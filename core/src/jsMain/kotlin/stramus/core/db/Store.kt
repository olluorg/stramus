@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.db

import io.github.kidx.Database
import io.github.kidx.Direction
import io.github.kidx.observe
import io.github.kromus.TextIndex
import io.github.kromus.sync.syncTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.crypto.hashPin
import stramus.core.crypto.randomSalt
import stramus.core.crypto.sha256HexBytes
import stramus.core.sync.DataUri
import stramus.core.merge.MergePlan
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.order.OrderKey
import stramus.core.repo.ActionStat
import stramus.core.repo.ActionUsageRepository
import stramus.core.repo.CachedIcon
import stramus.core.repo.CardRepository
import stramus.core.repo.CardSectionRepository
import stramus.core.repo.CollectionRepository
import stramus.core.repo.DeletedCard
import stramus.core.repo.DeletedCardSection
import stramus.core.repo.DeletedCollection
import stramus.core.repo.DeletedSection
import stramus.core.repo.FaviconRepository
import stramus.core.repo.SectionRepository
import stramus.core.repo.UsageRepository
import stramus.core.repo.UsageStat

/** Everything the UI needs: the open database plus the repositories over it. */
class StramusStore internal constructor(
    val db: Database,
    val sections: SectionRepository,
    val collections: CollectionRepository,
    val cardSections: CardSectionRepository,
    val cards: CardRepository,
    val favicons: FaviconRepository,
    val usage: UsageRepository,
    val actions: ActionUsageRepository,
    /**
     * Whether this open is the one that created the database — a first install, seeded from [StoreSeed]
     * and otherwise empty.
     *
     * It is the difference between a browser that has nothing to lose and one that does, which is the
     * only question worth asking a user who signs in: the welcome note is ours, not theirs, so a fresh
     * install joining an account is not a decision anybody has to be interrupted for.
     */
    val seeded: Boolean = false,
) {
    /**
     * Let go of the database: the background work first, then the connection itself.
     *
     * In that order, and not the other way round — a watcher still running against a closed connection
     * is exactly the error this exists to prevent. See [KidxCardRepository.close], which is the only
     * thing in here that keeps a job of its own.
     */
    fun close() {
        (cards as? KidxCardRepository)?.close()
        db.close()
    }
}

/** How long a tombstone is kept once the row is gone, so that a device offline for a while still hears. */
private val TOMBSTONE_RETENTION = 30.days

/**
 * Whether this database belongs to an account — which decides what a deletion *is*.
 *
 * Signed out, a deletion is a deletion: the row goes, as it always has, because a device that never syncs
 * would otherwise hoard the dead for ever. Signed in, it has to be a *thing that happened* — a tombstone —
 * or the other device, seeing only that a row is missing, would helpfully put it back.
 *
 * A [io.github.kidx.ReadScope] extension (not a [io.github.kidx.WriteScope] one) so it can be called from
 * either — every write scope is also a read scope — as long as the caller has named `SyncState` in its
 * `db.read`/`db.write` store list.
 */
private suspend fun io.github.kidx.ReadScope.syncing(): Boolean = SyncState.get("userId") != null

/**
 * What a first install starts with. An empty sidebar is nothing to hand a new user, so a database
 * that has never held anything is given the three things the user will go on making themselves: the
 * default section, a collection in it, and a note in that collection saying how the app is used.
 *
 * The words are the UI's, not the database's — the language is only known up in the app, which hands
 * its own table down to [openStramusStore] (see `I18n.kt`). Seeding happens on that first open and
 * never again: a user who clears all of it away has cleared it away.
 */
data class StoreSeed(
    /** The title of the non-deletable default section. */
    val sectionTitle: String,
    val collectionTitle: String,
    val noteTitle: String,
    /** The note's body, in the markdown a note card stores. */
    val noteBody: String,
) {
    companion object {
        /** For a caller with no interface behind it to take a language from. */
        val Default = StoreSeed(
            sectionTitle = "Main",
            collectionTitle = "Getting started",
            noteTitle = "How to use stramus",
            noteBody = "Drag a link, a tab or a file here to save it.",
        )
    }
}

/**
 * Ensures the default section exists, seeds a first install from [seed], and returns the store over
 * [db].
 *
 * There is no migration here: [db] is opened fresh against [stramusSchema] every time (see
 * `StoreJs.kt`) — kidx owns its own structural verification on open, and a database that predates kidx
 * simply isn't this one; it is a different, untouched IndexedDB database left on disk under its old
 * name. A first open of *this* database is therefore always either empty or already in the current
 * shape — nothing here ever needs to repair an older one.
 */
suspend fun openStramusStore(db: Database, seed: StoreSeed = StoreSeed.Default): StramusStore {
    // Nothing has ever been in this database — a first install, as opposed to one the user has emptied
    // out (a section always remains there, and a card or a collection may). Asked before the default
    // section is made, which is itself the first thing seeding puts in.
    val fresh = db.read(Sections, Collections, Cards) {
        Sections.count() == 0L && Collections.count() == 0L && Cards.count() == 0L
    }

    val sections = KidxSectionRepository(db, seed.sectionTitle)
    val defaultId = sections.defaultSectionId()
    val cards = KidxCardRepository(db)
    if (fresh) {
        // A section on its own can hold nothing and a collection on its own says nothing: the user
        // arrives at a card that tells them what the rest of it is for. All three are ordinary rows —
        // renameable, movable, deletable — not fixtures of the app.
        val welcome = insertCollection(db, seed.collectionTitle, defaultId)
        cards.addNote(welcome.id, seed.noteTitle, seed.noteBody)
    }

    purgeTombstones(db)

    return StramusStore(
        db,
        sections,
        KidxCollectionRepository(db, defaultId),
        KidxCardSectionRepository(db),
        cards,
        KidxFaviconRepository(db),
        KidxUsageRepository(db),
        KidxActionUsageRepository(db),
        seeded = fresh,
    )
}

/**
 * A row exactly as it stands, detached from the one the store handed back — what an undo of a merge is
 * built out of. Every field, tombstone and PIN included: a snapshot that quietly dropped one would put
 * back something subtly other than what was taken.
 */
private fun SectionRow.snapshot() = SectionRow().also {
    it.id = id; it.title = title; it.orderKey = orderKey; it.deletable = deletable
    it.collapsed = collapsed; it.pinSalt = pinSalt; it.pinHash = pinHash
    it.updatedAt = updatedAt; it.deletedAt = deletedAt
}

private fun CollectionRow.snapshot() = CollectionRow().also {
    it.id = id; it.sectionId = sectionId; it.title = title; it.orderKey = orderKey
    it.createdAt = createdAt; it.readOnly = readOnly; it.icon = icon; it.color = color
    it.updatedAt = updatedAt; it.deletedAt = deletedAt
}

private fun CardSectionRow.snapshot() = CardSectionRow().also {
    it.id = id; it.collectionId = collectionId; it.title = title; it.description = description
    it.orderKey = orderKey; it.collapsed = collapsed; it.updatedAt = updatedAt; it.deletedAt = deletedAt
}

