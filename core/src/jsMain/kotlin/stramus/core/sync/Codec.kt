@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.ReadScope
import io.github.kidx.WriteScope
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import stramus.core.crypto.sha256Hex
import stramus.core.db.ActionUsage
import stramus.core.db.ActionUsageRow
import stramus.core.db.CardRow
import stramus.core.db.CardSectionRow
import stramus.core.db.CardSections
import stramus.core.db.Cards
import stramus.core.db.CollectionRow
import stramus.core.db.Collections
import stramus.core.db.SectionRow
import stramus.core.db.Sections
import stramus.core.db.Usage
import stramus.core.db.UsageRow
import stramus.protocol.SyncRow

/**
 * The tables that travel, and the order they are applied in.
 *
 * Parents before children: a card whose collection has not arrived yet would be a card in a collection
 * that does not exist. Nothing enforces that in the database — there are no foreign keys here — but the
 * app would draw it nowhere, so the order is kept.
 *
 * `favicons` is deliberately absent. It is a cache: the second device fetches the icons itself in a few
 * seconds, and they are the biggest thing in the database by some way. `card_blobs` is absent too, but
 * for a different reason — the bytes of a file do not belong in a JSON delta at all, and they get an
 * endpoint of their own.
 */
val SYNCED_TABLES: List<String> = listOf("sections", "collections", "card_sections", "cards", "usage", "action_usage")

/** A local row, as the protocol sees it, together with what it hashes to. */
class LocalRow(val row: SyncRow, val hash: String)

/**
 * The canonical form a row is hashed by — its fields, in a fixed order, as one string.
 *
 * This is what the base version in `sync_meta` is: not a copy of the row, but the hash of this. The
 * question it answers is the only one ever asked of a base — "is this row still what the server
 * confirmed?" — and it answers it without keeping every note and every preview twice over.
 *
 * The order has to be stable. Two clients that hash the same row differently would each think the other
 * had changed it, and push it back and forth for ever.
 */
suspend fun hashOf(row: SyncRow): String {
    val payload = row.payload.orEmpty()
    val fields = payload.keys.sorted().joinToString(" ") { key ->
        "$key=${payload[key]?.jsonPrimitive?.contentOrNull ?: "null"}"
    }
    return sha256Hex("${row.tbl} ${row.id} ${row.deletedAt ?: ""} $fields")
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun obj(vararg pairs: Pair<String, String?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to (v?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)) })

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int = str(key)?.toIntOrNull() ?: 0
private fun JsonObject.uuid(key: String): Uuid = Uuid.parse(str(key)!!)
private fun JsonObject.uuidOrNull(key: String): Uuid? = str(key)?.let { Uuid.parse(it) }
private fun JsonObject.instant(key: String): Instant = Instant.parse(str(key)!!)

/**
 * Every row of every synced table, tombstones included — what a push is chosen from.
 *
 * [includeUsage] is the user's answer to a question the rest of the data does not raise. `usage` and
 * `action_usage` are a record of which pages a person opens and how often, which is to say: their browsing
 * history. Collections are things they chose to keep; this is a trace of what they did. It is off unless
 * they say otherwise, and when it is off it does not leave the machine.
 *
 * Hashing is deliberately not done here: [hashOf] awaits the platform's real SHA-256 (`crypto.subtle`),
 * and awaiting anything but a kidx call inside a scope risks the IndexedDB transaction auto-committing
 * out from under the rest of the block (kidx's own docs call this out — "do not suspend on anything else
 * inside a scope"). This only collects the rows; [hashOf] runs afterwards, once the scope has closed.
 */
suspend fun ReadScope.readRowsForSync(includeUsage: Boolean): List<SyncRow> = buildList {
    Sections.all().forEach { add(it.toSyncRow()) }
    Collections.all().forEach { add(it.toSyncRow()) }
    CardSections.all().forEach { add(it.toSyncRow()) }
    Cards.all().forEach { add(it.toSyncRow()) }
    if (includeUsage) {
        Usage.all().forEach { add(it.toSyncRow()) }
        ActionUsage.all().forEach { add(it.toSyncRow()) }
    }
}

/** Pairs each row with [hashOf] it — called outside any scope, see [readRowsForSync]. */
suspend fun List<SyncRow>.withHashes(): List<LocalRow> = map { LocalRow(it, hashOf(it)) }

private fun SectionRow.toSyncRow() = SyncRow(
    tbl = "sections",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else obj(
        "title" to title,
        "orderKey" to orderKey,
        "deletable" to deletable.toString(),
        "collapsed" to collapsed.toString(),
        // The PIN travels. The server sees the salt and the hash, not the PIN — but it also sees every
        // card in the locked section, in plain text. The lock is on the screen, not on the data, and
        // the privacy policy says so rather than implying otherwise.
        "pinSalt" to pinSalt,
        "pinHash" to pinHash,
    ),
)

private fun CollectionRow.toSyncRow() = SyncRow(
    tbl = "collections",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else obj(
        "sectionId" to sectionId.toString(),
        "title" to title,
        "orderKey" to orderKey,
        "createdAt" to createdAt.toString(),
        "readOnly" to readOnly.toString(),
    ),
)

private fun CardSectionRow.toSyncRow() = SyncRow(
    tbl = "card_sections",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else obj(
        "collectionId" to collectionId.toString(),
        "title" to title,
        "description" to description,
        "orderKey" to orderKey,
        "collapsed" to collapsed.toString(),
    ),
)

