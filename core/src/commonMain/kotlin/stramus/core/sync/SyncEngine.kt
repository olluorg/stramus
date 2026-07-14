@file:OptIn(ExperimentalUuidApi::class, DelicateKormiumApi::class)

package stramus.core.sync

import io.github.kormium.DelicateKormiumApi
import io.github.kormium.SuspendScope
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.isNotNull
import io.github.kormium.isNull
import io.github.kormium.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import stramus.core.db.CardBlobRow
import stramus.core.db.CardBlobs
import stramus.core.db.CardRow
import stramus.core.db.CardSections
import stramus.core.db.Cards
import stramus.core.db.Collections
import stramus.core.db.Sections
import stramus.core.db.StramusDb
import stramus.core.db.SyncMeta
import stramus.core.db.SyncMetaRow
import stramus.core.db.SyncState
import stramus.core.db.SyncStateRow
import stramus.core.order.OrderKey
import stramus.protocol.COUNTER_TABLES
import stramus.protocol.RowKey
import stramus.protocol.SyncRequest
import stramus.protocol.SyncResponse
import stramus.protocol.SyncRow

/** What the engine talks to. The app hands it HTTP; a test hands it the server, in process. */
fun interface SyncApi {
    suspend fun sync(request: SyncRequest): SyncResponse
}

/**
 * The files, which do not travel in the delta.
 *
 * Addressed by the hash of their bytes: [missing] is the only question worth asking before an upload, and
 * it makes saving the same file twice — or moving a card, or renaming it — cost nothing at all.
 */
interface BlobApi {
    /** Which of [shas] the server has not got. */
    suspend fun missing(shas: List<String>): List<String>
    suspend fun upload(sha: String, bytes: ByteArray)
    /** The bytes, or null if the server has not got them (a device that has not uploaded them yet). */
    suspend fun download(sha: String): ByteArray?
}

/** The keys of [SyncState]. */
private const val KEY_USER = "userId"
private const val KEY_DEVICE = "deviceId"
private const val KEY_REV = "lastRev"

/** What one run of the engine did, for the indicator in the corner of the screen. */
data class SyncResult(
    val pushed: Int,
    val applied: Int,
    /** Notes that were edited on two devices at once, and so are now two notes. */
    val conflictCopies: Int,
    val rev: Long,
)

/**
 * The client half of synchronisation.
 *
 * Nothing here is on the path of anything the user does. A card is saved by writing it to the local
 * database, and that is the whole of saving a card; this runs afterwards, in the background, and if it
 * cannot — no network, no account, server on fire — the app carries on exactly as it did before there
 * was a server at all. That is the one property the design is not allowed to trade away, and it is why
 * the engine only ever *reads* what the app wrote.
 *
 * One run is: work out what changed here, send it, apply what came back, write down the new cursor. The
 * whole of the applying happens in a single local transaction — a run that dies halfway must not leave a
 * database whose cursor says it has seen rows it has not.
 */