private fun CardRow.snapshot() = CardRow().also {
    it.id = id; it.collectionId = collectionId; it.cardSectionId = cardSectionId; it.kind = kind
    it.title = title; it.url = url; it.favicon = favicon; it.content = content; it.thumb = thumb
    it.mime = mime; it.blobSha = blobSha; it.orderKey = orderKey; it.createdAt = createdAt
    it.updatedAt = updatedAt; it.deletedAt = deletedAt; it.aiCreated = aiCreated
}

/**
 * Everything one merge changed, exactly as it stood before it — the way back from an operation far too
 * big for the thirty seconds an ordinary deletion gets.
 *
 * Every row here was either rewritten (a collection re-hung under another section) or deleted (the losing
 * half of a pair). Nothing is created, so putting all of these back is the whole of the undo.
 */
data class MergeUndo(
    internal val sections: List<SectionRow>,
    internal val collections: List<CollectionRow>,
    internal val cardSections: List<CardSectionRow>,
    internal val cards: List<CardRow>,
    internal val blobs: Map<Uuid, String>,
) {
    val rows: Int get() = sections.size + collections.size + cardSections.size + cards.size
}

/** How much a merge actually joined, for the sentence shown afterwards. */
data class MergeResult(
    val sections: Int,
    val collections: Int,
    val cardSections: Int,
    val cards: Int,
    val undo: MergeUndo,
) {
    val nothing: Boolean get() = sections + collections + cardSections + cards == 0
}

/**
 * Carry out a [MergePlan] — the plan the user has read and ticked, not the one that was proposed.
 *
 * Top down: a section's losers hand over their collections and go, then the same for collections, then
 * card sections, and last of all the duplicate cards. Handing over is an *append*: the loser's children
 * keep their order among themselves and land after the winner's, rather than being interleaved. Two order
 * keys made on two devices are two independent scales, and shuffling them together would put the arriving
 * half in places nobody chose.
 *
 * Field by field, the winner keeps what the later-written row said — the same last-write-wins the sync
 * settles everything else by, so a merge and a sync cannot disagree about which title is the current one.
 *
 * One transaction. A half-merged tree — a collection moved out of a section that has already gone — is not
 * a state this is allowed to end in.
 */
suspend fun StramusStore.applyMerge(plan: MergePlan): MergeResult {
    var mergedSections = 0
    var mergedCollections = 0
    var mergedCardSections = 0
    var mergedCards = 0

    val sectionsBefore = mutableMapOf<Uuid, SectionRow>()
    val collectionsBefore = mutableMapOf<Uuid, CollectionRow>()
    val cardSectionsBefore = mutableMapOf<Uuid, CardSectionRow>()
    val cardsBefore = mutableMapOf<Uuid, CardRow>()

    // The bytes of every file card the merge is about to drop, read before the write — exactly as a single
    // deletion reads them, and for the same reason: an undo has to be able to open the file again.
    val doomed = plan.sections.flatMap { it.collections }.flatMap { it.cards }.flatMap { it.losers }
    val blobs = db.read(CardBlobs) {
        doomed.mapNotNull { id -> CardBlobs.get(id)?.let { id to it.data } }
    }.toMap()

    db.write(Sections, Collections, CardSections, Cards, CardBlobs, SyncState) {
        val syncing = syncing()
        val now = Clock.System.now()

        for (sectionPlan in plan.sections) {
            val section = sectionPlan.section
            if (section.fuses) {
                val winner = Sections.get(section.winner)
                var winnerChanged = false
                // The collections of the losing sections move across, in their own order, after the ones
                // the winner already has.
                var last = Collections.find(Collections.bySection) { Collections.sectionId eq section.winner }
                    .filter { it.deletedAt == null }
                    .maxOfOrNull { it.orderKey }

                for (loserId in section.losers) {
                    val loser = Sections.get(loserId) ?: continue
                    if (loserId !in sectionsBefore) sectionsBefore[loserId] = loser.snapshot()

                    val moving = Collections.find(Collections.bySection) { Collections.sectionId eq loserId }
                        .filter { it.deletedAt == null }
                        .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
                    for (collection in moving) {
                        if (collection.id !in collectionsBefore) collectionsBefore[collection.id] = collection.snapshot()
                        collection.sectionId = section.winner
                        collection.orderKey = OrderKey.between(last, null)
                        collection.updatedAt = now
                        last = collection.orderKey
                        Collections.put(collection)
                    }

                    // The winner takes whatever the later-written of the two said about itself.
                    if (winner != null && loser.updatedAt > winner.updatedAt) {
                        if (winner.id !in sectionsBefore) sectionsBefore[winner.id] = winner.snapshot()
                        winner.title = loser.title
                        winner.collapsed = loser.collapsed
                        winnerChanged = true
                    }

                    if (syncing) {
                        loser.deletedAt = now
                        loser.updatedAt = now
                        Sections.put(loser)
                    } else {
                        Sections.delete(loserId)
                    }
                    mergedSections++
                }
                if (winner != null && winnerChanged) {
                    winner.updatedAt = now
                    Sections.put(winner)
                }
            }

            for (collectionPlan in sectionPlan.collections) {
                val collection = collectionPlan.collection
                if (collection.fuses) {
                    val winner = Collections.get(collection.winner)
                    var winnerChanged = false
                    var lastGroup = CardSections.find(CardSections.byCollection) {
                        CardSections.collectionId eq collection.winner
                    }.filter { it.deletedAt == null }.maxOfOrNull { it.orderKey }
                    var lastCard = Cards.find(Cards.byCollection) { Cards.collectionId eq collection.winner }
                        .filter { it.deletedAt == null }
                        .maxOfOrNull { it.orderKey }

                    for (loserId in collection.losers) {
                        val loser = Collections.get(loserId) ?: continue
                        if (loserId !in collectionsBefore) collectionsBefore[loserId] = loser.snapshot()

                        val groups = CardSections.find(CardSections.byCollection) {
                            CardSections.collectionId eq loserId
                        }.filter { it.deletedAt == null }
                            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
                        for (group in groups) {
                            if (group.id !in cardSectionsBefore) cardSectionsBefore[group.id] = group.snapshot()
                            group.collectionId = collection.winner
                            group.orderKey = OrderKey.between(lastGroup, null)
                            group.updatedAt = now
                            lastGroup = group.orderKey
                            CardSections.put(group)
                        }

                        val moving = Cards.find(Cards.byCollection) { Cards.collectionId eq loserId }
                            .filter { it.deletedAt == null }
                            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
                        for (card in moving) {
                            if (card.id !in cardsBefore) cardsBefore[card.id] = card.snapshot()
                            card.collectionId = collection.winner
                            card.orderKey = OrderKey.between(lastCard, null)
                            card.updatedAt = now
                            lastCard = card.orderKey
                            Cards.put(card)
                        }

                        if (winner != null && loser.updatedAt > winner.updatedAt) {
                            if (winner.id !in collectionsBefore) collectionsBefore[winner.id] = winner.snapshot()
                            winner.title = loser.title
                            winner.icon = loser.icon
                            winner.color = loser.color
                            winnerChanged = true
                        }
                        // One collection, and it has stood since the earlier of the two was made.
                        if (winner != null && loser.createdAt < winner.createdAt) {
                            if (winner.id !in collectionsBefore) collectionsBefore[winner.id] = winner.snapshot()
                            winner.createdAt = loser.createdAt
                            winnerChanged = true
                        }

                        if (syncing) {
                            loser.deletedAt = now
                            loser.updatedAt = now
                            Collections.put(loser)
                        } else {
                            Collections.delete(loserId)
                        }
                        mergedCollections++
                    }
                    if (winner != null && winnerChanged) {
                        winner.updatedAt = now
                        Collections.put(winner)
                    }
                }

                for (group in collectionPlan.cardSections) {
                    if (!group.fuses) continue
                    val winner = CardSections.get(group.winner)
                    var winnerChanged = false
                    // `cardSectionId` has no index of its own — the same unindexed scan the rest of this
                    // file runs for the question "which cards are in this group".
                    var lastCard = Cards.all()
                        .filter { it.deletedAt == null && it.cardSectionId == group.winner }
                        .maxOfOrNull { it.orderKey }

                    for (loserId in group.losers) {
                        val loser = CardSections.get(loserId) ?: continue
                        if (loserId !in cardSectionsBefore) cardSectionsBefore[loserId] = loser.snapshot()

                        val moving = Cards.all()
                            .filter { it.deletedAt == null && it.cardSectionId == loserId }
                            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
                        for (card in moving) {
                            if (card.id !in cardsBefore) cardsBefore[card.id] = card.snapshot()
                            card.cardSectionId = group.winner
                            card.orderKey = OrderKey.between(lastCard, null)
                            card.updatedAt = now
                            lastCard = card.orderKey
                            Cards.put(card)
                        }

                        if (winner != null && loser.updatedAt > winner.updatedAt) {
                            if (winner.id !in cardSectionsBefore) cardSectionsBefore[winner.id] = winner.snapshot()
                            winner.title = loser.title
                            winner.description = loser.description
                            winnerChanged = true
                        }

                        if (syncing) {
                            loser.deletedAt = now
                            loser.updatedAt = now
                            CardSections.put(loser)
                        } else {
                            CardSections.delete(loserId)
                        }
                        mergedCardSections++
                    }
                    if (winner != null && winnerChanged) {
                        winner.updatedAt = now
                        CardSections.put(winner)
                    }
                }

                // Last, and only now that every card of the pair sits in the winning collection: the
                // duplicates among them.
                for (fusion in collectionPlan.cards) {
                    for (loserId in fusion.losers) {
                        val row = Cards.get(loserId) ?: continue
                        if (loserId !in cardsBefore) cardsBefore[loserId] = row.snapshot()
                        if (syncing) {
                            row.deletedAt = now
                            row.updatedAt = now
                            Cards.put(row)
                        } else {
                            CardBlobs.delete(loserId)
                            Cards.delete(loserId)
                        }
                        mergedCards++
                    }
                }
            }
        }
    }

    return MergeResult(
        sections = mergedSections,
        collections = mergedCollections,
        cardSections = mergedCardSections,
        cards = mergedCards,
        undo = MergeUndo(
            sections = sectionsBefore.values.toList(),
            collections = collectionsBefore.values.toList(),
            cardSections = cardSectionsBefore.values.toList(),
            cards = cardsBefore.values.toList(),
            blobs = blobs,
        ),
    )
}

