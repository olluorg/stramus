@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.Database
import io.github.kidx.ReadScope
import io.github.kidx.WriteScope
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import stramus.core.db.ActionUsage
import stramus.core.db.CardBlobRow
import stramus.core.db.CardBlobs
import stramus.core.db.CardRow
import stramus.core.db.CardSections
import stramus.core.db.Cards
import stramus.core.db.Collections
import stramus.core.db.Favicons
import stramus.core.db.Sections
import stramus.core.db.SyncMeta
import stramus.core.db.SyncMetaRow
import stramus.core.db.SyncState
import stramus.core.db.SyncStateRow
import stramus.core.db.Usage
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

/**
 * At most this many rows in one push.
 *
 * The delta has always been paged; a push was not, and the two are the same problem seen from opposite
 * ends. A browser joining an account with a few thousand saved links had every one of them declared new
 * and sent in a single request — megabytes of JSON, and whether that arrives depends on a body limit
 * somewhere between here and the database that nobody in this repository controls. Rejected, nothing
 * syncs at all, and the failure is the least informative kind: it is the first sync that fails, on the
 * account with the most in it.
 *
 * Sent in bites instead, and the loop below simply goes round again: rows the server took have a base
 * version afterwards, so the next pass picks up where this one stopped without having to remember it.
 */
