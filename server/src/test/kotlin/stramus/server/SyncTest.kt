@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import stramus.protocol.SyncRow

/**
 * The merge, exercised the way two devices exercise it.
 *
 * The case worth reading is `a row changed on both sides is a conflict`: everything the base-version
 * bookkeeping costs is paid to tell that case apart from `a row changed on one side`, and a merge that
 * cannot tell them apart silently throws a version away in the first while believing it is in the second.
 */
class SyncTest {

    private val laptop = Uuid.random()
    private val phone = Uuid.random()
    private val user = Uuid.random()

    @Test
    fun `a first sync takes everything and hands back a cursor`() = runTest {
        val sync = newSync()

        val response = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))

        assertEquals(1, response.rev)
        assertEquals(1, response.accepted.size)
        // What this device just sent is not echoed back to it — it already has it.
        assertTrue(response.rows.isEmpty())
    }

    @Test
    fun `the other device gets what the first one wrote`() = runTest {
        val sync = newSync()
        sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))

        val onThePhone = sync.sync(user, phone, since = 0, pushed = emptyList())

        assertEquals(1, onThePhone.rows.size)
        assertEquals("Kotlin", onThePhone.rows.single().payload!!.text("title"))
        assertEquals(1, onThePhone.rev)
    }

    @Test
    fun `a row changed on one side only is not a conflict`() = runTest {
        val sync = newSync()
        val first = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))
        val phoneRead = sync.sync(user, phone, since = 0, pushed = emptyList())

        // The phone edits a row nobody else has touched since it last looked. That is the ordinary case,
        // and it must not be dressed up as a disagreement.
        val edited = sync.sync(user, phone, since = phoneRead.rev, pushed = listOf(card("a", "Kotlin docs", at = t(2))))

        assertTrue(edited.conflicts.isEmpty())
        assertEquals(1, edited.accepted.size)

        val backOnTheLaptop = sync.sync(user, laptop, since = first.rev, pushed = emptyList())
        assertEquals("Kotlin docs", backOnTheLaptop.rows.single().payload!!.text("title"))
    }

    @Test
    fun `a row changed on both sides is a conflict, and the later write wins`() = runTest {
        val sync = newSync()
        val start = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))
        val phoneRead = sync.sync(user, phone, since = 0, pushed = emptyList())

        // Both devices now hold revision 1 and both edit the row. The laptop's edit lands first, but the
        // phone's is *later on the clock* — and the clock is what decides.
        sync.sync(user, laptop, since = start.rev, pushed = listOf(card("a", "from the laptop", at = t(2))))
        val fromThePhone = sync.sync(user, phone, since = phoneRead.rev, pushed = listOf(card("a", "from the phone", at = t(3))))

        assertEquals(1, fromThePhone.conflicts.size)
        // The server hands back what it held before the merge, whoever won: the losing version of a note
        // is kept as a copy by the client, and it cannot do that without seeing it.
        assertEquals("from the laptop", fromThePhone.conflicts.single().server.payload!!.text("title"))
        assertEquals(1, fromThePhone.accepted.size)

        val laptopCatchesUp = sync.sync(user, laptop, since = start.rev, pushed = emptyList())
        assertEquals("from the phone", laptopCatchesUp.rows.single { it.id == "a" }.payload!!.text("title"))
    }

    @Test
    fun `the earlier write loses, and comes back in the delta unasked`() = runTest {
        val sync = newSync()
        val start = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))
        val phoneRead = sync.sync(user, phone, since = 0, pushed = emptyList())

        sync.sync(user, laptop, since = start.rev, pushed = listOf(card("a", "from the laptop", at = t(5))))
        // The phone's edit is older than the laptop's, so it loses. It is not accepted — and the winner
        // travels back to the phone in the same answer, without it having to ask again.
        val fromThePhone = sync.sync(user, phone, since = phoneRead.rev, pushed = listOf(card("a", "from the phone", at = t(2))))

        assertTrue(fromThePhone.accepted.isEmpty())
        assertEquals(1, fromThePhone.conflicts.size)
        assertEquals("from the laptop", fromThePhone.rows.single { it.id == "a" }.payload!!.text("title"))
    }

    @Test
    fun `a deletion travels, and does not come back to life`() = runTest {
        val sync = newSync()
        val start = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))
        sync.sync(user, laptop, since = start.rev, pushed = listOf(tombstone("a", at = t(2))))

        val onThePhone = sync.sync(user, phone, since = 0, pushed = emptyList())

        val row = onThePhone.rows.single { it.id == "a" }
        assertEquals(t(2).toString(), row.deletedAt)
        assertNull(row.payload, "a tombstone carries nothing — what is deleted is not kept in case it returns")
    }

    @Test
    fun `counters merge by maximum instead of by last write`() = runTest {
        val sync = newSync()
        val start = sync.sync(user, laptop, since = 0, pushed = listOf(usage("kotlinlang.org", hits = 7, at = t(1))))
        val phoneRead = sync.sync(user, phone, since = 0, pushed = emptyList())

        // Both devices count openings while apart. Last-write-wins would take the later row whole and
        // throw the other device's tally away; a counter has to keep the larger.
        sync.sync(user, laptop, since = start.rev, pushed = listOf(usage("kotlinlang.org", hits = 9, at = t(2))))
        sync.sync(user, phone, since = phoneRead.rev, pushed = listOf(usage("kotlinlang.org", hits = 3, at = t(3))))

        val afterwards = sync.sync(user, phone, since = 0, pushed = emptyList())
        val merged = afterwards.rows.single { it.tbl == "usage" }
        assertEquals("9", merged.payload!!.text("hits"), "the larger tally should have survived")
    }

    @Test
    fun `a device with a broken clock cannot win every conflict for ever`() = runTest {
        val sync = newSync()
        val start = sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))
        val phoneRead = sync.sync(user, phone, since = 0, pushed = emptyList())

        // The phone's calendar says next year. Left alone, it would beat every write the laptop ever
        // makes; clamped to the server's clock, it is simply another write.
        val nextYear = Clock.System.now() + (365 * 24).hours
        sync.sync(user, phone, since = phoneRead.rev, pushed = listOf(card("a", "from the future", at = nextYear)))

        val later = Clock.System.now() + 1.minutes
        val laptopWrites = sync.sync(user, laptop, since = start.rev, pushed = listOf(card("a", "from the present", at = later)))

        assertEquals(1, laptopWrites.accepted.size, "the honest clock should still be able to win")
    }

    @Test
    fun `one account cannot see another's rows`() = runTest {
        val sync = newSync()
        val other = Uuid.random()
        sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))

        val theirs = sync.sync(other, phone, since = 0, pushed = emptyList())
        assertTrue(theirs.rows.isEmpty())
    }
}

// ---- helpers ---------------------------------------------------------------------------------------

private fun newSync(): SyncService {
    val config = ServerConfig(databasePath = createTempDirectory("stramus-sync-test").resolve("s.db").toString())
    return SyncService(openServerDatabase(config))
}

private fun t(minute: Int): Instant = Instant.parse("2026-07-14T12:0$minute:00Z")

private fun card(id: String, title: String, at: Instant) = SyncRow(
    tbl = "cards",
    id = id,
    updatedAt = at.toString(),
    payload = JsonObject(mapOf("title" to JsonPrimitive(title), "url" to JsonPrimitive("https://example.org/$id"))),
)

private fun tombstone(id: String, at: Instant) =
    SyncRow(tbl = "cards", id = id, updatedAt = at.toString(), deletedAt = at.toString(), payload = null)

private fun usage(url: String, hits: Int, at: Instant) = SyncRow(
    tbl = "usage",
    id = url,
    updatedAt = at.toString(),
    payload = JsonObject(
        mapOf(
            "url" to JsonPrimitive(url),
            "hits" to JsonPrimitive(hits),
            "lastUsedAt" to JsonPrimitive(at.toString()),
        ),
    ),
)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
