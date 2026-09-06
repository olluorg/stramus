@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.SuspendScope
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.gt
import io.github.kormium.or
import io.github.kormium.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import stramus.protocol.COUNTER_TABLES
import stramus.protocol.RowKey
import stramus.protocol.SyncConflict
import stramus.protocol.SyncResponse
import stramus.protocol.SyncRow
import stramus.protocol.mergeRow
import stramus.protocol.isEchoOfThisPush
import stramus.protocol.deltaPageOf
import stramus.protocol.ServerRow
import stramus.protocol.DeltaCursor

/**
 * A device whose clock is this far ahead of the server's is not believed: its `updatedAt` is clamped to
 * the server's own time.
 *
 * Without this, one machine with a calendar set to 2031 would win every conflict it ever takes part in,
 * for years, and nothing the other devices did would ever stick. Clamping costs a correctly-set clock
 * nothing at all.
 */
private val json = Json { ignoreUnknownKeys = true }

/**
 * The whole of synchronisation: take what a device changed, decide what won, hand back what it missed.
 *
 * ### The cursor
 *
 * Each account has a counter ([UserSeq]) that only goes up. One push bumps it once, and every row that
 * push wrote is stamped with the new value. A device that has read up to revision N asks for the rows
 * above N — that is the delta, tombstones and all.
 *
 * ### What counts as a conflict
 *
 * Not "both sides have the row" — that is the ordinary case. A conflict is: the row changed here *and*
 * it changed on the server since this device last looked, which is exactly `serverRow.rev > since`. If
 * the server has not touched it since, the device's version simply lands, and there was never anything
 * to resolve.
 *
 * This is the distinction the client's base version pays for, and without it a last-write-wins merge
 * quietly drops a version in the one case that was a real disagreement while believing it was in the
 * other. Where a conflict *is* real, the later write wins — and the losing version travels back to the
 * client anyway ([SyncConflict]), which keeps it as a copy when losing it would mean losing a paragraph
 * of someone's note.
 */