private fun CardRow.toSyncRow() = SyncRow(
    tbl = "cards",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else obj(
        "collectionId" to collectionId.toString(),
        "cardSectionId" to cardSectionId?.toString(),
        "kind" to kind,
        "title" to title,
        "url" to url,
        "favicon" to favicon,
        "content" to content,
        // The preview of a file, but not the file: a thumbnail is a few kilobytes and the grid is blank
        // without it, where the bytes themselves have no upper bound and belong in their own endpoint.
        "thumb" to thumb,
        "mime" to mime,
        // The name of the file, not the file. A device that gets this card and has not got the bytes knows
        // exactly what to go and ask for.
        "blobSha" to blobSha,
        "orderKey" to orderKey,
        "createdAt" to createdAt.toString(),
    ),
)

private fun UsageRow.toSyncRow() = SyncRow(
    tbl = "usage",
    id = url,
    // A counter has no `updatedAt` of its own — when it was last used *is* when it last changed. A page the
    // user asked us to forget is dated by the moment they asked, which is what lets the server tell a
    // forgetting from a visit that happened before it.
    updatedAt = (deletedAt ?: lastUsedAt).toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) {
        null
    } else {
        obj("url" to url, "title" to title, "host" to host, "hits" to hits.toString(), "lastUsedAt" to lastUsedAt.toString())
    },
)

private fun ActionUsageRow.toSyncRow() = SyncRow(
    tbl = "action_usage",
    id = kind,
    updatedAt = lastUsedAt.toString(),
    payload = obj("kind" to kind, "hits" to hits.toString(), "lastUsedAt" to lastUsedAt.toString()),
)

/**
 * Write a row the server sent into the local database — an insert, an update, or a tombstone.
 *
 * `put` rather than a patch: the payload is the whole row, so there is nothing to merge here. The merge
 * already happened, on the server or (for a conflict) in the engine; this only writes down the answer,
 * and `put` — an unconditional whole-record overwrite — is exactly "write down the answer" whether the
 * row existed locally before or not.
 */
suspend fun WriteScope.applyRemote(row: SyncRow) {
    when (row.tbl) {
        "sections" -> {
            val p = row.payload
            Sections.put(
                SectionRow().apply {
                    id = Uuid.parse(row.id)
                    updatedAt = Instant.parse(row.updatedAt)
                    deletedAt = row.deletedAt?.let { Instant.parse(it) }
                    title = p?.str("title") ?: ""
                    orderKey = p?.str("orderKey") ?: ""
                    deletable = p?.int("deletable") ?: 1
                    collapsed = p?.int("collapsed") ?: 0
                    pinSalt = p?.str("pinSalt")
                    pinHash = p?.str("pinHash")
                },
            )
        }

        "collections" -> {
            val p = row.payload
            Collections.put(
                CollectionRow().apply {
                    id = Uuid.parse(row.id)
                    updatedAt = Instant.parse(row.updatedAt)
                    deletedAt = row.deletedAt?.let { Instant.parse(it) }
                    sectionId = p?.uuid("sectionId") ?: Uuid.NIL
                    title = p?.str("title") ?: ""
                    orderKey = p?.str("orderKey") ?: ""
                    createdAt = p?.instant("createdAt") ?: Instant.parse(row.updatedAt)
                    readOnly = p?.int("readOnly") ?: 0
                },
            )
        }

        "card_sections" -> {
            val p = row.payload
            CardSections.put(
                CardSectionRow().apply {
                    id = Uuid.parse(row.id)
                    updatedAt = Instant.parse(row.updatedAt)
                    deletedAt = row.deletedAt?.let { Instant.parse(it) }
                    collectionId = p?.uuid("collectionId") ?: Uuid.NIL
                    title = p?.str("title") ?: ""
                    description = p?.str("description")
                    orderKey = p?.str("orderKey") ?: ""
                    collapsed = p?.int("collapsed") ?: 0
                },
            )
        }

        "cards" -> {
            val p = row.payload
            Cards.put(
                CardRow().apply {
                    id = Uuid.parse(row.id)
                    updatedAt = Instant.parse(row.updatedAt)
                    deletedAt = row.deletedAt?.let { Instant.parse(it) }
                    collectionId = p?.uuid("collectionId") ?: Uuid.NIL
                    cardSectionId = p?.uuidOrNull("cardSectionId")
                    kind = p?.str("kind") ?: "link"
                    title = p?.str("title") ?: ""
                    url = p?.str("url") ?: ""
                    favicon = p?.str("favicon")
                    content = p?.str("content")
                    thumb = p?.str("thumb")
                    mime = p?.str("mime")
                    blobSha = p?.str("blobSha")
                    orderKey = p?.str("orderKey") ?: ""
                    createdAt = p?.instant("createdAt") ?: Instant.parse(row.updatedAt)
                },
            )
        }

        "usage" -> {
            val p = row.payload
            if (p == null) {
                // Forgotten on another device. The row is kept, dead: without it, this device would push
                // the page back up on its next run and the suggestion would come straight back.
                Usage.put(
                    UsageRow().apply {
                        url = row.id
                        title = row.id
                        host = row.id.substringBefore('/')
                        hits = 0
                        lastUsedAt = Instant.parse(row.updatedAt)
                        deletedAt = row.deletedAt?.let { Instant.parse(it) }
                    },
                )
            } else {
                Usage.put(
                    UsageRow().apply {
                        url = row.id
                        title = p.str("title") ?: row.id
                        host = p.str("host") ?: row.id.substringBefore('/')
                        hits = p.int("hits")
                        lastUsedAt = p.instant("lastUsedAt")
                        deletedAt = null
                    },
                )
            }
        }

        "action_usage" -> {
            val p = row.payload ?: return
            ActionUsage.put(
                ActionUsageRow().apply {
                    kind = row.id
                    hits = p.int("hits")
                    lastUsedAt = p.instant("lastUsedAt")
                },
            )
        }

        // A table this version does not know about. It is not an error: the row was written by a client
        // newer than this one, and dropping it on the floor is better than refusing to sync at all.
        else -> Unit
    }
}