/**
 * Put a merge back, row for row.
 *
 * `put` throughout, as every undo in this file does: a row may be sitting there as a tombstone (a
 * signed-in database deletes by marking), and an unconditional overwrite is what "it comes back as it was"
 * means. `updatedAt` moves to now so the restoration beats the merge on the way to the server — the other
 * devices have to be told the joining was taken back.
 */
suspend fun StramusStore.undoMerge(undo: MergeUndo) {
    db.write(Sections, Collections, CardSections, Cards, CardBlobs) {
        val now = Clock.System.now()
        undo.sections.forEach { Sections.put(it.also { row -> row.updatedAt = now }) }
        undo.collections.forEach { Collections.put(it.also { row -> row.updatedAt = now }) }
        undo.cardSections.forEach { CardSections.put(it.also { row -> row.updatedAt = now }) }
        undo.cards.forEach { card ->
            Cards.put(card.also { it.updatedAt = now })
            undo.blobs[card.id]?.let { bytes ->
                CardBlobs.put(CardBlobRow().apply { this.cardId = card.id; this.data = bytes })
            }
        }
    }
}

/**
 * Sweep the dead.
 *
 * A tombstone exists to tell the *other* device that a row went. Once it has done that — or if there is no
 * other device, because this database has no account — it is landfill, and the file bytes behind a deleted
 * file card are landfill measured in megabytes.
 *
 * Signed out, they go at once: a database that never syncs has nobody to tell. Signed in, they are kept
 * for [TOMBSTONE_RETENTION], which is the length of holiday a device may go on without coming back to find
 * that the rows it deleted have been helpfully restored by a device that never heard.
 *
 * `deletedAt` is nullable, so none of these stores can index it (decision 10) — each one is read whole
 * and filtered in Kotlin, exactly the scan SQLite ran too: none of these columns had an index there
 * either.
 */
private suspend fun purgeTombstones(db: Database) {
    db.write(Sections, Collections, CardSections, Cards, CardBlobs, Usage, SyncState) {
        val cutoff = if (syncing()) Clock.System.now() - TOMBSTONE_RETENTION else Clock.System.now()
        fun isDead(deletedAt: kotlin.time.Instant?) = deletedAt != null && deletedAt < cutoff

        Cards.all().filter { isDead(it.deletedAt) }.forEach { card ->
            CardBlobs.delete(card.id)
            Cards.delete(card.id)
        }
        CardSections.all().filter { isDead(it.deletedAt) }.forEach { CardSections.delete(it.id) }
        Collections.all().filter { isDead(it.deletedAt) }.forEach { Collections.delete(it.id) }
        Sections.all().filter { isDead(it.deletedAt) }.forEach { Sections.delete(it.id) }
        Usage.all().filter { isDead(it.deletedAt) }.forEach { Usage.delete(it.url) }
    }
}

/**
 * [isDefault] is the one section this database will not delete, worked out across all of them — see
 * [Section.seeded] for why the column alone cannot say it any more.
 */
private fun SectionRow.toModel(isDefault: Boolean = deletable == 0) =
    Section(id, title, orderKey, !isDefault, collapsed != 0, pinHash != null, seeded = deletable == 0)
private fun CollectionRow.toModel() =
    Collection(id, sectionId, title, orderKey, createdAt, readOnly != 0, icon, color)