class SyncEngine(
    private val db: SuspendDatabase<StramusDb>,
    private val api: SyncApi,
    /** Null in a test that has no interest in files; the app always has one. */
    private val blobs: BlobApi? = null,
    /**
     * Whether the browsing statistics go up with everything else. Asked on every run rather than held,
     * because the user can turn it off between two of them — and when they do, it must stop at once.
     */
    private val syncUsage: () -> Boolean = { false },
) {

    /**
     * Sign this database in to an account. Whatever is already in it is not the account's yet, so all of
     * it counts as new: no base versions are left behind, and the first run pushes the lot.
     *
     * [discardLocal] is for the second device. A fresh install seeds itself a section, a collection and a
     * note saying how the app works (see `StoreSeed`) — and if that device then signs in to an account
     * that already has years of collections, merging would hand the user a second "Main" and a second
     * copy of the welcome note, for ever. Joining an existing account is the one time it is right to throw
     * local data away, and the one time the user has nothing to lose by it. The app asks; this obeys.
     */
    suspend fun signIn(userId: Uuid, deviceId: Uuid, discardLocal: Boolean = false) {
        db.suspendTransaction {
            if (discardLocal) {
                Cards.deleteWhere { }
                CardBlobs.deleteWhere { }
                CardSections.deleteWhere { }
                Collections.deleteWhere { }
                Sections.deleteWhere { }
            }
            putState(KEY_USER, userId.toString())
            putState(KEY_DEVICE, deviceId.toString())
            putState(KEY_REV, "0")
            SyncMeta.deleteWhere { }
        }
    }

    /** Forget the account. The data stays: it was the user's before there was an account, and still is. */
    suspend fun signOut() {
        db.suspendTransaction {
            SyncState.deleteWhere { }
            SyncMeta.deleteWhere { }
        }
    }

    suspend fun signedIn(): Boolean = db.suspendTransaction { getState(KEY_USER) != null }

    /**
     * Ask the server for everything again on the next run.
     *
     * What turning the statistics option *on* needs: while it was off, those rows were coming down in the
     * delta and being dropped, and the cursor moved past them. Nothing but a fresh read of the whole account
     * will bring them back — and since the base versions are kept, this costs a download and pushes nothing.
     */
    suspend fun refetchEverything() {
        db.suspendTransaction { putState(KEY_REV, "0") }
    }

    /**
     * One round trip. Returns null if there is no account — in which case this is not an error and not a
     * failure, it is the app doing what it has always done.
     *
     * A caller that gets [SyncResult] back with `hasMore` behind it does not need to know: this loops
     * until the server has nothing left, so one call means "in step".
     */
    suspend fun syncNow(): SyncResult? {
        val deviceId = db.suspendTransaction { getState(KEY_DEVICE) }?.let { Uuid.parse(it) } ?: return null

        var pushed = 0
        var applied = 0
        var copies = 0
        var rev: Long

        while (true) {
            // Read outside the write transaction: this reads every row of every synced table, and holding
            // a write lock across a network call would be a way to freeze the app on a slow connection.
            val withUsage = syncUsage()
            val local = db.suspendTransaction { readAllForSync(withUsage) }
            val bases = db.suspendTransaction {
                SyncMeta.all().associate { RowKey(it.tbl, it.rowId) to it }
            }
            val since = db.suspendTransaction { getState(KEY_REV) }?.toLongOrNull() ?: 0L

            // "Changed here" is exactly "no longer what the server confirmed". A row with no base at all
            // is new; a row whose hash matches its base has not been touched since it last went up.
            val changed = local.filter { bases[RowKey(it.row.tbl, it.row.id)]?.hash != it.hash }

            val response = api.sync(SyncRequest(deviceId.toString(), since, changed.map { it.row }))

            val localByKey = local.associateBy { RowKey(it.row.tbl, it.row.id) }
            val conflictCopies = mutableListOf<CardRow>()

            db.suspendTransaction {
                // What the server took, it took as we sent it: that version is now the base.
                response.accepted.forEach { key ->
                    val row = localByKey[key] ?: return@forEach
                    putBase(key, row.hash, response.rev)
                }

                // A note edited on two devices at once would otherwise lose a paragraph of someone's
                // writing. The loser is not thrown away — it becomes a second card beside the winner, and
                // the user decides. Everything else (a title, a place in the grid) merges by last write.
                response.conflicts.forEach { conflict ->
                    val mine = localByKey[RowKey(conflict.tbl, conflict.id)]?.row
                    val theirs = conflict.server
                    val iWon = RowKey(conflict.tbl, conflict.id) in response.accepted
                    val loser = if (iWon) theirs else mine
                    val winner = if (iWon) mine else theirs
                    if (conflict.tbl == "cards" && loser != null && isNote(loser) && text(loser, "content") != text(winner, "content")) {
                        conflictCopies += copyOf(loser)
                    }
                }

                response.rows.forEach { row ->
                    // Statistics the user asked us not to sync are not written down here either. They came
                    // from their own other device, but the option means "this stays on the machine it is
                    // on", and applying them would quietly undo that.
                    if (!withUsage && row.tbl in COUNTER_TABLES) return@forEach
                    applyRemote(row)
                    putBase(RowKey(row.tbl, row.id), hashOf(row), row.rev)
                }

                conflictCopies.forEach { Cards.insert(it) }

                putState(KEY_REV, response.rev.toString())
            }

            // The bytes, after the rows: a card arrives first and its file follows, so a grid that redraws
            // in between shows a file card with its preview and no bytes — which is what it shows anyway
            // until the user opens it.
            reconcileBlobs()

            pushed += response.accepted.size
            applied += response.rows.size
            copies += conflictCopies.size
            rev = response.rev

            if (!response.hasMore) return SyncResult(pushed, applied, copies, rev)
        }
    }

    /**
     * Send up the files this device has and the server has not; fetch down the files a card names and this
     * device has not got.
     *
     * Nothing here is allowed to fail a sync. A file past the account's quota, a network that dies halfway
     * through a 9 MB upload — the rows are already in step, and the bytes are tried again on the next run.
     * A card is not damaged by its file being late: it draws its preview and says its name, and opening it
     * is the only thing that needs the bytes at all.
     */
    private suspend fun reconcileBlobs() {
        val blobs = blobs ?: return

        val cards = db.suspendTransaction {
            Cards.find { where { Cards.blobSha.isNotNull() and Cards.deletedAt.isNull() } }
        }
        if (cards.isEmpty()) return

        val held = mutableMapOf<String, String>() // sha -> the `data:` URI this device holds for it
        val wanted = mutableListOf<CardRow>() // cards whose bytes are somewhere else

        db.suspendTransaction {
            cards.forEach { card ->
                val sha = card.blobSha ?: return@forEach
                val local = CardBlobs.findOne { where { CardBlobs.cardId eq card.id } }
                if (local != null) {
                    if (sha !in held) held[sha] = local.data
                } else {
                    wanted += card
                }
            }
        }

        if (held.isNotEmpty()) {
            // One question for the lot: which of these have you not got? Everything the server already holds
            // — every file that was uploaded once, on any device — costs nothing to "sync" ever again.
            runCatching { blobs.missing(held.keys.toList()) }.getOrNull()?.forEach { sha ->
                val bytes = held[sha]?.let { DataUri.bytesOf(it) } ?: return@forEach
                runCatching { blobs.upload(sha, bytes) }
            }
        }

        wanted.forEach { card ->
            val sha = card.blobSha ?: return@forEach
            val bytes = runCatching { blobs.download(sha) }.getOrNull() ?: return@forEach
            db.suspendTransaction {
                CardBlobs.deleteWhere { where { CardBlobs.cardId eq card.id } }
                CardBlobs.insert(
                    CardBlobRow().apply {
                        cardId = card.id
                        data = DataUri.of(bytes, card.mime)
                    },
                )
            }
        }
    }
}

