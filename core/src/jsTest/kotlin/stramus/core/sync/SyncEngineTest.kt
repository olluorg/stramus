@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.Database
import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import stramus.core.db.CardRow
import stramus.core.db.Cards
import stramus.core.db.installIndexedDb
import stramus.core.db.stramusSchema
import stramus.protocol.RowKey
import stramus.protocol.SyncConflict
import stramus.protocol.SyncRequest
import stramus.protocol.SyncResponse
import stramus.protocol.SyncRow
import stramus.core.db.openStramusStore
import stramus.core.db.StoreSeed
import stramus.core.db.StramusStore

/**
 * `SyncEngine` had no automated coverage at all after the kidx migration (the old coverage came from
 * `server`'s `EndToEndSyncTest`, which drove it as a real JVM client — see that file's doc comment for
 * why it no longer can). These are narrower: a scripted [SyncApi] stands in for the server, so what is
 * checked is the engine's own bookkeeping — hashing, base versions, applying a delta, the conflict-copy
 * rule — not a real merge.
 */
class SyncEngineTest {

    @Test
    fun `signing in seeds device state`() = syncTest { db ->
        val engine = SyncEngine(db, ScriptedSyncApi { SyncResponse(rev = 0) })
        assertTrue(!engine.signedIn())
        engine.signIn(Uuid.random(), Uuid.random())
        assertTrue(engine.signedIn())
    }

    @Test
    fun `signing out forgets the account but a fresh sync then does nothing`() = syncTest { db ->
        val engine = SyncEngine(db, ScriptedSyncApi { SyncResponse(rev = 0) })
        engine.signIn(Uuid.random(), Uuid.random())
        engine.signOut()
        assertTrue(!engine.signedIn())
        assertEquals(null, engine.syncNow(), "no account: a sync is not a failure, it just does nothing")
    }

    @Test
    fun `a row the server hands back is written into the local store`() = syncTest { db ->
        val cardId = Uuid.random()
        val collectionId = Uuid.random()
        val row = cardRow(cardId, collectionId, "Kotlin", Clock.System.now())
        val api = ScriptedSyncApi { SyncResponse(rev = 1, rows = listOf(row)) }

        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        val result = engine.syncNow()

        assertEquals(1, result?.applied)
        val stored = db.read(Cards) { Cards.get(cardId) }
        assertEquals("Kotlin", stored?.title)
    }

    @Test
    fun `a tombstone the server hands back marks the row deleted, not absent`() = syncTest { db ->
        val cardId = Uuid.random()
        val t = Clock.System.now()
        val api = ScriptedSyncApi { req ->
            if (req.since == 0L) {
                SyncResponse(rev = 1, rows = listOf(cardRow(cardId, Uuid.random(), "Kotlin", t)))
            } else {
                SyncResponse(rev = 2, rows = listOf(cardRow(cardId, Uuid.random(), "Kotlin", t, deletedAt = t)))
            }
        }
        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()
        engine.syncNow()

        val stored = db.read(Cards) { Cards.get(cardId) }
        assertTrue(stored != null && stored.deletedAt != null, "the row should still be there, as a tombstone")
    }

    @Test
    fun `a delta that takes two pages is read whole, and moves the cursor once`() = syncTest { db ->
        val first = Uuid.random()
        val second = Uuid.random()
        val collectionId = Uuid.random()
        val now = Clock.System.now()
        val requests = mutableListOf<SyncRequest>()
        val api = ScriptedSyncApi { req ->
            requests += req
            if (req.cursor == null) {
                SyncResponse(
                    rev = 7,
                    rows = listOf(cardRow(first, collectionId, "Page one", now)),
                    hasMore = true,
                    nextCursor = "7|cards|${first}",
                )
            } else {
                SyncResponse(rev = 7, rows = listOf(cardRow(second, collectionId, "Page two", now)))
            }
        }

        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()

        assertEquals(2, requests.size, "a delta that says it has more should be asked for again")
        // The second page is the same question asked from the same place — the revision cannot say where
        // page one stopped, so moving `since` there would step over everything page two was going to hold.
        assertEquals(0L, requests[1].since)
        assertEquals("7|cards|$first", requests[1].cursor)
        assertEquals("Page two", db.read(Cards) { Cards.get(second) }?.title, "the second page has to land too")

        // Only now, with the delta finished, does the cursor move.
        engine.syncNow()
        assertEquals(7L, requests[2].since)
        assertEquals(null, requests[2].cursor)
    }