private fun CardSectionRow.toModel() = CardSection(id, collectionId, title, description, orderKey, collapsed != 0)
private fun CardRow.toModel() = Card(
    id, collectionId, cardSectionId, CardKind.from(kind), title, url, favicon, content, thumb, mime, blobSha,
    orderKey, createdAt, aiCreated == true,
)

// The way back from a model to the row it came from — what an undo writes. A restored row keeps its
// id and its order key, so what comes back is the thing that was deleted, in the place it was deleted
// from, and not a copy of it appended to the end.
private fun Collection.toRow() = CollectionRow().apply {
    this.id = this@toRow.id
    this.sectionId = this@toRow.sectionId
    this.title = this@toRow.title
    this.orderKey = this@toRow.orderKey
    this.createdAt = this@toRow.createdAt
    this.readOnly = if (this@toRow.readOnly) 1 else 0
    this.icon = this@toRow.icon
    this.color = this@toRow.color
    this.updatedAt = Clock.System.now()
}

private fun CardSection.toRow() = CardSectionRow().apply {
    this.id = this@toRow.id
    this.collectionId = this@toRow.collectionId
    this.title = this@toRow.title
    this.description = this@toRow.description
    this.orderKey = this@toRow.orderKey
    this.collapsed = if (this@toRow.collapsed) 1 else 0
    this.updatedAt = Clock.System.now()
}

private fun Card.toRow() = CardRow().apply {
    this.id = this@toRow.id
    this.collectionId = this@toRow.collectionId
    this.cardSectionId = this@toRow.cardSectionId
    this.kind = this@toRow.kind.id
    this.title = this@toRow.title
    this.url = this@toRow.url
    this.favicon = this@toRow.favicon
    this.content = this@toRow.content
    this.thumb = this@toRow.thumb
    this.mime = this@toRow.mime
    this.blobSha = this@toRow.blobSha
    this.orderKey = this@toRow.orderKey
    this.createdAt = this@toRow.createdAt
    this.updatedAt = Clock.System.now()
}

/**
 * The key of a row dropped at [index] among [siblings] — the row itself already taken out of them, so
 * [index] counts the places it could land. This is the whole of what a move now writes: one key, on
 * one row, leaving every other row of the group untouched.
 */
private fun keyAt(siblings: List<String>, index: Int): String {
    val at = index.coerceIn(0, siblings.size)
    return OrderKey.between(siblings.getOrNull(at - 1), siblings.getOrNull(at))
}

/** The key that appends to a group: after [last], or the first key of all if the group is empty. */
private fun appendKey(last: String?): String = OrderKey.between(last, null)

/**
 * Insert a collection at the end of its section. Shared by the collection repository and by
 * [KidxSectionRepository.create], which gives every new section a collection of its own name.
 */
private suspend fun insertCollection(db: Database, title: String, sectionId: Uuid): Collection {
    val row = db.write(Collections) {
        val last = Collections.find(Collections.bySection) { Collections.sectionId eq sectionId }
            .filter { it.deletedAt == null }
            .lastOrNull()
            ?.orderKey
        val row = CollectionRow().apply {
            this.id = Uuid.random()
            this.sectionId = sectionId
            this.title = title
            this.orderKey = appendKey(last)
            this.createdAt = Clock.System.now()
            this.readOnly = 0
            this.updatedAt = Clock.System.now()
        }
        Collections.add(row)
        row
    }
    return row.toModel()
}

/**
 * Take a collection out of the database whole — its card sections, its cards and their file bytes —
 * and hand back everything needed to put it back ([restoreCollection]). Null if there is no such
 * collection. Shared with section deletion, which does this to each collection of the section.
 */
private suspend fun deleteCollection(db: Database, id: Uuid): DeletedCollection? {
    val collection = db.read(Collections) { Collections.get(id) } ?: return null
    val cardSections = db.read(CardSections) {
        CardSections.find(CardSections.byCollection) { CardSections.collectionId eq id }
    }
    val cards = db.read(Cards) {
        Cards.find(Cards.byCollection) { Cards.collectionId eq id }
    }
    // The bytes are read out before they are deleted: an undone deletion has to open the file again.
    val blobs = db.read(CardBlobs) {
        cards.mapNotNull { card -> CardBlobs.get(card.id)?.let { card.id to it.data } }
    }.toMap()

    db.write(Cards, CardSections, Collections, CardBlobs, SyncState) {
        val now = Clock.System.now()
        if (syncing()) {
            // Tombstones, all the way down: the other device has these cards, and has to be told they
            // went. The file bytes stay until the tombstones are swept — an undo has to open them again.
            cards.forEach { card -> Cards.put(card.also { it.deletedAt = now; it.updatedAt = now }) }
            cardSections.forEach { cs -> CardSections.put(cs.also { it.deletedAt = now; it.updatedAt = now }) }
            Collections.put(collection.also { it.deletedAt = now; it.updatedAt = now })
        } else {
            cards.forEach { Cards.delete(it.id); CardBlobs.delete(it.id) }
            cardSections.forEach { CardSections.delete(it.id) }
            Collections.delete(id)
        }
    }
    return DeletedCollection(collection.toModel(), cardSections.map { it.toModel() }, cards.map { it.toModel() }, blobs)
}

/** The undo of [deleteCollection]: every row back, with the id and the place it had. */
private suspend fun restoreCollection(db: Database, deleted: DeletedCollection) {
    db.write(Collections, CardSections, Cards, CardBlobs) {
        // The rows may still be there as tombstones (a signed-in database deletes by marking), so `put`
        // — an unconditional overwrite — is what "the thing that went comes back, as it was" means here.
        Collections.put(deleted.collection.toRow())
        deleted.cardSections.forEach { CardSections.put(it.toRow()) }
        deleted.cards.forEach { card ->
            Cards.put(card.toRow())
            deleted.blobs[card.id]?.let { bytes ->
                CardBlobs.put(CardBlobRow().apply { this.cardId = card.id; this.data = bytes })
            }
        }
    }
}

