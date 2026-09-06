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
    fun `a delta bigger than one page is handed over whole, not cut off at the first 500`() = runTest {
        val sync = newSync()
        // One push, so every one of these rows is stamped with the same revision — which is what a first
        // sync from a device with a few hundred cards actually looks like.
        val many = (1..620).map { card("c$it", "Card $it", at = t(1)) }
        sync.sync(user, laptop, since = 0, pushed = many)

        // The phone reads the account for the first time and keeps asking while the server says there is
        // more, which is the whole of what the protocol asks of it.
        val seen = mutableSetOf<String>()
        var page = sync.sync(user, phone, since = 0, pushed = emptyList())
        var guard = 0
        while (true) {
            page.rows.forEach { seen += it.id }
            if (!page.hasMore) break
            check(guard++ < 20) { "the delta never ends" }
            page = sync.sync(user, phone, since = 0, pushed = emptyList(), cursor = page.nextCursor)
        }

        assertEquals(620, seen.size, "every row of the account has to arrive, not just the first page")
    }

    @Test
    fun `a deletion for a row the server never had is still a deletion`() = runTest {
        // The order two devices reach the server in is not the order things happened in. A card made
        // and deleted while offline arrives as a tombstone for a row nobody here has ever seen, and
        // dropping it because "there is nothing to delete" is how the card comes back from the dead the
        // moment the device that made it gets its own turn.
        val sync = newSync()

        sync.sync(user, phone, since = 0, pushed = listOf(tombstone("a", at = t(2))))
        val onTheLaptop = sync.sync(user, laptop, since = 0, pushed = emptyList())

        val row = onTheLaptop.rows.single { it.id == "a" }
        assertTrue(row.deletedAt != null, "the tombstone is kept as one")
        assertNull(row.payload)
    }

    @Test
    fun `an edit made after a deletion brings the row back, and one made before does not`() = runTest {
        val sync = newSync()
        sync.sync(user, laptop, since = 0, pushed = listOf(card("a", "Kotlin", at = t(1))))

        // Deleted at t2, and edited at t1 — the edit is older than the deletion and loses to it.
        val deleted = sync.sync(user, laptop, since = 1, pushed = listOf(tombstone("a", at = t(2))))
        val stale = sync.sync(user, phone, since = 0, pushed = listOf(card("a", "Stale", at = t(1))))
        assertTrue(stale.accepted.none { it.id == "a" }, "an edit older than the deletion does not undo it")

        // And at t3 somebody puts it back on purpose, which is a decision and has to stand.
        val revived = sync.sync(user, phone, since = deleted.rev, pushed = listOf(card("a", "Back", at = t(3))))
        assertTrue(revived.accepted.any { it.id == "a" })

        val seen = sync.sync(user, laptop, since = deleted.rev, pushed = emptyList()).rows.single { it.id == "a" }
        assertNull(seen.deletedAt, "the row is alive again")
        assertEquals("Back", seen.payload!!.text("title"))
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
    fun `paging does not skip a row whose id sorts differently in one order than another`() = runTest {
        // A `usage` row is keyed by the page's address, so unlike every other table its ids are not
        // uuids: they are URLs, and a URL can hold anything. Two of the ones below sort one way by UTF-8
        // bytes (which is what SQLite's default collation compares) and the other way by UTF-16 code
        // units (which is what Kotlin's String.compareTo compares) — an emoji is above U+FF01 in one and
        // below it in the other. Paging reads in one order and resumes after a cursor compared in
        // another, and if those two ever disagree the row between them is skipped in silence.
        // One row a page, so the cursor is asked the question once per row rather than never.
        val sync = newSync(deltaLimit = 1)
        val ids = listOf(
            "example.org/\uFF01",
            "example.org/\uD83D\uDE00",
            "example.org/plain",
            "пример.рф/страница",
            "example.org/a|b",
        )
        sync.sync(user, laptop, since = 0, pushed = ids.map { usage(it, hits = 1, at = t(1)) })

        val seen = mutableSetOf<String>()
        var page = sync.sync(user, phone, since = 0, pushed = emptyList())
        var guard = 0
        while (true) {
            page.rows.forEach { seen += it.id }
            if (!page.hasMore) break
            check(guard++ < 20) { "the delta never ends" }
            page = sync.sync(user, phone, since = 0, pushed = emptyList(), cursor = page.nextCursor)
        }

        assertEquals(ids.toSet(), seen, "every row, whatever its id sorts like — and whatever it contains")
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

private fun newSync(deltaLimit: Int = 500): SyncService {
    val config = ServerConfig(
        databasePath = createTempDirectory("stramus-sync-test").resolve("s.db").toString(),
        deltaLimit = deltaLimit,
    )
    return SyncService(openServerDatabase(config), config.deltaLimit)
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
