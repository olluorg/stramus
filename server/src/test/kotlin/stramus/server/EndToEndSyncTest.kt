@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import stramus.core.db.StramusDb
import stramus.core.db.StoreSeed
import stramus.core.db.StramusStore
import stramus.core.db.openStramusStore
import stramus.core.sync.SyncApi
import stramus.core.sync.SyncEngine
import stramus.protocol.LoginRequest
import stramus.protocol.RegisterRequest
import stramus.protocol.SyncRequest
import stramus.protocol.TokenPair

/**
 * Two devices and a server, doing what two devices and a server do.
 *
 * The client store, the sync engine and the server are three pieces that are each correct on their own and
 * whose whole purpose lives in the space between them: this is the only test that exercises that space —
 * over real HTTP, through the real protocol, against real SQLite on both ends. The client here is the same
 * common code that runs as Kotlin/JS in the browser; only the engine underneath it differs.
 */
class EndToEndSyncTest {

    @Test
    fun `a card saved on one device turns up on the other`() = twoDevices { laptop, phone ->
        val collection = laptop.store.collections.all().first().id
        laptop.store.cards.add(collection, "Kotlin", "https://kotlinlang.org", null)

        laptop.engine.syncNow()
        phone.engine.syncNow()

        val onThePhone = phone.store.cards.byCollection(collection)
        assertTrue(onThePhone.any { it.title == "Kotlin" }, "the phone should have the card: $onThePhone")
    }

    @Test
    fun `a deletion travels, and the card does not come back on the next sync`() = twoDevices { laptop, phone ->
        val collection = laptop.store.collections.all().first().id
        val card = laptop.store.cards.add(collection, "Kotlin", "https://kotlinlang.org", null)
        laptop.engine.syncNow()
        phone.engine.syncNow()

        laptop.store.cards.delete(card.id)
        laptop.engine.syncNow()
        phone.engine.syncNow()

        assertTrue(phone.store.cards.byCollection(collection).none { it.id == card.id })

        // And it stays gone. Without a tombstone, the phone — which still had the row when it last looked —
        // would push it back up on its next run, and the laptop would obligingly restore it.
        phone.engine.syncNow()
        laptop.engine.syncNow()
        assertTrue(laptop.store.cards.byCollection(collection).none { it.id == card.id })
        assertTrue(phone.store.cards.byCollection(collection).none { it.id == card.id })
    }

    @Test
    fun `a card moved on one device lands in the same place on the other`() = twoDevices { laptop, phone ->
        val collection = laptop.store.collections.all().first().id
        listOf("a", "b", "c").forEach { laptop.store.cards.add(collection, it, "https://example.org/$it", null) }
        laptop.engine.syncNow()
        phone.engine.syncNow()

        val c = laptop.store.cards.byCollection(collection).first { it.title == "c" }
        laptop.store.cards.move(c.id, collection, null, 0)
        laptop.engine.syncNow()
        phone.engine.syncNow()

        val order = phone.store.cards.byCollection(collection).map { it.title }
        assertEquals(listOf("c", "a", "b"), order.filter { it in listOf("a", "b", "c") })
    }

    @Test
    fun `two devices editing different cards do not fight`() = twoDevices { laptop, phone ->
        val collection = laptop.store.collections.all().first().id
        val first = laptop.store.cards.add(collection, "first", "https://example.org/1", null)
        val second = laptop.store.cards.add(collection, "second", "https://example.org/2", null)
        laptop.engine.syncNow()
        phone.engine.syncNow()

        laptop.store.cards.rename(first.id, "renamed on the laptop")
        phone.store.cards.rename(second.id, "renamed on the phone")

        laptop.engine.syncNow()
        phone.engine.syncNow()
        laptop.engine.syncNow()

        val titles = laptop.store.cards.byCollection(collection).map { it.title }.toSet()
        assertTrue("renamed on the laptop" in titles, titles.toString())
        assertTrue("renamed on the phone" in titles, titles.toString())
    }

