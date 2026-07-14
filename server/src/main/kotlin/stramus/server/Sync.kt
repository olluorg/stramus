@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.SuspendScope
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.gt
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

/** At most this many rows in one delta; the rest come on the next call, with `hasMore` set. */
private const val DELTA_LIMIT = 500

/**
 * A device whose clock is this far ahead of the server's is not believed: its `updatedAt` is clamped to
 * the server's own time.
 *
 * Without this, one machine with a calendar set to 2031 would win every conflict it ever takes part in,
 * for years, and nothing the other devices did would ever stick. Clamping costs a correctly-set clock
 * nothing at all.
 */
private val MAX_CLOCK_SKEW = 5.minutes

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
class SyncService(private val db: SuspendDatabase<ServerDb>) {

    suspend fun sync(userId: Uuid, deviceId: Uuid, since: Long, pushed: List<SyncRow>): SyncResponse {
        val now = Clock.System.now()

        return db.suspendTransaction {
            val currentRev = UserSeq.findOne { where { UserSeq.userId eq userId } }?.rev ?: 0L

            val accepted = mutableListOf<RowKey>()
            val conflicts = mutableListOf<SyncConflict>()

            // The revision this push writes under. Taken even if nothing is written — a wasted number
            // costs nothing, and threading "did anything change?" through the loop below to avoid it
            // would cost a good deal more.
            val newRev = if (pushed.isEmpty()) currentRev else currentRev + 1

            for (row in pushed) {
                val incoming = row.clamped(now)
                val existing = SyncRows.findOne {
                    where { (SyncRows.userId eq userId) and (SyncRows.tbl eq row.tbl) and (SyncRows.id eq row.id) }
                }

                if (existing == null) {
                    insert(userId, deviceId, incoming, newRev)
                    accepted += RowKey(row.tbl, row.id)
                    continue
                }

                if (row.tbl in COUNTER_TABLES) {
                    // A tally, not a value: take the larger of each field rather than the later row.
                    val merged = mergeCounters(existing, incoming)
                    write(userId, deviceId, merged, newRev)
                    accepted += RowKey(row.tbl, row.id)
                    continue
                }

                val changedOnServer = existing.rev > since
                if (changedOnServer) {
                    // Both sides moved. Hand the server's version back whichever way this goes: the
                    // client is the only one that knows a note from a bookmark, and the only one that
                    // can keep the loser as a copy.
                    conflicts += SyncConflict(row.tbl, row.id, existing.toProtocol())
                }

                if (incoming.wins(existing, deviceId)) {
                    write(userId, deviceId, incoming, newRev)
                    accepted += RowKey(row.tbl, row.id)
                }
                // Otherwise the server's version stands. It is not written, so it keeps its own revision
                // — which is above `since`, so the delta below carries it to the client unasked.
            }

            if (pushed.isNotEmpty()) {
                UserSeq.deleteWhere { where { UserSeq.userId eq userId } }
                UserSeq.insert(UserSeqRow().apply { this.userId = userId; rev = newRev })
            }

            // Everything the device has not seen — minus what it just sent us, which it already has.
            val delta = SyncRows.find {
                where { (SyncRows.userId eq userId) and (SyncRows.rev gt since) }
                orderBy ASC SyncRows.rev
                limit = DELTA_LIMIT + 1
            }.filterNot { it.rev == newRev && RowKey(it.tbl, it.id) in accepted }

            val hasMore = delta.size > DELTA_LIMIT

            SyncResponse(
                rev = newRev,
                accepted = accepted,
                rows = delta.take(DELTA_LIMIT).map { it.toProtocol() },
                conflicts = conflicts,
                hasMore = hasMore,
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

/** Later write wins; an exact tie goes to the larger device id, so both machines decide it the same way. */
private fun SyncRow.wins(server: SyncRowEntity, deviceId: Uuid): Boolean {
    val mine = Instant.parse(updatedAt)
    return when {
        mine > server.updatedAt -> true
        mine < server.updatedAt -> false
        else -> deviceId.toString() > server.deviceId.toString()
    }
}

/** A row from a device whose clock is far ahead is stamped with the server's time instead. */
private fun SyncRow.clamped(now: Instant): SyncRow {
    val stated = Instant.parse(updatedAt)
    return if (stated > now + MAX_CLOCK_SKEW) copy(updatedAt = now.toString()) else this
}

/**
 * The merge for a counter: the larger tally, the later use. Neither device's openings are thrown away,
 * which is what taking the whole row of whoever wrote last would do.
 *
 * It is not a true distributed counter — two devices that each count five openings while apart end up
 * with five, not ten. Making that exact means a per-device tally and a table many times the size, to
 * sharpen a number whose only job is to sort a search box.
 */
private fun mergeCounters(server: SyncRowEntity, incoming: SyncRow): SyncRow {
    val serverPayload = server.payload?.let { json.parseToJsonElement(it) as? JsonObject }
    val incomingPayload = incoming.payload
    if (serverPayload == null || incomingPayload == null) return incoming

    val hits = maxOf(serverPayload.long("hits"), incomingPayload.long("hits"))
    val serverUsed = serverPayload.text("lastUsedAt")
    val incomingUsed = incomingPayload.text("lastUsedAt")
    val lastUsedAt = listOfNotNull(serverUsed, incomingUsed).maxOrNull()

    // The rest of the fields (a page's title, its host) come from whichever row is the later one: they
    // are values, not tallies, and there is nothing to add up.
    val later = if (Instant.parse(incoming.updatedAt) >= server.updatedAt) incomingPayload else serverPayload

    val merged = buildMap {
        putAll(later)
        put("hits", JsonPrimitive(hits))
        lastUsedAt?.let { put("lastUsedAt", JsonPrimitive(it)) }
    }
    return incoming.copy(payload = JsonObject(merged))
}

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
