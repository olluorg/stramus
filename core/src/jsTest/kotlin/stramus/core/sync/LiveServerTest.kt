@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.Schema
import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import stramus.core.db.StoreSeed
import stramus.core.db.StramusStore
import stramus.core.db.installIndexedDb
import stramus.core.db.openStramusStore
import stramus.core.db.stramusSchema

/**
 * The last thing neither half's tests could reach: the real client, over real HTTP, against the real
 * server — Ktor at both ends, real JSON, a real token, a real SQLite file.
 *
 * `ContractTest` runs the real engine against the real *decisions*, which is most of the risk; this runs
 * it against the real *server*, which is the rest: the transport, the serialisation, the login, the
 * refresh of an access token that has run out mid-sync, and the queries that find and order the rows the
 * shared decisions are applied to.
 *
 * It needs a server, so it only runs when there is one — the build starts it and says where, and without
 * that this file is quiet rather than red. That is a deliberate trade and worth stating plainly: run
 * with `-PliveSync` (which CI passes) and these are real; run without and the suite is the poorer for
 * it. Everything else in `core`'s tests runs unconditionally.
 */
class LiveServerTest {

    @Test
    fun `two real browsers, one real server, and the same collections on both`() = liveTest { url ->
        val ada = "ada-${Uuid.random()}@example.org"
        val a = browser("live-a", url, ada, register = true)
        val b = browser("live-b", url, ada, register = false)

        val collection = a.store.collections.all().first()
        a.store.cards.add(collection.id, "Kotlin", "https://kotlinlang.org/", null)
        val group = a.store.cardSections.create(collection.id, "Docs", "about them")
        a.store.cards.add(collection.id, "MDN", "https://developer.mozilla.org/", null, cardSectionId = group.id)
        a.store.cards.addNote(collection.id, "A note", "some words")
        a.engine.syncNow()

        b.engine.syncNow()

        assertEquals(1, b.cardsTitled("Kotlin"))
        assertEquals(1, b.cardsTitled("MDN"))
        assertEquals(listOf("Docs"), b.store.cardSections.byCollection(collection.id).map { it.title })

        // A deletion, over the wire, and the second browser stops showing it.
        val doomed = a.store.cards.byCollection(collection.id).first { it.title == "Kotlin" }
        a.store.cards.delete(doomed.id)
        a.engine.syncNow()
        b.engine.syncNow()
        assertEquals(0, b.cardsTitled("Kotlin"))

        // And the pair goes quiet: nothing left for either to say.
        val quiet = a.engine.syncNow()
        assertEquals(0, quiet?.pushed ?: -1, "a settled device pushes nothing over HTTP either")

        a.store.close()
        b.store.close()
    }

    @Test
    fun `an account bigger than a page comes down whole over the wire`() = liveTest { url ->
        val ada = "big-${Uuid.random()}@example.org"
        val a = browser("live-big-a", url, ada, register = true)
        val b = browser("live-big-b", url, ada, register = false)

        val collection = a.store.collections.all().first()
        repeat(60) { n -> a.store.cards.add(collection.id, "Card $n", "https://example.org/$n", null) }
        a.engine.syncNow()

        b.engine.syncNow()

        val arrived = b.store.collections.all().flatMap { b.store.cards.byCollection(it.id) }
        assertEquals(60, arrived.count { it.url.startsWith("https://example.org/") })

        a.store.close()
        b.store.close()
    }
}

// ---- a browser, for real ----------------------------------------------------------------------------

private class Browser(val store: StramusStore, val engine: SyncEngine) {
    suspend fun cardsTitled(title: String): Int =
        store.collections.all().flatMap { store.cards.byCollection(it.id) }.count { it.title == title }
}

private suspend fun browser(name: String, url: String, email: String, register: Boolean): Browser {
    val schema = Schema(name, stramusSchema.migrations)
    deleteDatabase(name)
    val db = openDatabase(schema)
    val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag here."))

    // A localStorage of its own per browser: the real api keeps the device id and the refresh token
    // there, and two browsers sharing one would be one browser wearing two names.
    installLocalStorage()
    val api = StramusApi(url)
    val me = if (register) api.register(email, PASSWORD) else api.login(email, PASSWORD)
    val engine = SyncEngine(store.db, api, api)
    engine.signIn(Uuid.parse(me.userId), api.deviceId, discardLocal = !register)
    return Browser(store, engine)
}

private const val PASSWORD = "correct horse battery staple"

/** Node has no `localStorage`; the real api wants one. A map is the whole of what it uses. */
private fun installLocalStorage() {
    js(
        """
        (function () {
            var store = {};
            globalThis.localStorage = {
                getItem: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
                setItem: function (k, v) { store[k] = String(v); },
                removeItem: function (k) { delete store[k]; },
                clear: function () { store = {}; }
            };
        })();
        """,
    )
}

/** Where the build put the server, if it started one. Without it these tests have nothing to talk to. */
private fun liveServerUrl(): String? {
    val value = js("(typeof process !== 'undefined' && process.env && process.env.STRAMUS_LIVE_URL) || null")
    return (value as? String)?.takeIf { it.isNotBlank() }
}

private fun liveTest(block: suspend (String) -> Unit) = runTest {
    val url = liveServerUrl()
    if (url == null) {
        // Said out loud rather than passed in silence: a test that quietly does nothing is worse than no
        // test, because it looks like coverage on the report.
        println("LiveServerTest: skipped — no STRAMUS_LIVE_URL. Run with -PliveSync to start a server.")
        return@runTest
    }
    installIndexedDb()
    block(url)
}