    @Test
    fun `a row already at its base version is not pushed again`() = syncTest { db ->
        val cardId = Uuid.random()
        val collectionId = Uuid.random()
        var lastRequest: SyncRequest? = null
        val api = ScriptedSyncApi { req ->
            lastRequest = req
            SyncResponse(rev = req.since + 1, accepted = req.rows.map { RowKey(it.tbl, it.id) })
        }
        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())

        db.write(Cards) { Cards.add(CardRow().apply {
            id = cardId; this.collectionId = collectionId; cardSectionId = null; kind = "link"
            title = "Kotlin"; url = "https://kotlinlang.org"; favicon = null; content = null; thumb = null
            mime = null; blobSha = null; orderKey = "a0"; createdAt = Clock.System.now(); updatedAt = Clock.System.now()
            deletedAt = null
        }) }

        engine.syncNow()
        val firstPush = lastRequest
        assertTrue(firstPush != null && firstPush.rows.any { it.id == cardId.toString() }, "the first sync should have pushed the new card")

        engine.syncNow()
        val secondPush = lastRequest
        assertTrue(secondPush != null && secondPush.rows.isEmpty(), "nothing changed locally since the base was recorded — nothing to push")
    }

    @Test
    fun `a conflicting note keeps the losing version as a copy`() = syncTest { db ->
        val noteId = Uuid.random()
        val collectionId = Uuid.random()
        val t = Clock.System.now()
        val serverVersion = noteRow(noteId, collectionId, "milk, coffee", t)

        val api = ScriptedSyncApi { req ->
            if (req.since == 0L) {
                SyncResponse(rev = 1, rows = listOf(noteRow(noteId, collectionId, "milk", t)))
            } else {
                // The push is rejected (the server's own edit is later) and reported as a conflict.
                SyncResponse(
                    rev = 2,
                    accepted = emptyList(),
                    conflicts = listOf(SyncConflict("cards", noteId.toString(), serverVersion)),
                    rows = listOf(serverVersion),
                )
            }
        }
        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()

        db.write(Cards) {
            val row = Cards.get(noteId)!!
            row.content = "milk, bread"
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }

        val result = engine.syncNow()
        assertEquals(1, result?.conflictCopies)

        val all = db.read(Cards) { Cards.all() }
        val bodies = all.mapNotNull { it.content }
        assertTrue("milk, coffee" in bodies, "the winning (server) version should be there: $bodies")
        assertTrue(bodies.any { it == "milk, bread" }, "the losing version should survive as a copy: $bodies")
        assertEquals(2, all.count { it.kind == "note" }, "the loser is a second card, not a merge")
    }

    @Test
    fun `usage rows are not pushed when the option is off`() = syncTest { db ->
        var lastRequest: SyncRequest? = null
        val api = ScriptedSyncApi { req -> lastRequest = req; SyncResponse(rev = req.since + 1) }
        val engine = SyncEngine(db, api, syncUsage = { false })
        engine.signIn(Uuid.random(), Uuid.random())

        db.write(stramus.core.db.Usage) {
            stramus.core.db.Usage.add(
                stramus.core.db.UsageRow().apply {
                    url = "kotlinlang.org/docs"; title = "Kotlin docs"; host = "kotlinlang.org"
                    hits = 1; lastUsedAt = Clock.System.now(); deletedAt = null
                },
            )
        }

        engine.syncNow()
        assertTrue(lastRequest!!.rows.none { it.tbl == "usage" }, "browsing stats must stay on the machine by default")
    }

    @Test
    fun `a row that came from the server is not sent straight back`() = syncTest { db ->
        // The ping-pong that eats a sync alive. A row arrives, is written down, and is read back on the
        // next run — if what `applyRemote` stores does not hash to what the server sent, this device
        // decides the row "changed here" and pushes it, the other device decides the same, and the two
        // of them trade one card for ever. Nothing about that is visible in the UI until the battery
        // is flat.
        val cardId = Uuid.random()
        val row = cardRow(cardId, Uuid.random(), "Kotlin", Clock.System.now())
        val pushes = mutableListOf<List<SyncRow>>()
        val api = ScriptedSyncApi { request ->
            pushes += request.rows
            if (request.since == 0L) SyncResponse(rev = 1, rows = listOf(row)) else SyncResponse(rev = 1)
        }

        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()
        engine.syncNow()

        assertTrue(pushes.all { it.isEmpty() }, "a row we were given is not a row we changed")
    }

    @Test
    fun `signing in pushes everything already in the browser`() = syncTest { db ->
        // Joining an account with collections already here: nothing has a base version, so all of it is
        // new as far as the server is concerned. This is the "keep my collections" half of the sign-in
        // question, and the reason merging by id alone leaves pairs behind for the duplicate finder.
        val cardId = Uuid.random()
        db.write(Cards) { Cards.add(card(cardId, "Mine")) }

        var pushed: List<SyncRow>? = null
        val api = ScriptedSyncApi { request ->
            pushed = request.rows
            SyncResponse(rev = 1, accepted = request.rows.map { RowKey(it.tbl, it.id) })
        }
        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()

        assertTrue(pushed!!.any { it.id == cardId.toString() }, "the card that was here has to go up")
    }

    @Test
    fun `signing in and discarding takes the local collections with it`() = syncTest { db ->
        // The other half of the same question, for the second device: a fresh install seeds itself a
        // welcome note, and joining an account that already holds years of collections must not hand
        // the user a second copy of our own greeting for ever.
        db.write(Cards) { Cards.add(card(Uuid.random(), "Mine")) }

        var pushed: List<SyncRow>? = null
        val engine = SyncEngine(db, ScriptedSyncApi { request -> pushed = request.rows; SyncResponse(rev = 0) })
        engine.signIn(Uuid.random(), Uuid.random(), discardLocal = true)
        engine.syncNow()

        assertEquals(emptyList(), db.read(Cards) { Cards.all() }, "the local rows are gone")
        assertTrue(pushed!!.isEmpty(), "and nothing of them was sent up on the way out")
    }

    @Test
    fun `asking for the account again re-reads it without pushing anything`() = syncTest { db ->
        val cardId = Uuid.random()
        val row = cardRow(cardId, Uuid.random(), "Kotlin", Clock.System.now())
        val sinces = mutableListOf<Long>()
        val api = ScriptedSyncApi { request ->
            sinces += request.since
            SyncResponse(rev = 7, rows = if (request.since == 0L) listOf(row) else emptyList())
        }
        val engine = SyncEngine(db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()
        engine.syncNow()

        engine.refetchEverything()
        engine.syncNow()

        assertEquals(listOf(0L, 7L, 0L), sinces, "the cursor goes back to the start, and only then")
    }

    @Test
    fun `a card deleted here becomes a tombstone that travels`() = syncStoreTest { store ->
        // Signed in, a deletion is a marked row rather than an absent one — it has to be *told* to the
        // other device, and an absent row tells nobody anything. Deleted through the repository rather
        // than by writing the row by hand, because which of the two a deletion is is precisely what the
        // repository decides (see `syncing()` in Store.kt).
        val pushes = mutableListOf<List<SyncRow>>()
        val api = ScriptedSyncApi { request ->
            pushes += request.rows
            SyncResponse(rev = 1, accepted = request.rows.map { RowKey(it.tbl, it.id) })
        }
        val engine = SyncEngine(store.db, api)
        engine.signIn(Uuid.random(), Uuid.random())
        engine.syncNow()

        val collection = store.collections.all().first()
        val doomed = store.cards.add(collection.id, "Doomed", "https://example.org/doomed", null)
        engine.syncNow()
        store.cards.delete(doomed.id)
        engine.syncNow()

        val tombstone = pushes.last().firstOrNull { it.id == doomed.id.toString() }
        assertTrue(tombstone != null && tombstone.deletedAt != null, "the deletion has to go up as one")
        assertEquals(null, tombstone.payload, "and a tombstone carries nothing of what it deleted")
    }

    @Test
    fun `erasing the browser leaves neither the rows nor the account behind`() = syncTest { db ->
        db.write(Cards) { Cards.add(card(Uuid.random(), "Mine")) }
        val engine = SyncEngine(db, ScriptedSyncApi { SyncResponse(rev = 0) })
        engine.signIn(Uuid.random(), Uuid.random())

        engine.eraseLocalData()

        assertEquals(emptyList(), db.read(Cards) { Cards.all() })
        assertTrue(!engine.signedIn(), "the bookkeeping that said whose account they were goes too")
    }
}

