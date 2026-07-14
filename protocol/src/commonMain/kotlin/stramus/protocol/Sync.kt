package stramus.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The synchronisation protocol: a cursor, a delta, and tombstones — the shape Google Drive's
 * `changes.list` has, for the same reasons.
 *
 * A client holds a [SyncRequest.since] cursor, sends the rows it changed, and gets back everything that
 * happened to the account since that cursor, deletions included. It applies the answer, keeps the new
 * [SyncResponse.rev], and that is the whole loop.
 *
 * ## Why the server does not know what a card is
 *
 * A row travels as an opaque [SyncRow.payload] — a JSON object the client wrote and the client reads.
 * The server orders rows, decides which of two versions wins, and hands them on; it never looks inside
 * one, because it has nothing to do inside one: search, sort and rendering all live in the browser.
 *
 * The payoff is that the client's schema can change — a new column on a card — without a server deploy,
 * and an old client and a new one can sync with each other through a server that understands neither.
 * The cost is that a future server-side feature (sharing a collection, say) would have to teach the
 * server what a card is; that is a migration of the payload into typed tables when it comes, and it is
 * cheaper than paying for it before it does.
 *
 * The one exception is [COUNTER_TABLES] — see [SyncRow].
 */

/** Tables whose rows are counters, and so merge by maximum rather than by last write. See [SyncRow]. */
val COUNTER_TABLES: Set<String> = setOf("usage", "action_usage")

/**
 * One row, on its way in either direction.
 *
 * [updatedAt] is when the client last wrote it, and it is what a conflict is decided by (the later write
 * wins; an exact tie is broken by the device id, which is arbitrary but the same on both machines). The
 * server clamps a wildly future [updatedAt] to its own clock, so a device with a broken calendar cannot
 * win every conflict for ever.
 *
 * [deletedAt] makes the row a tombstone: the row is gone, and the fact that it went is itself a thing
 * that has to travel. Without it, the other device sees a row it has and the server does not, and
 * helpfully puts it back.
 *
 * [payload] is the row's own columns, as the client's `SyncCodec` writes them. Null on a tombstone —
 * there is nothing left to carry.
 *
 * [rev] is the server's cursor value for this row. The client sends 0 and ignores it; the server fills
 * it in on the way back, and the client remembers it as the row's base revision.
 *
 * **Counters merge differently.** A row of `usage` or `action_usage` (see [COUNTER_TABLES]) is not a
 * value but a tally, and last-write-wins would throw away the openings counted on the other device.
 * These merge field by field, taking the larger `hits` and the later `lastUsedAt`. It is the one place
 * the server looks inside a payload, and it is worth the exception: the alternative is a counter that
 * silently loses half of what it counted.
 */
@Serializable
data class SyncRow(
    val tbl: String,
    val id: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val payload: JsonObject? = null,
    val rev: Long = 0,
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    /** The cursor this device has read up to. 0 asks for everything the account holds. */
    val since: Long,
    /** What changed here since the last sync — new rows, edited rows, tombstones. */
    val rows: List<SyncRow> = emptyList(),
)

/** A row that changed on both sides since [SyncRequest.since]: [server] is what the server held. */
@Serializable
data class SyncConflict(
    val tbl: String,
    val id: String,
    /**
     * The server's version *before* this merge. Whether it won or lost, the client is handed it: the
     * losing version of a note is not thrown away but kept as a copy beside the winner, and the client
     * cannot do that without seeing both.
     */
    val server: SyncRow,
)

/** Names a row without carrying it. */
@Serializable
data class RowKey(val tbl: String, val id: String)

@Serializable
data class SyncResponse(
    /** The new cursor. The client stores this and sends it as `since` next time. */
    val rev: Long,
    /** The pushed rows the server took as they were. They are not echoed back in [rows]. */
    val accepted: List<RowKey> = emptyList(),
    /** Everything that changed on the account since `since`, tombstones included. */
    val rows: List<SyncRow> = emptyList(),
    /** Rows that changed on both sides. The client resolves the ones it cares about; see [SyncConflict]. */
    val conflicts: List<SyncConflict> = emptyList(),
    /** The delta was cut short. Sync again straight away with the new [rev] to get the rest. */
    val hasMore: Boolean = false,
)
