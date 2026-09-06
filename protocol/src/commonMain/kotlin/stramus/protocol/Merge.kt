package stramus.protocol

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The decisions synchronisation is made of, apart from the database that stores the answers.
 *
 * They live here, beside the wire format, because both sides of the conversation need them to be the
 * same decisions — and until they were written down in one place, they were the same only by the
 * author remembering to keep them so. That is not a theoretical worry: the client was tested against a
 * scripted stand-in for the server and the server against a hand-rolled stand-in for the client, both
 * suites were green, and the pair was broken — the server answered "there is more" while the cursor it
 * handed back said "you have read everything". Neither stand-in could disagree with the code that
 * wrote it.
 *
 * What is here is every choice: what beats what, what counts as a conflict, how a counter adds up, and
 * where a page of the delta stops. What is *not* here is storage — finding a row and writing it down is
 * the server's, and a query over a real table has no business being a pure function over a list.
 */

/** A row as a server holds it: the row, and which device wrote it last (the tie-break, see [wins]). */
data class ServerRow(val row: SyncRow, val deviceId: String)

/**
 * What to do with one pushed row.
 *
 * [write] is the version that should be stored, or null to leave the server's own standing. [conflict]
 * is the server's version handed back to the client — set only when the row really did change on both
 * sides, whichever of them won, because only the client can tell a note from a bookmark and keep the
 * losing text as a copy.
 */
data class RowVerdict(val write: SyncRow?, val conflict: SyncRow?) {
    val accepted: Boolean get() = write != null
}

/**
 * A device whose clock is this far ahead is not believed: its `updatedAt` is clamped to the server's
 * own time. Without it, one machine with a calendar set to 2031 wins every conflict it takes part in
 * for years, and nothing the other devices do ever sticks. Clamping costs a correct clock nothing.
 */
val MAX_CLOCK_SKEW = 5.minutes

/** A row from a device whose clock is far ahead is stamped with the server's time instead. */
fun SyncRow.clamped(now: Instant): SyncRow {
    val stated = Instant.parse(updatedAt)
    return if (stated > now + MAX_CLOCK_SKEW) copy(updatedAt = now.toString()) else this
}

/**
 * The whole of what a server decides about one pushed row.
 *
 * 1. **No such row here** → take it. A new card, or a deletion for a card this server never saw, which
 *    is kept as the deletion it is: dropping it because "there is nothing to delete" is how a card made
 *    and deleted offline comes back to life the moment its creation arrives on its own.
 * 2. **A counter** ([COUNTER_TABLES]) → added up rather than overwritten; see [mergeCounter].
 * 3. **Changed here since this device last looked** (`existing.rev > since`) → a real conflict. The
 *    later write wins, and the server's version travels back either way.
 * 4. **Otherwise** → the ordinary case, and the one the base version is paid for: the device is the
 *    only one to have touched the row, so its version simply lands and nothing is in dispute.
 */
fun mergeRow(
    incoming: SyncRow,
    existing: ServerRow?,
    since: Long,
    deviceId: String,
    now: Instant,
): RowVerdict {
    val row = incoming.clamped(now)
    if (existing == null) return RowVerdict(write = row, conflict = null)

    if (row.tbl in COUNTER_TABLES) {
        return RowVerdict(write = mergeCounter(existing.row, row), conflict = null)
    }

    val changedOnServer = existing.row.rev > since
    val conflict = if (changedOnServer) existing.row else null

    // Later write wins; an exact tie goes to the larger device id — arbitrary, but agreed, so that two
    // machines settle it the same way without asking each other.
    val mine = Instant.parse(row.updatedAt)
    val theirs = Instant.parse(existing.row.updatedAt)
    val takes = when {
        mine > theirs -> true
        mine < theirs -> false
        else -> deviceId > existing.deviceId
    }
    return RowVerdict(write = if (takes) row else null, conflict = conflict)
}

/**
 * The merge for a counter — and for the one thing that can happen to a counter that is not counting.
 *
 * Two tallies merge by taking the larger of each field: neither device's openings are thrown away,
 * which is what taking the whole row of whoever wrote last would do. (Not a true distributed counter —
 * two devices that each count five while apart end up with five, not ten. Making that exact means a
 * per-device tally and a table many times the size, to sharpen a number whose only job is to sort a
 * search box.)
 *
 * **Forgetting is different, and it is the case that matters.** "Stop suggesting this page" is not a
 * smaller count; it is an instruction, and merging it by maximum would ignore it entirely — the other
 * device still has the tally, pushes it back, and the page the user dismissed is at the top of the box
 * again. So a tombstone beats any count made *before* it, and only a use made *after* it brings the
 * page back — which is right, because opening a page again is the user changing their mind.
 *
 * Returns null when the server's version stands and nothing should be written.
 */