    @Test
    fun `a note edited on both devices at once ends up as two notes, not one`() = twoDevices { laptop, phone ->
        val collection = laptop.store.collections.all().first().id
        val note = laptop.store.cards.addNote(collection, "Shopping", "milk")
        laptop.engine.syncNow()
        phone.engine.syncNow()

        // Both write, neither having seen the other. Last-write-wins would take one body and drop the
        // other on the floor — and what was dropped is somebody's writing.
        laptop.store.cards.updateNote(note.id, "Shopping", "milk, bread")
        phone.store.cards.updateNote(note.id, "Shopping", "milk, coffee")

        laptop.engine.syncNow()
        val onThePhone = phone.engine.syncNow()!!
        assertEquals(1, onThePhone.conflictCopies, "the losing version should have been kept as a copy")

        val bodies = phone.store.cards.byCollection(collection).map { it.content }
        assertTrue(bodies.any { it == "milk, bread" }, "one version should be there: $bodies")
        assertTrue(bodies.any { it == "milk, coffee" }, "and so should the other: $bodies")

        // The copy is an ordinary card, so it goes up like anything else, and the laptop sees it too.
        phone.engine.syncNow()
        laptop.engine.syncNow()
        val onTheLaptop = laptop.store.cards.byCollection(collection).mapNotNull { it.content }
        assertTrue(onTheLaptop.any { it == "milk, coffee" }, "the copy should have reached the laptop: $onTheLaptop")
    }

    @Test
    fun `everything the user has made turns up on a device that joins the account`() = twoDevices { laptop, phone ->
        val section = laptop.store.sections.create("Work")
        val collection = laptop.store.collections.create("Reading", section.id)
        val group = laptop.store.cardSections.create(collection.id, "Later", "for the weekend")
        laptop.store.cards.add(collection.id, "Kotlin", "https://kotlinlang.org", null, cardSectionId = group.id)
        laptop.store.cards.addNote(collection.id, "a note", "with a body")

        laptop.engine.syncNow()
        phone.engine.syncNow()

        assertTrue(phone.store.sections.all().any { it.title == "Work" })
        assertTrue(phone.store.collections.all().any { it.title == "Reading" })
        assertEquals(listOf("Later"), phone.store.cardSections.byCollection(collection.id).map { it.title })
        val cards = phone.store.cards.byCollection(collection.id)
        assertEquals(setOf("Kotlin", "a note"), cards.map { it.title }.toSet())
        assertEquals("with a body", cards.first { it.title == "a note" }.content)
        assertEquals(group.id, cards.first { it.title == "Kotlin" }.cardSectionId)
    }

    @Test
    fun `nothing is pushed twice — a second sync with no changes sends nothing`() = twoDevices { laptop, _ ->
        val collection = laptop.store.collections.all().first().id
        laptop.store.cards.add(collection, "Kotlin", "https://kotlinlang.org", null)

        val first = laptop.engine.syncNow()!!
        assertTrue(first.pushed > 0)

        // The base version is what makes this true: a row whose hash still matches what the server
        // confirmed is not "changed", so a quiet device is a silent one.
        val second = laptop.engine.syncNow()!!
        assertEquals(0, second.pushed)
        assertEquals(0, second.applied)
    }
}

// ---- harness ---------------------------------------------------------------------------------------

private class Device(val store: StramusStore, val engine: SyncEngine)

/**
 * One account, two devices, one server. The phone joins an account that already exists, so it starts by
 * throwing away what a fresh install seeded itself — otherwise the user would end up with two "Main"s.
 */
private fun twoDevices(block: suspend (Device, Device) -> Unit) = testApplication {
    val config = ServerConfig(databasePath = tempPath("server"))
    application { stramusModule(config, openServerDatabase(config)) }

    val http = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    val laptopId = Uuid.random()
    val phoneId = Uuid.random()

    val registered: TokenPair = http.post("/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("ada@example.org", "correct horse battery", laptopId.toString()))
    }.body()

    val phoneTokens: TokenPair = http.post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest("ada@example.org", "correct horse battery", phoneId.toString()))
    }.body()

    val userId = Uuid.random() // the client only needs *an* id to mark the database as signed in

    val laptop = device(http, registered.accessToken, userId, laptopId, discardLocal = false)
    val phone = device(http, phoneTokens.accessToken, userId, phoneId, discardLocal = true)

    block(laptop, phone)
}

private suspend fun device(
    http: HttpClient,
    accessToken: String,
    userId: Uuid,
    deviceId: Uuid,
    discardLocal: Boolean,
): Device {
    val db: SuspendDatabase<StramusDb> = createSqliteDatabase(tempPath("client-$deviceId"))
    val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))

    // The engine talks to whatever this hands it. In the app that is a Ktor client over HTTPS; here it is
    // a Ktor client over the test host — the same JSON, the same routes, the same token.
    val api = SyncApi { request: SyncRequest ->
        http.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    val engine = SyncEngine(db, api)
    engine.signIn(userId, deviceId, discardLocal)
    return Device(store, engine)
}

private fun tempPath(name: String): String =
    createTempDirectory("stramus-e2e").resolve("$name.db").toString()