private fun isNote(row: SyncRow): Boolean = text(row, "kind") == "note"

private fun text(row: SyncRow?, key: String): String? = (row?.payload as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

/**
 * The losing version of a note, as a card of its own: same collection, same group, marked in its title so
 * the user can see which is which. It is an ordinary new card — it will be pushed on the next run like
 * anything else the user made.
 */
private fun copyOf(loser: SyncRow): CardRow {
    val payload = loser.payload ?: JsonObject(emptyMap())
    fun str(key: String): String? = payload[key]?.jsonPrimitive?.contentOrNull

    return CardRow().apply {
        id = Uuid.random()
        collectionId = Uuid.parse(str("collectionId")!!)
        cardSectionId = str("cardSectionId")?.let { Uuid.parse(it) }
        kind = "note"
        title = "${str("title") ?: ""} (conflicting copy)"
        url = ""
        favicon = null
        content = str("content")
        thumb = null
        mime = null
        // At the end of its group. Where exactly it lands matters less than that the user sees it is
        // there, next to the note it is a copy of.
        orderKey = OrderKey.between(str("orderKey"), null)
        createdAt = Clock.System.now()
        updatedAt = Clock.System.now()
        deletedAt = null
    }
}

private suspend fun SuspendScope<StramusDb>.putBase(key: RowKey, hash: String, rev: Long) {
    SyncMeta.deleteWhere { where { (SyncMeta.tbl eq key.tbl) and (SyncMeta.rowId eq key.id) } }
    SyncMeta.insert(
        SyncMetaRow().apply {
            tbl = key.tbl
            rowId = key.id
            this.hash = hash
            this.rev = rev
        },
    )
}

private suspend fun SuspendScope<StramusDb>.getState(key: String): String? =
    SyncState.findOne { where { SyncState.k eq key } }?.v

private suspend fun SuspendScope<StramusDb>.putState(key: String, value: String) {
    SyncState.deleteWhere { where { SyncState.k eq key } }
    SyncState.insert(SyncStateRow().apply { k = key; v = value })
}