fun mergeCounter(server: SyncRow, incoming: SyncRow): SyncRow? {
    val incomingAt = Instant.parse(incoming.updatedAt)
    val serverAt = Instant.parse(server.updatedAt)
    val forgetting = incoming.deletedAt != null
    val forgotten = server.deletedAt != null

    return when {
        forgetting && forgotten -> if (incomingAt >= serverAt) incoming else null
        forgetting -> if (incomingAt >= serverAt) incoming else null
        forgotten -> if (incomingAt > serverAt) incoming else null
        else -> mergeTallies(server, incoming)
    }
}

/** Two live tallies: the larger of each field, so nothing either device counted is lost. */
private fun mergeTallies(server: SyncRow, incoming: SyncRow): SyncRow {
    val serverPayload = server.payload ?: return incoming
    val incomingPayload = incoming.payload ?: return incoming

    val hits = maxOf(serverPayload.longField("hits"), incomingPayload.longField("hits"))
    val lastUsedAt = listOfNotNull(
        serverPayload.textField("lastUsedAt"),
        incomingPayload.textField("lastUsedAt"),
    ).maxOrNull()

    // The rest of the fields (a page's title, its host) come from whichever row is the later one: they
    // are values, not tallies, and there is nothing to add up.
    val later = if (Instant.parse(incoming.updatedAt) >= Instant.parse(server.updatedAt)) {
        incomingPayload
    } else {
        serverPayload
    }
    return incoming.copy(payload = later.withFields("hits" to hits.toString(), "lastUsedAt" to lastUsedAt))
}

/**
 * Where a page of the delta stopped: the row's (rev, tbl, id), which is the order the delta is read in.
 *
 * A revision is not fine enough to page by. One push takes one revision and stamps every row it wrote
 * with it — so a device joining an account meets its whole history at revision 1, and "carry on after
 * revision 1" would mean "skip all of it". The triple names a row, and reading resumes strictly after it.
 *
 * Encoded as text because it is opaque to the client: it holds it and hands it back, and nothing else.
 */
data class DeltaCursor(val rev: Long, val tbl: String, val id: String) : Comparable<DeltaCursor> {
    fun encode(): String = "$rev|$tbl|$id"

    override fun compareTo(other: DeltaCursor): Int = when {
        rev != other.rev -> rev.compareTo(other.rev)
        tbl != other.tbl -> tbl.compareTo(other.tbl)
        else -> id.compareTo(other.id)
    }

    companion object {
        /** Split on the first two bars only — a `usage` row's id is a URL, and may hold one itself. */
        fun parse(raw: String): DeltaCursor? {
            val firstBar = raw.indexOf('|').takeIf { it > 0 } ?: return null
            val secondBar = raw.indexOf('|', firstBar + 1).takeIf { it > 0 } ?: return null
            val rev = raw.substring(0, firstBar).toLongOrNull() ?: return null
            return DeltaCursor(rev, raw.substring(firstBar + 1, secondBar), raw.substring(secondBar + 1))
        }

        fun of(row: SyncRow): DeltaCursor = DeltaCursor(row.rev, row.tbl, row.id)
    }
}

/** One page of a delta: what to send, whether there is more, and where to carry on from. */
data class DeltaPage(val rows: List<SyncRow>, val hasMore: Boolean, val nextCursor: String?)

/**
 * Cut [ordered] — already in (rev, tbl, id) order and already past the cursor — into a page of [limit].
 *
 * The caller passes one row more than the limit if it has one; that extra row is what says there is
 * more, and it is not sent.
 */
fun deltaPageOf(ordered: List<SyncRow>, limit: Int): DeltaPage {
    val hasMore = ordered.size > limit
    val rows = ordered.take(limit)
    return DeltaPage(rows, hasMore, if (hasMore) rows.lastOrNull()?.let { DeltaCursor.of(it).encode() } else null)
}

/**
 * Whether a row of the delta is one the asking device already has because it just sent it.
 *
 * On the first page that is exactly what the server accepted from this push. On the pages after it —
 * which carry no push of their own — it is what this device wrote under this revision, the push page
 * one took. (If another device has pushed in between, `newRev` has moved on and nothing is dropped: the
 * rows come down again and are written over themselves, which costs a little and breaks nothing.)
 */
fun isEchoOfThisPush(
    row: ServerRow,
    newRev: Long,
    deviceId: String,
    accepted: Set<RowKey>,
    continuation: Boolean,
): Boolean = if (continuation) {
    row.row.rev == newRev && row.deviceId == deviceId
} else {
    row.row.rev == newRev && RowKey(row.row.tbl, row.row.id) in accepted
}

// ---- the little the payload of a counter is read for -------------------------------------------------

private fun JsonObject.longField(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

private fun JsonObject.textField(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.withFields(vararg fields: Pair<String, String?>): JsonObject =
    JsonObject(toMutableMap().apply { fields.forEach { (k, v) -> if (v != null) put(k, JsonPrimitive(v)) } })