private const val PUSH_LIMIT = 200

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
    private val db: Database,
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
        db.write(Cards, CardBlobs, CardSections, Collections, Sections, SyncState, SyncMeta) {
            if (discardLocal) {
                Cards.all().forEach { Cards.delete(it.id) }
                CardBlobs.all().forEach { CardBlobs.delete(it.cardId) }
                CardSections.all().forEach { CardSections.delete(it.id) }
                Collections.all().forEach { Collections.delete(it.id) }
                Sections.all().forEach { Sections.delete(it.id) }
            }
            putState(KEY_USER, userId.toString())
            putState(KEY_DEVICE, deviceId.toString())
            putState(KEY_REV, "0")
            SyncMeta.all().forEach { SyncMeta.delete(listOf(it.tbl, it.rowId)) }
        }
    }

    /** Forget the account. The data stays: it was the user's before there was an account, and still is. */
    suspend fun signOut() {
        db.write(SyncState, SyncMeta) {
            SyncState.all().forEach { SyncState.delete(it.k) }
            SyncMeta.all().forEach { SyncMeta.delete(listOf(it.tbl, it.rowId)) }
        }
    }

    /**
     * Empty this browser's database: the collections, the files, the counters, the cached icons, and the
     * bookkeeping that says whose account they were.
     *
     * Offered only alongside deleting the account, where "delete everything" would otherwise have meant
     * everything but the copy sitting in front of the user. Deliberately *not* a button of its own: the
     * browser already removes all of this by uninstalling the extension or clearing the site's data, and
     * it does so more thoroughly than a list here that a later table could fall off the end of.
     *
     * Unlike [signIn]'s `discardLocal` this also takes [Usage], [ActionUsage] and [Favicons] — the pages
     * you visit and the icons of the sites you saved are the most personal rows in the file, and leaving
     * them behind is exactly what someone asking for this does not want.
     *
     * One transaction: a half-emptied database is not a state this can end in.
     */
    suspend fun eraseLocalData() {
        db.write(Cards, CardBlobs, CardSections, Collections, Sections, Usage, ActionUsage, Favicons, SyncState, SyncMeta) {
            Cards.all().forEach { Cards.delete(it.id) }
            CardBlobs.all().forEach { CardBlobs.delete(it.cardId) }
            CardSections.all().forEach { CardSections.delete(it.id) }
            Collections.all().forEach { Collections.delete(it.id) }
            Sections.all().forEach { Sections.delete(it.id) }
            Usage.all().forEach { Usage.delete(it.url) }
            ActionUsage.all().forEach { ActionUsage.delete(it.kind) }
            Favicons.all().forEach { Favicons.delete(it.host) }
            SyncState.all().forEach { SyncState.delete(it.k) }
            SyncMeta.all().forEach { SyncMeta.delete(listOf(it.tbl, it.rowId)) }
        }
    }

    suspend fun signedIn(): Boolean = db.read(SyncState) { getState(KEY_USER) != null }

    /**
     * Ask the server for everything again on the next run.
     *
     * What turning the statistics option *on* needs: while it was off, those rows were coming down in the
     * delta and being dropped, and the cursor moved past them. Nothing but a fresh read of the whole account
     * will bring them back — and since the base versions are kept, this costs a download and pushes nothing.
     */
    suspend fun refetchEverything() {
        db.write(SyncState) { putState(KEY_REV, "0") }
    }

    /**
     * One round trip. Returns null if there is no account — in which case this is not an error and not a
     * failure, it is the app doing what it has always done.
     *
     * A caller that gets [SyncResult] back with `hasMore` behind it does not need to know: this loops
     * until the server has nothing left, so one call means "in step".
     */
    suspend fun syncNow(): SyncResult? {
        // One at a time. The timer fires every minute, the window-focus handler fires whenever the user
        // comes back, the account dialog has a button, and signing in runs one of its own — so two runs
        // overlapping is ordinary rather than exotic, and paging the push has made a run long enough for
        // it to happen routinely.
        //
        // Dropped rather than queued: a second run started while one is in flight would ask the same
        // question of the same rows and get the same answer a moment later. There is nothing it could
        // learn that the run already going is not about to. The caller sees null, which is what it
        // already means by "nothing to do" (see the account with no session).
        if (!running.tryLock()) return null
        try {
            return syncRun()
        } finally {
            running.unlock()
        }
    }

    private val running = Mutex()

    private suspend fun syncRun(): SyncResult? {
        val deviceId = db.read(SyncState) { getState(KEY_DEVICE) }?.let { Uuid.parse(it) } ?: return null

        var pushed = 0
        var applied = 0
        var copies = 0
        var rev: Long

        // Fixed while a delta is being paged: every page is an answer to the same question, and moving
        // it between two of them is exactly the mistake [cursor] and the write at the bottom of this loop
        // exist to prevent. It moves in one place only — when a delta has been read to the end and the
        // run goes back for another bite of the push, which is the one moment in a run when this device
        // really has seen everything up to that revision.
        var since = db.read(SyncState) { getState(KEY_REV) }?.toLongOrNull() ?: 0L

        /** Where the last page stopped, while there are more; null on the first, which is the one that pushes. */
        var cursor: String? = null

        while (true) {
            val withUsage = syncUsage()
            // Only the first page carries what changed here — the rest are pages of one answer. Reading
            // (and hashing) every row of the database for each of them would be work whose result is
            // known to be the same and would not be sent anyway.
            //
            // Read outside the write transaction: this reads every row of every synced table, and holding
            // a write lock across a network call would be a way to freeze the app on a slow connection.
            val changed = if (cursor != null) {
                emptyList()
            } else {
                val localRows = db.read(Sections, Collections, CardSections, Cards, Usage, ActionUsage) {
                    readRowsForSync(withUsage)
                }
                val local = localRows.withHashes()
                val bases = db.read(SyncMeta) {
                    SyncMeta.all().associate { RowKey(it.tbl, it.rowId) to it }
                }
                // "Changed here" is exactly "no longer what the server confirmed". A row with no base at
                // all is new; a row whose hash matches its base has not been touched since it last went up.
                local.filter { bases[RowKey(it.row.tbl, it.row.id)]?.hash != it.hash }
            }
            // A bite of them, not all: see [PUSH_LIMIT]. What is left over is not remembered anywhere —
            // the rows the server takes get a base version, so the next pass finds exactly the rest.
            val sending = changed.take(PUSH_LIMIT)
            val moreToPush = changed.size > sending.size
            val response = api.sync(SyncRequest(deviceId.toString(), since, sending.map { it.row }, cursor))

            // Only what went up can come back as accepted or as a conflict, so the rows this page sent
            // are the whole of what those two need to be looked up in.
            val localByKey = sending.associateBy { RowKey(it.row.tbl, it.row.id) }
            val conflictCopies = mutableListOf<CardRow>()
            // Computed here, outside any scope, for the same reason `local`'s hashes are: [hashOf] awaits
            // real SHA-256 and must not run inside `db.write`.
            val incomingHashes = response.rows.associate { RowKey(it.tbl, it.id) to hashOf(it) }

            db.write(Sections, Collections, CardSections, Cards, Usage, ActionUsage, SyncMeta, SyncState) {
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
                    putBase(RowKey(row.tbl, row.id), incomingHashes.getValue(RowKey(row.tbl, row.id)), row.rev)
                }

                conflictCopies.forEach { Cards.add(it) }

                // The cursor moves only when the delta is finished. `rev` is where the *account* has got
                // to, not where the reading of it has: written down while pages are still outstanding, it
                // carries this device past every row it has not been handed — silently, and for good,
                // since the next run then asks for changes after a revision it never actually read.
                // Only when this run has nothing left to do at all — neither a page of the delta to
                // read nor a bite of the push to send. `rev` says how far the *account* has got, and
                // writing it down while either is outstanding claims to have seen rows that are still
                // in hand.
                if (!response.hasMore && !moreToPush) putState(KEY_REV, response.rev.toString())
            }

            // The bytes, after the rows: a card arrives first and its file follows, so a grid that redraws
            // in between shows a file card with its preview and no bytes — which is what it shows anyway
            // until the user opens it.
            reconcileBlobs()

            pushed += response.accepted.size
            applied += response.rows.size
            copies += conflictCopies.size
            rev = response.rev

            if (!response.hasMore) {
                // The delta is finished. If this device is still holding rows back, go round again with
                // the cursor cleared: what follows is a fresh question, not another page of this answer.
                //
                // The delta comes down again with it, which is waste and not error — every row of it is
                // written over itself. Waste worth having: the alternative is moving the cursor while a
                // push is outstanding, and a cursor that has run ahead of what was actually read is the
                // one mistake in this file that costs rows rather than bytes.
                if (!moreToPush) return SyncResult(pushed, applied, copies, rev)

                // More to push, and the delta read to the end. Ask for the rest of the push from where
                // that left off, so the rows already applied are not sent down again with every bite —
                // which is what the first version of this did, and on a first sync of a few thousand
                // rows that is the delta re-delivered fifteen times over.
                since = response.rev
                cursor = null
                continue
            }

            // A server that says there is more but cannot say where to carry on from — or names the place
            // this page already started at — is one this client cannot page: stop, and leave the cursor
            // where it was. The run has applied what it was given and the next one asks the same question
            // again: slower than it should be, but nothing is skipped and nothing spins for ever, which
            // are the two properties worth keeping.
            val next = response.nextCursor
            if (next == null || next == cursor) return SyncResult(pushed, applied, copies, rev)
            cursor = next
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

        // `blobSha`/`deletedAt` have no index of their own — the same unindexed scan SQLite ran here too.
        val cards = db.read(Cards) { Cards.all().filter { it.blobSha != null && it.deletedAt == null } }
        if (cards.isEmpty()) return

        val held = mutableMapOf<String, String>() // sha -> the `data:` URI this device holds for it
        val wanted = mutableListOf<CardRow>() // cards whose bytes are somewhere else

        db.read(CardBlobs) {
            cards.forEach { card ->
                val sha = card.blobSha ?: return@forEach
                val local = CardBlobs.get(card.id)
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
            db.write(CardBlobs) {
                CardBlobs.put(
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

private fun text(row: SyncRow?, key: String): String? = row?.payload?.get(key)?.jsonPrimitive?.contentOrNull

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

private suspend fun WriteScope.putBase(key: RowKey, hash: String, rev: Long) {
    SyncMeta.put(SyncMetaRow().apply { tbl = key.tbl; rowId = key.id; this.hash = hash; this.rev = rev })
}

private suspend fun ReadScope.getState(key: String): String? = SyncState.get(key)?.v

private suspend fun WriteScope.putState(key: String, value: String) {
    SyncState.put(SyncStateRow().apply { k = key; v = value })
}