private class ScriptedSyncApi(private val responder: (SyncRequest) -> SyncResponse) : SyncApi {
    override suspend fun sync(request: SyncRequest): SyncResponse = responder(request)
}

/** A card row as the store itself would write one — the local half of what a push is chosen from. */
private fun card(id: Uuid, title: String) = CardRow().apply {
    this.id = id
    collectionId = Uuid.random()
    cardSectionId = null
    kind = "link"
    this.title = title
    url = "https://example.org/${'$'}title"
    favicon = null
    content = null
    thumb = null
    mime = null
    blobSha = null
    orderKey = "a0"
    createdAt = Clock.System.now()
    updatedAt = Clock.System.now()
    deletedAt = null
}

private fun payload(vararg pairs: Pair<String, String?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to (v?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)) })

private fun cardRow(
    id: Uuid,
    collectionId: Uuid,
    title: String,
    updatedAt: kotlin.time.Instant,
    deletedAt: kotlin.time.Instant? = null,
) = SyncRow(
    tbl = "cards",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else payload(
        "collectionId" to collectionId.toString(), "cardSectionId" to null, "kind" to "link",
        "title" to title, "url" to "https://example.org", "favicon" to null, "content" to null,
        "thumb" to null, "mime" to null, "blobSha" to null, "orderKey" to "a0", "createdAt" to updatedAt.toString(),
    ),
)

private fun noteRow(id: Uuid, collectionId: Uuid, content: String, updatedAt: kotlin.time.Instant) = SyncRow(
    tbl = "cards",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    payload = payload(
        "collectionId" to collectionId.toString(), "cardSectionId" to null, "kind" to "note",
        "title" to "Shopping", "url" to "", "favicon" to null, "content" to content,
        "thumb" to null, "mime" to null, "blobSha" to null, "orderKey" to "a0", "createdAt" to updatedAt.toString(),
    ),
)

/** The same, for the tests that need the repositories over the database rather than the database. */
private fun syncStoreTest(block: suspend (StramusStore) -> Unit) = runTest {
    installIndexedDb()
    deleteDatabase(stramusSchema.databaseName)
    val db = openDatabase(stramusSchema)
    val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))
    try {
        block(store)
    } finally {
        store.close()
    }
}

private fun syncTest(block: suspend (Database) -> Unit) = runTest {
    installIndexedDb()
    deleteDatabase(stramusSchema.databaseName)
    val db = openDatabase(stramusSchema)
    try {
        block(db)
    } finally {
        db.close()
    }
}