internal class KidxSectionRepository(
    private val db: Database,
    /** The name the default section is created under, in the language the app was first opened in. */
    private val defaultTitle: String,
) : SectionRepository {

    override suspend fun all(): List<Section> {
        val rows = db.read(Sections) {
            Sections.all()
                .filter { it.deletedAt == null }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
        }
        // Which one is the default is decided here, over all of them, rather than read off each row on
        // its own: a database can hold several seeded sections (sync brings another device's in beside
        // our own), and only the first of them in the order above is the one this database keeps. The
        // rest are ordinary sections the user can delete — see [Section.seeded].
        val default = rows.firstOrNull { it.deletable == 0 }?.id
        return rows.map { it.toModel(isDefault = it.id == default) }
    }

    override suspend fun create(title: String): Section {
        val row = db.write(Sections) {
            val last = Sections.all().filter { it.deletedAt == null }.sortedBy { it.orderKey }.lastOrNull()?.orderKey
            val row = SectionRow().apply {
                this.id = Uuid.random()
                this.title = title
                this.orderKey = appendKey(last)
                this.deletable = 1
                this.collapsed = 0
                this.pinSalt = null
                this.pinHash = null
                this.updatedAt = Clock.System.now()
            }
            Sections.add(row)
            row
        }
        // A section with no collection in it can hold nothing, so it comes with one, named after it.
        insertCollection(db, title, row.id)
        return row.toModel()
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.write(Sections) {
            val row = Sections.get(id) ?: return@write
            row.title = title
            row.updatedAt = Clock.System.now()
            Sections.put(row)
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.write(Sections) {
            val row = Sections.get(id) ?: return@write
            row.collapsed = if (collapsed) 1 else 0
            row.updatedAt = Clock.System.now()
            Sections.put(row)
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.write(Sections) {
            val row = Sections.get(id) ?: return@write
            val siblings = Sections.all()
                .filter { it.deletedAt == null && it.id != id }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))

            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            row.orderKey = key
            row.updatedAt = Clock.System.now()
            Sections.put(row)
        }
    }

    override suspend fun delete(id: Uuid): DeletedSection? {
        if (id == defaultSectionId()) return null // the default section is not deletable
        val row = db.read(Sections) { Sections.get(id) } ?: return null

        // A section owns its collections: they go with it, rather than being tipped into the default
        // section, where they would be one more thing for the user to clear away. Nothing is lost by
        // it — everything taken out here goes into the snapshot, and an undo puts all of it back.
        val collectionIds = db.read(Collections) {
            Collections.find(Collections.bySection) { Collections.sectionId eq id }
        }.map { it.id }
        val collections = collectionIds.mapNotNull { deleteCollection(db, it) }

        db.write(Sections, SyncState) {
            val now = Clock.System.now()
            if (syncing()) {
                val current = Sections.get(id) ?: return@write
                current.deletedAt = now
                current.updatedAt = now
                Sections.put(current)
            } else {
                Sections.delete(id)
            }
        }
        return DeletedSection(row.toModel(), collections, row.pinSalt, row.pinHash)
    }

    override suspend fun restore(deleted: DeletedSection) {
        val row = SectionRow().apply {
            this.id = deleted.section.id
            this.title = deleted.section.title
            this.orderKey = deleted.section.orderKey
            // The column records what the row was made as, not what this database will let go of.
            this.deletable = if (deleted.section.seeded) 0 else 1
            this.collapsed = if (deleted.section.collapsed) 1 else 0
            // The PIN comes back with the section: an undone deletion must not be a way past a lock.
            this.pinSalt = deleted.pinSalt
            this.pinHash = deleted.pinHash
            this.updatedAt = Clock.System.now()
        }
        db.write(Sections) { Sections.put(row) }
        deleted.collections.forEach { restoreCollection(db, it) }
    }

    /**
     * Returns the default section id, creating the non-deletable default section if absent.
     *
     * The same pick as [all] makes, in the same order and over the same rows — a live seeded section,
     * first by order key then by id. Two devices holding the same two seeded sections therefore agree
     * on which is the default, where "whichever the store handed back first" did not.
     */
    override suspend fun defaultSectionId(): Uuid {
        val existing = db.read(Sections) { Sections.all() }
            .filter { it.deletedAt == null && it.deletable == 0 }
            .minWithOrNull(compareBy({ it.orderKey }, { it.id.toString() }))
        if (existing != null) return existing.id
        val row = SectionRow().apply {
            this.id = Uuid.random()
            this.title = defaultTitle
            this.orderKey = OrderKey.FIRST
            this.deletable = 0
            this.collapsed = 0
            this.pinSalt = null
            this.pinHash = null
            this.updatedAt = Clock.System.now()
        }
        db.write(Sections) { Sections.add(row) }
        return row.id
    }

    override suspend fun setPin(id: Uuid, pin: String) {
        val salt = randomSalt()
        val hash = hashPin(pin, salt)
        db.write(Sections) {
            val row = Sections.get(id) ?: return@write
            row.pinSalt = salt
            row.pinHash = hash
            row.updatedAt = Clock.System.now()
            Sections.put(row)
        }
    }

    override suspend fun clearPin(id: Uuid) {
        db.write(Sections) {
            val row = Sections.get(id) ?: return@write
            row.pinSalt = null
            row.pinHash = null
            row.updatedAt = Clock.System.now()
            Sections.put(row)
        }
    }

    override suspend fun verifyPin(id: Uuid, pin: String): Boolean {
        val row = db.read(Sections) { Sections.get(id) } ?: return false
        val salt = row.pinSalt
        val hash = row.pinHash ?: return true // not locked: there is nothing to get wrong
        return salt != null && hashPin(pin, salt) == hash
    }
}

internal class KidxCollectionRepository(
    private val db: Database,
    private val defaultSectionId: Uuid,
) : CollectionRepository {

    override suspend fun all(): List<Collection> = db.read(Collections) {
        // Ordered by key, which orders each section's collections among themselves; collections of two
        // different sections do not compare, and the UI never asks them to — it walks the sections in
        // their own order and takes the collections of each.
        Collections.all()
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
    }.map { it.toModel() }

    override suspend fun create(title: String, sectionId: Uuid): Collection = insertCollection(db, title, sectionId)

    override suspend fun rename(id: Uuid, title: String) {
        db.write(Collections) {
            val row = Collections.get(id) ?: return@write
            row.title = title
            row.updatedAt = Clock.System.now()
            Collections.put(row)
        }
    }

    override suspend fun delete(id: Uuid): DeletedCollection? = deleteCollection(db, id)

    override suspend fun restore(deleted: DeletedCollection) = restoreCollection(db, deleted)

    override suspend fun moveToSection(id: Uuid, sectionId: Uuid) {
        db.write(Collections) {
            val row = Collections.get(id) ?: return@write
            // The end of the section it lands in — a key from the section it came from would name a place
            // among rows it has never been ordered against.
            val last = Collections.find(Collections.bySection) { Collections.sectionId eq sectionId }
                .filter { it.deletedAt == null }
                .lastOrNull()
                ?.orderKey
            row.sectionId = sectionId
            row.orderKey = appendKey(last)
            row.updatedAt = Clock.System.now()
            Collections.put(row)
        }
    }

    override suspend fun move(id: Uuid, toSectionId: Uuid, newIndex: Int) {
        db.write(Collections) {
            val row = Collections.get(id) ?: return@write

            val siblings = Collections.find(Collections.bySection) { Collections.sectionId eq toSectionId }
                .filter { it.deletedAt == null && it.id != id }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))

            // Section and place at once, and nothing else touched: where this used to renumber every
            // collection of every section, it now writes the one row that moved.
            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            row.sectionId = toSectionId
            row.orderKey = key
            row.updatedAt = Clock.System.now()
            Collections.put(row)
        }
    }

    override suspend fun setReadOnly(id: Uuid, readOnly: Boolean) {
        db.write(Collections) {
            val row = Collections.get(id) ?: return@write
            row.readOnly = if (readOnly) 1 else 0
            row.updatedAt = Clock.System.now()
            Collections.put(row)
        }
    }

    override suspend fun setIcon(id: Uuid, icon: String?, color: String?) {
        db.write(Collections) {
            val row = Collections.get(id) ?: return@write
            row.icon = icon
            row.color = color
            row.updatedAt = Clock.System.now()
            Collections.put(row)
        }
    }
}

