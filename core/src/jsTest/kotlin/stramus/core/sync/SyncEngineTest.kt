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
}

private class ScriptedSyncApi(private val responder: (SyncRequest) -> SyncResponse) : SyncApi {
    override suspend fun sync(request: SyncRequest): SyncResponse = responder(request)
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