class SyncService(
    private val db: SuspendDatabase<ServerDb>,
    /** How many rows one page of a delta holds — [ServerConfig.deltaLimit]. */
    private val deltaLimit: Int = 500,
) {

    suspend fun sync(
        userId: Uuid,
        deviceId: Uuid,
        since: Long,
        pushed: List<SyncRow>,
        cursor: String? = null,
    ): SyncResponse {
        val now = Clock.System.now()
        val page = cursor?.let(DeltaCursor::parse)

        return db.suspendTransaction {
            val currentRev = UserSeq.findOne { where { UserSeq.userId eq userId } }?.rev ?: 0L

            val accepted = mutableListOf<RowKey>()
            val conflicts = mutableListOf<SyncConflict>()

            // The revision this push writes under. Taken even if nothing is written — a wasted number
            // costs nothing, and threading "did anything change?" through the loop below to avoid it
            // would cost a good deal more.
            val newRev = if (pushed.isEmpty()) currentRev else currentRev + 1

            for (row in pushed) {
                val existing = SyncRows.findOne {
                    where { (SyncRows.userId eq userId) and (SyncRows.tbl eq row.tbl) and (SyncRows.id eq row.id) }
                }

                // What to do about it is not decided here. Every choice — what beats what, what counts
                // as a conflict, how a counter adds up — lives in `protocol`'s [mergeRow], where the
                // client's own tests can run the same code rather than a stand-in written to agree
                // with it. This function's job is the two things a pure function cannot do: find the
                // row, and write the answer down.
                val verdict = mergeRow(
                    incoming = row,
                    existing = existing?.let { ServerRow(it.toProtocol(), it.deviceId.toString()) },
                    since = since,
                    deviceId = deviceId.toString(),
                    now = now,
                )
                verdict.conflict?.let { conflicts += SyncConflict(row.tbl, row.id, it) }
                verdict.write?.let { winner ->
                    if (existing == null) insert(userId, deviceId, winner, newRev)
                    else write(userId, deviceId, winner, newRev)
                    accepted += RowKey(row.tbl, row.id)
                }
                // Otherwise the server's version stands. It is not written, so it keeps its own revision
                // — which is above `since`, so the delta below carries it to the client unasked.
            }

            if (pushed.isNotEmpty()) {
                UserSeq.deleteWhere { where { UserSeq.userId eq userId } }
                UserSeq.insert(UserSeqRow().apply { this.userId = userId; rev = newRev })
            }

            // Everything the device has not seen — minus what it just wrote itself, which it has.
            //
            // Ordered by (rev, tbl, id) rather than by rev alone: a revision holds as many rows as the
            // push that made it, so a page can end in the middle of one, and the place it ended has to be
            // nameable. That triple is [DeltaCursor], and it is what a device comes back with.
            val delta = SyncRows.find {
                where {
                    val mine = (SyncRows.userId eq userId) and (SyncRows.rev gt since)
                    if (page == null) mine else mine and after(page)
                }
                orderBy ASC SyncRows.rev
                orderBy ASC SyncRows.tbl
                orderBy ASC SyncRows.id
                limit = deltaLimit + 1
            }.filterNot {
                isEchoOfThisPush(
                    row = ServerRow(it.toProtocol(), it.deviceId.toString()),
                    newRev = newRev,
                    deviceId = deviceId.toString(),
                    accepted = accepted.toSet(),
                    continuation = page != null,
                )
            }

            // Where the page stops is `protocol`'s to say as well: the ordering is this query's, but the
            // cut and the cursor that names it are the contract, and the client is written against them.
            val cut = deltaPageOf(delta.map { it.toProtocol() }, deltaLimit)

            SyncResponse(
                rev = newRev,
                accepted = accepted,
                rows = cut.rows,
                conflicts = conflicts,
                hasMore = cut.hasMore,
                nextCursor = cut.nextCursor,
            )
        }
    }

    /** Everything the server holds for this user, for the export a person is entitled to ask for. */
    suspend fun exportAll(userId: Uuid): List<SyncRow> = db.suspendTransaction {
        SyncRows.find {
            where { SyncRows.userId eq userId }
            orderBy ASC SyncRows.rev
        }.map { it.toProtocol() }
    }

    private suspend fun SuspendScope<ServerDb>.insert(userId: Uuid, deviceId: Uuid, row: SyncRow, rev: Long) {
        SyncRows.insert(
            SyncRowEntity().apply {
                this.userId = userId
                tbl = row.tbl
                id = row.id
                this.rev = rev
                updatedAt = Instant.parse(row.updatedAt)
                deletedAt = row.deletedAt?.let { Instant.parse(it) }
                this.deviceId = deviceId
                payload = row.payload?.toString()
            },
        )
    }

    private suspend fun SuspendScope<ServerDb>.write(userId: Uuid, deviceId: Uuid, row: SyncRow, rev: Long) {
        SyncRows.update(
            SyncRowEntity().apply {
                this.rev = rev
                updatedAt = Instant.parse(row.updatedAt)
                deletedAt = row.deletedAt?.let { Instant.parse(it) }
                this.deviceId = deviceId
                payload = row.payload?.toString()
            },
        ) {
            where { (SyncRows.userId eq userId) and (SyncRows.tbl eq row.tbl) and (SyncRows.id eq row.id) }
        }
    }
}

/** The rows that sort after [cursor], in the (rev, tbl, id) order the delta is read in. */
private fun after(cursor: DeltaCursor) =
    (SyncRows.rev gt cursor.rev) or
        (
            (SyncRows.rev eq cursor.rev) and
                ((SyncRows.tbl gt cursor.tbl) or ((SyncRows.tbl eq cursor.tbl) and (SyncRows.id gt cursor.id)))
            )

private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

private fun SyncRowEntity.toProtocol(): SyncRow = SyncRow(
    tbl = tbl,
    id = id,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = payload?.let { json.parseToJsonElement(it) as? JsonObject },
    rev = rev,
)