internal class KidxCardSectionRepository(
    private val db: Database,
) : CardSectionRepository {

    override suspend fun byCollection(collectionId: Uuid): List<CardSection> = db.read(CardSections) {
        CardSections.find(CardSections.byCollection) { CardSections.collectionId eq collectionId }
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
    }.map { it.toModel() }

    override suspend fun create(collectionId: Uuid, title: String, description: String?): CardSection {
        val row = db.write(CardSections) {
            val last = CardSections.find(CardSections.byCollection) { CardSections.collectionId eq collectionId }
                .filter { it.deletedAt == null }
                .lastOrNull()
                ?.orderKey
            val row = CardSectionRow().apply {
                this.id = Uuid.random()
                this.collectionId = collectionId
                this.title = title
                this.description = description
                this.orderKey = appendKey(last)
                this.collapsed = 0
                this.updatedAt = Clock.System.now()
            }
            CardSections.add(row)
            row
        }
        return row.toModel()
    }

    override suspend fun update(id: Uuid, title: String, description: String?) {
        db.write(CardSections) {
            val row = CardSections.get(id) ?: return@write
            row.title = title
            row.description = description
            row.updatedAt = Clock.System.now()
            CardSections.put(row)
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.write(CardSections) {
            val row = CardSections.get(id) ?: return@write
            row.collapsed = if (collapsed) 1 else 0
            row.updatedAt = Clock.System.now()
            CardSections.put(row)
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.write(CardSections) {
            val moving = CardSections.get(id) ?: return@write
            val siblings = CardSections.find(CardSections.byCollection) { CardSections.collectionId eq moving.collectionId }
                .filter { it.deletedAt == null && it.id != id }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))

            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            moving.orderKey = key
            moving.updatedAt = Clock.System.now()
            CardSections.put(moving)
        }
    }

    override suspend fun delete(id: Uuid): DeletedCardSection? {
        val row = db.read(CardSections) { CardSections.get(id) } ?: return null
        // `cardSectionId` has no index of its own — the same unindexed scan SQLite ran for this query.
        val cardIds = db.read(Cards) {
            Cards.all().filter { it.cardSectionId == id }.sortedBy { it.orderKey }
        }.map { it.id }

        db.write(Cards, CardSections, SyncState) {
            // Detach the section's cards (they become ungrouped) before removing the section.
            Cards.all().filter { it.cardSectionId == id }.forEach { card ->
                card.cardSectionId = null
                card.updatedAt = Clock.System.now()
                Cards.put(card)
            }

            val now = Clock.System.now()
            if (syncing()) {
                val current = CardSections.get(id) ?: return@write
                current.deletedAt = now
                current.updatedAt = now
                CardSections.put(current)
            } else {
                CardSections.delete(id)
            }
        }
        return DeletedCardSection(row.toModel(), cardIds)
    }

    override suspend fun restore(deleted: DeletedCardSection) {
        db.write(CardSections, Cards) {
            CardSections.put(deleted.cardSection.toRow())
            // The cards were left behind, ungrouped; the ones that were in this section rejoin it.
            // Any that the user has since moved elsewhere are simply no longer there to be found.
            deleted.cardIds.forEach { cardId ->
                val card = Cards.get(cardId) ?: return@forEach
                card.cardSectionId = deleted.cardSection.id
                card.updatedAt = Clock.System.now()
                Cards.put(card)
            }
        }
    }
}

/** Words past this in one query are dropped: kromus's analyzer already ignores stop-words on top. */
private const val SEARCH_MAX_WORDS = 4

/** Plenty for a personal card collection; unlike the old unbounded `LIKE`, kromus needs a cap named. */
private const val SEARCH_RESULT_LIMIT = 200

internal class KidxCardRepository(
    private val db: Database,
) : CardRepository {

    /**
     * Full-text search over cards. kidx has none of its own by design (SPEC.md: "not part of kidx");
     * this is its companion, kept fresh from the live store the same way `Store.observe(db)` feeds any
     * other reactive read — see kromus-sync's `syncTo`.
     *
     * Reindexing runs in the background and is eventually consistent: a card saved a moment ago may not
     * be in the very next keystroke's results yet, where the old `LIKE` query was always exactly as
     * fresh as the last write. For a search box that is not a difference a user can see.
     *
     * Tombstones are filtered out of the snapshot before they reach the index — `Cards.observe(db)`
     * itself has no `where`, so "deleted cards are not searchable" is done here, in Kotlin, rather than
     * pretending kidx can express it.
     */
    private val searchIndex = TextIndex<Uuid>()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Stop watching the cards.
     *
     * The job below outlives every read the app makes — that is its whole point — but it must not
     * outlive the *database*. Left running past a `close()` it goes on asking a closed connection for
     * rows, which throws from a coroutine nobody is waiting on: in a page being torn down that is
     * merely noise, and anywhere the app closes a database and opens another (recovering a broken one,
     * restoring a backup, a test opening one store after another) it is an error attributed to whatever
     * happened to be running at the time.
     */
    fun close() {
        searchScope.cancel()
    }

    init {
        searchScope.launch {
            Cards.observe(db)
                .map { rows -> rows.filter { it.deletedAt == null } }
                .syncTo(searchIndex, keyOf = { it.id }, versionOf = { it.updatedAt }) { row ->
                    "${row.title} ${row.url} ${row.content.orEmpty()}"
                }
        }
    }

    override suspend fun byCollection(collectionId: Uuid): List<Card> = db.read(Cards) {
        // Every card of the collection, ordered by key. Keys are per group, so this puts each group's
        // cards in the right order among themselves — which is all the UI reads, since it draws the
        // ungrouped cards, then each card section in turn.
        Cards.find(Cards.byCollection) { Cards.collectionId eq collectionId }
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
    }.map { it.toModel() }

    override suspend fun count(collectionId: Uuid): Int = db.read(Cards) {
        Cards.find(Cards.byCollection) { Cards.collectionId eq collectionId }.count { it.deletedAt == null }
    }

    override suspend fun add(
        collectionId: Uuid,
        title: String,
        url: String,
        favicon: String?,
        cardSectionId: Uuid?,
        aiCreated: Boolean,
    ): Card = insert(collectionId, cardSectionId, CardKind.LINK, title, url, favicon, content = null, mime = null, aiCreated = aiCreated)

    override suspend fun addNote(collectionId: Uuid, title: String, content: String, cardSectionId: Uuid?): Card =
        insert(collectionId, cardSectionId, CardKind.NOTE, title, url = "", favicon = null, content = content, mime = null)

    override suspend fun addFile(
        collectionId: Uuid,
        title: String,
        dataUri: String,
        mime: String,
        thumb: String?,
        cardSectionId: Uuid?,
    ): Card = insert(
        collectionId,
        cardSectionId,
        CardKind.FILE,
        title,
        url = "",
        favicon = null,
        content = null, // the bytes go to card_blobs, not into the card
        mime = mime,
        thumb = thumb,
        blob = dataUri,
        // Hashed here, once, when the file arrives — not on every sync. It is what the server will store
        // the bytes under, and what another device will ask for them by.
        blobSha = DataUri.bytesOf(dataUri)?.let { sha256HexBytes(it) },
    )

    private suspend fun insert(
        collectionId: Uuid,
        cardSectionId: Uuid?,
        kind: CardKind,
        title: String,
        url: String,
        favicon: String?,
        content: String?,
        mime: String?,
        thumb: String? = null,
        blob: String? = null,
        blobSha: String? = null,
        aiCreated: Boolean = false,
    ): Card {
        val row = db.write(Cards, CardBlobs) {
            val last = lastKeyOfGroup(collectionId, cardSectionId)
            val row = CardRow().apply {
                this.id = Uuid.random()
                this.collectionId = collectionId
                this.cardSectionId = cardSectionId
                this.kind = kind.id
                this.title = title
                this.url = url
                this.favicon = favicon
                this.content = content
                this.thumb = thumb
                this.mime = mime
                this.blobSha = blobSha
                this.aiCreated = aiCreated
                this.orderKey = appendKey(last)
                this.createdAt = Clock.System.now()
                this.updatedAt = Clock.System.now()
            }
            Cards.add(row)
            // The card and its bytes land together: a card whose blob is missing would open empty.
            if (blob != null) {
                CardBlobs.add(CardBlobRow().apply { this.cardId = row.id; this.data = blob })
            }
            row
        }
        return row.toModel()
    }

    override suspend fun blob(id: Uuid): String? = db.read(CardBlobs) { CardBlobs.get(id)?.data }

    override suspend fun setThumb(id: Uuid, thumb: String) {
        db.write(Cards) {
            val row = Cards.get(id) ?: return@write
            row.thumb = thumb
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun clearLinkThumbs(): Boolean = db.write(Cards) {
        val stale = Cards.all().filter { it.kind == CardKind.LINK.id && it.thumb != null }
        stale.forEach { row ->
            row.thumb = null
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
        stale.isNotEmpty()
    }

    override suspend fun imageFilesWithoutThumb(): List<Card> = db.read(Cards) {
        Cards.all().filter { it.kind == CardKind.FILE.id && it.deletedAt == null }
    }.map { it.toModel() }.filter { it.thumb == null && (it.mime ?: "").startsWith("image/") }

    override suspend fun updateNote(id: Uuid, title: String, content: String) {
        db.write(Cards) {
            val row = Cards.get(id) ?: return@write
            row.title = title
            row.content = content
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.write(Cards) {
            val row = Cards.get(id) ?: return@write
            row.title = title
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun updateUrl(id: Uuid, url: String) {
        db.write(Cards) {
            val row = Cards.get(id) ?: return@write
            row.url = url
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun markOpened(id: Uuid) {
        db.write(Cards) {
            val row = Cards.get(id) ?: return@write
            if (row.aiCreated != true) return@write
            row.aiCreated = false
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun delete(id: Uuid): DeletedCard? {
        val row = db.read(Cards) { Cards.get(id) } ?: return null
        // The bytes are read out before they are deleted: an undone deletion has to open the file again.
        val blob = db.read(CardBlobs) { CardBlobs.get(id)?.data }
        db.write(Cards, CardBlobs, SyncState) {
            if (syncing()) {
                // A tombstone, and the bytes left where they are: an undo has to be able to open the file
                // again, and the sweep below takes both once the deletion is old enough to be everywhere.
                val now = Clock.System.now()
                val current = Cards.get(id) ?: return@write
                current.deletedAt = now
                current.updatedAt = now
                Cards.put(current)
            } else {
                CardBlobs.delete(id)
                Cards.delete(id)
            }
        }
        return DeletedCard(row.toModel(), blob)
    }

    override suspend fun restore(deleted: DeletedCard) {
        db.write(Cards, CardBlobs) {
            // The row may still be there as a tombstone (a signed-in database deletes by marking), so `put`
            // — an unconditional overwrite — is what "the card that comes back keeps its id and its place"
            // means here.
            Cards.put(deleted.card.toRow())
            if (deleted.blob != null) {
                CardBlobs.put(CardBlobRow().apply { this.cardId = deleted.card.id; this.data = deleted.blob })
            } else {
                CardBlobs.delete(deleted.card.id)
            }
        }
    }

    override suspend fun deleteGroup(collectionId: Uuid, cardSectionId: Uuid?): List<DeletedCard> {
        // `cardSectionId` has no index of its own — the group is picked out of the collection's own
        // rows, which is the query the grid already runs to draw them.
        val rows = db.read(Cards) {
            Cards.find(Cards.byCollection) { Cards.collectionId eq collectionId }
                .filter { it.deletedAt == null && it.cardSectionId == cardSectionId }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
        }
        if (rows.isEmpty()) return emptyList()

        // The bytes are read out before they are deleted: an undone deletion has to open the files again.
        val blobs = db.read(CardBlobs) {
            rows.mapNotNull { row -> CardBlobs.get(row.id)?.let { row.id to it.data } }
        }.toMap()

        val taken = rows.map { it.toModel() }
        db.write(Cards, CardBlobs, SyncState) {
            val now = Clock.System.now()
            if (syncing()) {
                // Tombstones, and the bytes left where they are — the same bargain [delete] strikes for
                // one card: the other device has to be told these went, and an undo has to open the
                // files again. The sweep takes both once the deletions are old enough to be everywhere.
                rows.forEach { row -> Cards.put(row.also { it.deletedAt = now; it.updatedAt = now }) }
            } else {
                rows.forEach { CardBlobs.delete(it.id); Cards.delete(it.id) }
            }
        }
        // Built from the models read before the write: `rows` now carry a tombstone's `deletedAt`, and
        // what the undo puts back has to be the card as it stood.
        return taken.map { DeletedCard(it, blobs[it.id]) }
    }

    override suspend fun restoreAll(deleted: List<DeletedCard>) {
        db.write(Cards, CardBlobs) {
            // `put` rather than `add`, for the reason [restore] gives: the row may still be there as a
            // tombstone, and what comes back keeps its id and its place.
            deleted.forEach { card ->
                Cards.put(card.card.toRow())
                val bytes = card.blob
                if (bytes != null) {
                    CardBlobs.put(CardBlobRow().apply { this.cardId = card.card.id; this.data = bytes })
                } else {
                    CardBlobs.delete(card.card.id)
                }
            }
        }
    }

    override suspend fun move(id: Uuid, toCollectionId: Uuid, cardSectionId: Uuid?, newIndex: Int) {
        db.write(Cards, CardSections) {
            val row = Cards.get(id) ?: return@write

            // A card can only join a section of the collection it lands in; anything else (a stale
            // section from the collection it came from) would hide it from every group.
            val groups = CardSections.find(CardSections.byCollection) { CardSections.collectionId eq toCollectionId }
                .filter { it.deletedAt == null }
                .map { it.id }
            val group = cardSectionId?.takeIf { it in groups }

            val siblings = Cards.find(Cards.byCollection) { Cards.collectionId eq toCollectionId }
                .filter { it.deletedAt == null && it.id != id && it.cardSectionId == group }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))

            val key = keyAt(siblings.map { it.orderKey }, newIndex)

            // One row, one write — collection, group and place together. A card dragged out of a group
            // ends up ungrouped by setting `group` to null explicitly, which `put` (a whole-record write)
            // always does — there is no "leave this field alone" in IndexedDB the way a patch `update` had.
            row.collectionId = toCollectionId
            row.cardSectionId = group
            row.orderKey = key
            row.updatedAt = Clock.System.now()
            Cards.put(row)
        }
    }

    override suspend fun reorder(collectionId: Uuid, cardSectionId: Uuid?, orderedIds: List<Uuid>) {
        db.write(Cards) {
            val members = Cards.find(Cards.byCollection) { Cards.collectionId eq collectionId }
                .filter { it.deletedAt == null && it.cardSectionId == cardSectionId }
                .sortedWith(compareBy({ it.orderKey }, { it.id.toString() }))
                .map { it.id }

            // The caller names the whole group; a card it left out (one saved while the sort was being
            // chosen) keeps its place at the end rather than being dropped.
            val sorted = orderedIds.filter { it in members }
            val ids = sorted + members.filterNot { it in sorted }

            // A sort is the one operation that really does re-place every card of the group, so here —
            // and only here — every row of the group is written. The keys are spread rather than
            // generated one after another, which keeps them short.
            val keys = OrderKey.sequence(null, null, ids.size)
            val now = Clock.System.now()
            ids.forEachIndexed { i, cardId ->
                val card = Cards.get(cardId) ?: return@forEachIndexed
                card.orderKey = keys[i]
                card.updatedAt = now
                Cards.put(card)
            }
        }
    }

    // BM25-ranked, best match first — a strict improvement over the old unranked multi-`LIKE` scan,
    // which only ever answered "does every word appear somewhere" with no notion of which hit is best.
    override suspend fun search(query: String): List<Card> {
        val trimmed = query.trim().split(' ').filter { it.isNotBlank() }.take(SEARCH_MAX_WORDS).joinToString(" ")
        if (trimmed.isBlank()) return emptyList()
        val ids = searchIndex.search(trimmed, k = SEARCH_RESULT_LIMIT).map { it.key }
        if (ids.isEmpty()) return emptyList()
        val byId = db.read(Cards) { ids.mapNotNull { Cards.get(it) } }.associateBy { it.id }
        // kromus's own ranking, not the store's: this is the one place row order does not come from an
        // index scan.
        return ids.mapNotNull { byId[it] }.filter { it.deletedAt == null }.map { it.toModel() }
    }

    /** The last key of a card group — the (collection, card section) a card is about to be appended to. */
    private suspend fun io.github.kidx.ReadScope.lastKeyOfGroup(collectionId: Uuid, cardSectionId: Uuid?): String? =
        Cards.find(Cards.byCollection) { Cards.collectionId eq collectionId }
            .filter { it.deletedAt == null && it.cardSectionId == cardSectionId }
            .maxByOrNull { it.orderKey }
            ?.orderKey
}

internal class KidxFaviconRepository(
    private val db: Database,
) : FaviconRepository {

    override suspend fun all(): Map<String, CachedIcon> = db.read(Favicons) {
        Favicons.all().associate { it.host to CachedIcon(it.dataUri, it.updatedAt) }
    }

    override suspend fun put(host: String, dataUri: String) {
        val row = FaviconRow().apply {
            this.host = host
            this.dataUri = dataUri
            this.updatedAt = Clock.System.now()
        }
        // One row per host is the whole invariant of the cache; `put` overwrites whatever was there.
        db.write(Favicons) { Favicons.put(row) }
    }

    override suspend fun remove(host: String) {
        db.write(Favicons) { Favicons.delete(host) }
    }
}

internal class KidxUsageRepository(
    private val db: Database,
) : UsageRepository {

    override suspend fun all(): List<UsageStat> = db.read(Usage) {
        Usage.all().filter { it.deletedAt == null }
    }.map { UsageStat(it.url, it.title, it.host, it.hits, it.lastUsedAt) }

    override suspend fun record(url: String, title: String) {
        // Read, add one, write back — all in one transaction, so two openings in the same instant
        // cannot both read the same count and each write it back plus one.
        db.write(Usage) {
            val existing = Usage.get(url)
            // A page that was forgotten and is now open again starts over. The old count is not what the
            // user asked to be rid of — the *suggestion* was — but bringing back the fifty visits they told
            // us to forget would put the page straight back at the top of the box, which is the same thing.
            val forgotten = existing?.deletedAt != null
            val row = UsageRow().apply {
                this.url = url
                // A page whose title is not known this time keeps the one it had: a card renamed to
                // nothing, or a tab still loading, should not blank out a name the user recognises.
                this.title = title.ifBlank { existing?.title ?: url }
                this.host = url.substringBefore('/')
                this.hits = if (forgotten) 1 else (existing?.hits ?: 0) + 1
                this.lastUsedAt = Clock.System.now()
                this.deletedAt = null
            }
            Usage.put(row)
        }
    }

    override suspend fun forget(url: String) {
        db.write(Usage, SyncState) {
            if (syncing()) {
                // A tombstone, like any other deletion: without it the other device — which still has the
                // page — would push it back up, and the suggestion the user just dismissed would return.
                val row = Usage.get(url) ?: return@write
                row.deletedAt = Clock.System.now()
                Usage.put(row)
            } else {
                Usage.delete(url)
            }
        }
    }
}

internal class KidxActionUsageRepository(
    private val db: Database,
) : ActionUsageRepository {

    override suspend fun all(): List<ActionStat> = db.read(ActionUsage) {
        ActionUsage.all()
    }.map { ActionStat(it.kind, it.hits, it.lastUsedAt) }

    override suspend fun record(kind: String) {
        // Read, add one, write back under one transaction — as with [KidxUsageRepository.record],
        // so two rows taken in the same instant cannot both write back the same count plus one.
        db.write(ActionUsage) {
            val existing = ActionUsage.get(kind)
            val row = ActionUsageRow().apply {
                this.kind = kind
                this.hits = (existing?.hits ?: 0) + 1
                this.lastUsedAt = Clock.System.now()
            }
            ActionUsage.put(row)
        }
    }
}
