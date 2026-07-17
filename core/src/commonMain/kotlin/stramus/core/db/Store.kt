@file:OptIn(ExperimentalUuidApi::class, DelicateKormiumApi::class)

package stramus.core.db

import io.github.kormium.DelicateKormiumApi
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.count
import io.github.kormium.eq
import io.github.kormium.inList
import io.github.kormium.isNotNull
import io.github.kormium.isNull
import io.github.kormium.none
import io.github.kormium.like
import io.github.kormium.or
import io.github.kormium.SuspendScope
import io.github.kormium.lt
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.crypto.hashPin
import stramus.core.crypto.randomSalt
import stramus.core.crypto.sha256HexBytes
import stramus.core.sync.DataUri
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
import stramus.core.repo.DeletedCardSection
import stramus.core.repo.DeletedCollection
import stramus.core.repo.DeletedSection
import stramus.core.repo.FaviconRepository
import stramus.core.repo.SectionRepository
import stramus.core.repo.UsageRepository
import stramus.core.repo.UsageStat

/** Everything the UI needs: the open database plus the repositories over it. */
class StramusStore internal constructor(
    val db: SuspendDatabase<StramusDb>,
    val sections: SectionRepository,
    val collections: CollectionRepository,
    val cardSections: CardSectionRepository,
    val cards: CardRepository,
    val favicons: FaviconRepository,
    val usage: UsageRepository,
    val actions: ActionUsageRepository,
)

/** Words past this in one query are dropped: each one is another LIKE over every card. */
private const val SEARCH_MAX_WORDS = 4

/** How long a tombstone is kept once the row is gone, so that a device offline for a while still hears. */
private val TOMBSTONE_RETENTION = 30.days

/**
 * Whether this database belongs to an account — which decides what a deletion *is*.
 *
 * Signed out, a deletion is a deletion: the row goes, as it always has, because a device that never syncs
 * would otherwise hoard the dead for ever. Signed in, it has to be a *thing that happened* — a tombstone —
 * or the other device, seeing only that a row is missing, would helpfully put it back.
 */
private suspend fun SuspendScope<StramusDb>.syncing(): Boolean =
    SyncState.findOne { where { SyncState.k eq "userId" } } != null

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
 * Brings [db] up to the current schema, migrating a database written by an earlier version of the app,
 * ensures the default section exists, seeds a first install from [seed], and returns the store over it.
 *
 * The engine underneath is the caller's: the app hands it the browser's (IndexedDB-backed wa-sqlite,
 * see the `openStramusStore(name)` beside this one), and a test hands it a file. Everything from here
 * down is Kormium's DSL and nothing else — which is what lets the migration of a user's database be
 * run, and checked, outside a browser.
 */
suspend fun openStramusStore(db: SuspendDatabase<StramusDb>, seed: StoreSeed = StoreSeed.Default): StramusStore {
    db.suspendTransaction {
        schemaTableDdl.forEach { Sections.execSql(it) }
        // Columns added by versions of the app that came after the table did (ignore if already
        // present). These run before the order-key migration below, which expects the full old shape.
        runCatching { Collections.execSql("""ALTER TABLE "collections" ADD COLUMN "sectionId" text NOT NULL DEFAULT ''""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "cardSectionId" text""") }
        runCatching { Sections.execSql("""ALTER TABLE "sections" ADD COLUMN "collapsed" integer NOT NULL DEFAULT 0""") }
        runCatching { CardSections.execSql("""ALTER TABLE "card_sections" ADD COLUMN "collapsed" integer NOT NULL DEFAULT 0""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "kind" text NOT NULL DEFAULT 'link'""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "content" text""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "mime" text""") }
        runCatching { Sections.execSql("""ALTER TABLE "sections" ADD COLUMN "pinSalt" text""") }
        runCatching { Sections.execSql("""ALTER TABLE "sections" ADD COLUMN "pinHash" text""") }
        runCatching { Collections.execSql("""ALTER TABLE "collections" ADD COLUMN "readOnly" integer NOT NULL DEFAULT 0""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "thumb" text""") }
        runCatching { Cards.execSql("""ALTER TABLE "cards" ADD COLUMN "blobSha" text""") }
        runCatching { Usage.execSql("""ALTER TABLE "usage" ADD COLUMN "deletedAt" text""") }
    }

    migrateToOrderKeys(db)

    // Left until after the migration: on a database still carrying integer positions, an index over
    // the column that replaces them cannot be built.
    db.suspendTransaction { schemaIndexDdl.forEach { Sections.execSql(it) } }

    // Nothing has ever been in this database — a first install, as opposed to one the user has emptied
    // out (a section always remains there, and a card or a collection may). Asked before the default
    // section is made, which is itself the first thing seeding puts in.
    val fresh = db.suspendAutocommit {
        Sections.count() == 0L && Collections.count() == 0L && Cards.count() == 0L
    }

    val sections = KormiumSectionRepository(db, seed.sectionTitle)
    val defaultId = sections.defaultSectionId()
    val cards = KormiumCardRepository(db)
    if (fresh) {
        // A section on its own can hold nothing and a collection on its own says nothing: the user
        // arrives at a card that tells them what the rest of it is for. All three are ordinary rows —
        // renameable, movable, deletable — not fixtures of the app.
        val welcome = insertCollection(db, seed.collectionTitle, defaultId)
        cards.addNote(welcome.id, seed.noteTitle, seed.noteBody)
    }

    db.suspendTransaction {
        // A collection whose section is not there — one written before sections existed, and so given
        // the empty string by the `ALTER TABLE` above — belongs to the default section.
        Collections.update {
            Collections.sectionId set defaultId
            where { Sections.none { Sections.id eq Collections.sectionId } }
        }

        // Un-group cards pointing at a card section of another collection: earlier builds moved a
        // card between collections without clearing its section, and such a card matched no group
        // and so was drawn nowhere. Ungrouped is the only place it can be shown.
        Cards.update(CardRow().apply { cardSectionId = null }) {
            where {
                CardSections.none {
                    (CardSections.id eq Cards.cardSectionId) and (CardSections.collectionId eq Cards.collectionId)
                } and Cards.cardSectionId.isNotNull()
            }
        }

        // Earlier builds held a file's bytes in cards.content, which put every file of a collection
        // into the page each time it was drawn and into every LIKE the search ran. Move them out;
        // the grid's preview (thumb) is regenerated from the bytes by the UI, once, on next start.
        val inlined = Cards.find { where { (Cards.kind eq CardKind.FILE.id) and Cards.content.isNotNull() } }
        inlined.forEach { card ->
            // The bytes may already be where they belong: a previous run of this could have been
            // interrupted between moving them out and clearing the column.
            if (CardBlobs.findOne { where { CardBlobs.cardId eq card.id } } == null) {
                CardBlobs.insert(CardBlobRow().apply { cardId = card.id; data = card.content!! })
            }
        }
        if (inlined.isNotEmpty()) {
            Cards.update(CardRow().apply { content = null }) {
                where { Cards.kind eq CardKind.FILE.id }
            }
        }
    }

    purgeTombstones(db)

    return StramusStore(
        db,
        sections,
        KormiumCollectionRepository(db, defaultId),
        KormiumCardSectionRepository(db),
        cards,
        KormiumFaviconRepository(db),
        KormiumUsageRepository(db),
        KormiumActionUsageRepository(db),
    )
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
 */
private suspend fun purgeTombstones(db: SuspendDatabase<StramusDb>) {
    db.suspendTransaction {
        val cutoff = if (syncing()) Clock.System.now() - TOMBSTONE_RETENTION else Clock.System.now()

        val dead = Cards.find { where { Cards.deletedAt lt cutoff } }.map { it.id }
        if (dead.isNotEmpty()) {
            CardBlobs.deleteWhere { where { CardBlobs.cardId inList dead } }
        }
        Cards.deleteWhere { where { Cards.deletedAt lt cutoff } }
        CardSections.deleteWhere { where { CardSections.deletedAt lt cutoff } }
        Collections.deleteWhere { where { Collections.deletedAt lt cutoff } }
        Sections.deleteWhere { where { Sections.deletedAt lt cutoff } }
        Usage.deleteWhere { where { Usage.deletedAt lt cutoff } }
    }
}

/**
 * Rewrite a database that still orders its rows by an integer `position` to order them by an
 * [OrderKey] instead (and give every row the `updatedAt`/`deletedAt` a synchronised row needs).
 *
 * The four tables are rebuilt rather than altered in place: SQLite cannot change a column's type, and
 * leaving the old `position` behind is not an option — it is `NOT NULL` with no default, so the first
 * card saved afterwards would be refused by a column no one uses any more.
 *
 * The keys are computed here, in Kotlin, from the order the rows already have, and then written back
 * per row: the user's order is preserved exactly, including the groups the old scheme kept contiguous
 * inside one collection-wide sequence. It all runs in one transaction, so a database that dies halfway
 * through this comes back as the database it was.
 */
private suspend fun migrateToOrderKeys(db: SuspendDatabase<StramusDb>) {
    // Reading a column that only the old schema has is the test for the old schema: on a database
    // already carrying order keys, this query names a column that is not there, and fails.
    val legacy = runCatching {
        db.suspendAutocommit { LegacySections.find { limit = 1 } }
    }.isSuccess
    if (!legacy) return

    val sections = db.suspendAutocommit { LegacySections.find { orderBy ASC LegacySections.position } }
    val collections = db.suspendAutocommit { LegacyCollections.find { orderBy ASC LegacyCollections.position } }
    val cardSections = db.suspendAutocommit { LegacyCardSections.find { orderBy ASC LegacyCardSections.position } }
    val cards = db.suspendAutocommit { LegacyCards.find { orderBy ASC LegacyCards.position } }

    // One key per row, siblings numbered together: sections across the sidebar, collections within
    // their section, card sections within their collection, cards within their group.
    val sectionKeys = keysFor(sections.map { it.id }) { null }
    val collectionKeys = keysFor(collections.map { it.id }) { id -> collections.first { it.id == id }.sectionId }
    val cardSectionKeys = keysFor(cardSections.map { it.id }) { id -> cardSections.first { it.id == id }.collectionId }
    val cardKeys = keysFor(cards.map { it.id }) { id ->
        val card = cards.first { it.id == id }
        card.collectionId to card.cardSectionId
    }

    val now = Clock.System.now().toString()

    db.suspendTransaction {
        // The indexes name the column that is about to go, and they would otherwise follow the old
        // tables into `_legacy` and be dropped with them, leaving the new tables unindexed.
        Sections.execSql("""DROP INDEX IF EXISTS "idx_cards_collection"""")
        Sections.execSql("""DROP INDEX IF EXISTS "idx_card_sections_collection"""")
        Sections.execSql("""DROP INDEX IF EXISTS "idx_collections_section"""")

        listOf("sections", "collections", "card_sections", "cards").forEach { table ->
            Sections.execSql("""ALTER TABLE "$table" RENAME TO "${table}_legacy"""")
        }
        schemaTableDdl.forEach { Sections.execSql(it) }

        Sections.execSql(
            """
            INSERT INTO "sections" ("id", "title", "orderKey", "deletable", "collapsed", "pinSalt", "pinHash", "updatedAt")
            SELECT "id", "title", '', "deletable", "collapsed", "pinSalt", "pinHash", '$now' FROM "sections_legacy"
            """.trimIndent(),
        )
        Sections.execSql(
            """
            INSERT INTO "collections" ("id", "sectionId", "title", "orderKey", "createdAt", "readOnly", "updatedAt")
            SELECT "id", "sectionId", "title", '', "createdAt", "readOnly", '$now' FROM "collections_legacy"
            """.trimIndent(),
        )
        Sections.execSql(
            """
            INSERT INTO "card_sections" ("id", "collectionId", "title", "description", "orderKey", "collapsed", "updatedAt")
            SELECT "id", "collectionId", "title", "description", '', "collapsed", '$now' FROM "card_sections_legacy"
            """.trimIndent(),
        )
        Sections.execSql(
            """
            INSERT INTO "cards" (
                "id", "collectionId", "cardSectionId", "kind", "title", "url", "favicon", "content",
                "thumb", "mime", "orderKey", "createdAt", "updatedAt"
            )
            SELECT
                "id", "collectionId", "cardSectionId", "kind", "title", "url", "favicon", "content",
                "thumb", "mime", '', "createdAt", '$now'
            FROM "cards_legacy"
            """.trimIndent(),
        )

        sectionKeys.forEach { (id, key) ->
            Sections.update(SectionRow().apply { orderKey = key }) { where { Sections.id eq id } }
        }
        collectionKeys.forEach { (id, key) ->
            Collections.update(CollectionRow().apply { orderKey = key }) { where { Collections.id eq id } }
        }
        cardSectionKeys.forEach { (id, key) ->
            CardSections.update(CardSectionRow().apply { orderKey = key }) { where { CardSections.id eq id } }
        }
        cardKeys.forEach { (id, key) ->
            Cards.update(CardRow().apply { orderKey = key }) { where { Cards.id eq id } }
        }

        listOf("sections", "collections", "card_sections", "cards").forEach { table ->
            Sections.execSql("""DROP TABLE "${table}_legacy"""")
        }
    }
}

/**
 * An order key for each of [ids], which arrive in the order the old schema put them in. [groupOf] says
 * which rows are siblings — rows of one group are keyed against each other and against no one else.
 *
 * The keys of a group are spread rather than generated one after another, so a collection of two
 * hundred cards does not end with keys two hundred characters long.
 */
private fun <K> keysFor(ids: List<Uuid>, groupOf: (Uuid) -> K): Map<Uuid, String> {
    val groups = ids.groupBy(groupOf)
    return buildMap {
        groups.values.forEach { members ->
            OrderKey.sequence(null, null, members.size).forEachIndexed { i, key -> put(members[i], key) }
        }
    }
}

private fun SectionRow.toModel() = Section(id, title, orderKey, deletable != 0, collapsed != 0, pinHash != null)
private fun CollectionRow.toModel() = Collection(id, sectionId, title, orderKey, createdAt, readOnly != 0)
private fun CardSectionRow.toModel() = CardSection(id, collectionId, title, description, orderKey, collapsed != 0)
private fun CardRow.toModel() = Card(
    id, collectionId, cardSectionId, CardKind.from(kind), title, url, favicon, content, thumb, mime, blobSha,
    orderKey, createdAt,
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
 * [KormiumSectionRepository.create], which gives every new section a collection of its own name.
 */
private suspend fun insertCollection(db: SuspendDatabase<StramusDb>, title: String, sectionId: Uuid): Collection {
    val last = db.suspendAutocommit {
        Collections.find {
            where { (Collections.sectionId eq sectionId) and Collections.deletedAt.isNull() }
            orderBy DESC Collections.orderKey
            limit = 1
        }.firstOrNull()?.orderKey
    }
    val row = CollectionRow().apply {
        this.id = Uuid.random()
        this.sectionId = sectionId
        this.title = title
        this.orderKey = appendKey(last)
        this.createdAt = Clock.System.now()
        this.readOnly = 0
        this.updatedAt = Clock.System.now()
    }
    db.suspendTransaction { Collections.insert(row) }
    return row.toModel()
}

/**
 * Take a collection out of the database whole — its card sections, its cards and their file bytes —
 * and hand back everything needed to put it back ([restoreCollection]). Null if there is no such
 * collection. Shared with section deletion, which does this to each collection of the section.
 */
private suspend fun deleteCollection(db: SuspendDatabase<StramusDb>, id: Uuid): DeletedCollection? {
    val collection = db.suspendAutocommit { Collections.findOne { where { Collections.id eq id } } } ?: return null
    val cardSections = db.suspendAutocommit {
        CardSections.find {
            where { CardSections.collectionId eq id }
            orderBy ASC CardSections.orderKey
        }
    }
    val cards = db.suspendAutocommit {
        Cards.find {
            where { Cards.collectionId eq id }
            orderBy ASC Cards.orderKey
        }
    }
    // The bytes are read out before they are deleted: an undone deletion has to open the file again.
    val blobs = db.suspendAutocommit {
        cards.mapNotNull { card ->
            CardBlobs.findOne { where { CardBlobs.cardId eq card.id } }?.let { card.id to it.data }
        }
    }.toMap()

    db.suspendTransaction {
        val now = Clock.System.now()
        if (syncing()) {
            // Tombstones, all the way down: the other device has these cards, and has to be told they
            // went. The file bytes stay until the tombstones are swept — an undo has to open them again.
            Cards.update(CardRow().apply { deletedAt = now; updatedAt = now }) { where { Cards.collectionId eq id } }
            CardSections.update(
                CardSectionRow().apply { deletedAt = now; updatedAt = now },
            ) { where { CardSections.collectionId eq id } }
            Collections.update(
                CollectionRow().apply { deletedAt = now; updatedAt = now },
            ) { where { Collections.id eq id } }
        } else {
            // The cards are already read, so their blobs are named directly rather than by a subquery.
            if (cards.isNotEmpty()) {
                CardBlobs.deleteWhere { where { CardBlobs.cardId inList cards.map { it.id } } }
            }
            Cards.deleteWhere { where { Cards.collectionId eq id } }
            CardSections.deleteWhere { where { CardSections.collectionId eq id } }
            Collections.deleteWhere { where { Collections.id eq id } }
        }
    }
    return DeletedCollection(collection.toModel(), cardSections.map { it.toModel() }, cards.map { it.toModel() }, blobs)
}

/** The undo of [deleteCollection]: every row back, with the id and the place it had. */
private suspend fun restoreCollection(db: SuspendDatabase<StramusDb>, deleted: DeletedCollection) {
    db.suspendTransaction {
        // The rows may still be there as tombstones (a signed-in database deletes by marking), so each one
        // is replaced rather than inserted. What comes back is the row that went, with its id and its
        // place — and a fresh `updatedAt`, which is what makes the undo beat the deletion on the server.
        Collections.deleteWhere { where { Collections.id eq deleted.collection.id } }
        Collections.insert(deleted.collection.toRow())
        deleted.cardSections.forEach {
            CardSections.deleteWhere { where { CardSections.id eq it.id } }
            CardSections.insert(it.toRow())
        }
        deleted.cards.forEach { card ->
            Cards.deleteWhere { where { Cards.id eq card.id } }
            Cards.insert(card.toRow())
            deleted.blobs[card.id]?.let { bytes ->
                CardBlobs.insert(CardBlobRow().apply { this.cardId = card.id; this.data = bytes })
            }
        }
    }
}

internal class KormiumSectionRepository(
    private val db: SuspendDatabase<StramusDb>,
    /** The name the default section is created under, in the language the app was first opened in. */
    private val defaultTitle: String,
) : SectionRepository {

    override suspend fun all(): List<Section> = db.suspendAutocommit {
        Sections.find {
            where { Sections.deletedAt.isNull() }
            orderBy ASC Sections.orderKey
            orderBy ASC Sections.id
        }.map { it.toModel() }
    }

    override suspend fun create(title: String): Section {
        val last = db.suspendAutocommit {
            Sections.find {
                where { Sections.deletedAt.isNull() }
                orderBy DESC Sections.orderKey
                limit = 1
            }.firstOrNull()?.orderKey
        }
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
        db.suspendTransaction { Sections.insert(row) }
        // A section with no collection in it can hold nothing, so it comes with one, named after it.
        insertCollection(db, title, row.id)
        return row.toModel()
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Sections.update(
                SectionRow().apply { this.title = title; this.updatedAt = Clock.System.now() },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.suspendTransaction {
            Sections.update(
                SectionRow().apply {
                    this.collapsed = if (collapsed) 1 else 0
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val siblings = Sections.find {
                where { Sections.deletedAt.isNull() }
                orderBy ASC Sections.orderKey
                orderBy ASC Sections.id
            }.filter { it.id != id }
            if (Sections.findOne { where { Sections.id eq id } } == null) return@suspendTransaction

            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            Sections.update(
                SectionRow().apply { orderKey = key; updatedAt = Clock.System.now() },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid): DeletedSection? {
        if (id == defaultSectionId()) return null // the default section is not deletable
        val row = db.suspendAutocommit { Sections.findOne { where { Sections.id eq id } } } ?: return null

        // A section owns its collections: they go with it, rather than being tipped into the default
        // section, where they would be one more thing for the user to clear away. Nothing is lost by
        // it — everything taken out here goes into the snapshot, and an undo puts all of it back.
        val collectionIds = db.suspendAutocommit {
            Collections.find {
                where { Collections.sectionId eq id }
                orderBy ASC Collections.orderKey
            }
        }.map { it.id }
        val collections = collectionIds.mapNotNull { deleteCollection(db, it) }

        db.suspendTransaction {
            val now = Clock.System.now()
            if (syncing()) {
                Sections.update(SectionRow().apply { deletedAt = now; updatedAt = now }) { where { Sections.id eq id } }
            } else {
                Sections.deleteWhere { where { Sections.id eq id } }
            }
        }
        return DeletedSection(row.toModel(), collections, row.pinSalt, row.pinHash)
    }

    override suspend fun restore(deleted: DeletedSection) {
        val row = SectionRow().apply {
            this.id = deleted.section.id
            this.title = deleted.section.title
            this.orderKey = deleted.section.orderKey
            this.deletable = if (deleted.section.deletable) 1 else 0
            this.collapsed = if (deleted.section.collapsed) 1 else 0
            // The PIN comes back with the section: an undone deletion must not be a way past a lock.
            this.pinSalt = deleted.pinSalt
            this.pinHash = deleted.pinHash
            this.updatedAt = Clock.System.now()
        }
        db.suspendTransaction {
            Sections.deleteWhere { where { Sections.id eq row.id } }
            Sections.insert(row)
        }
        deleted.collections.forEach { restoreCollection(db, it) }
    }

    /** Returns the default section id, creating the non-deletable default section if absent. */
    override suspend fun defaultSectionId(): Uuid {
        val existing = db.suspendAutocommit { Sections.all() }.firstOrNull { it.deletable == 0 }
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
        db.suspendTransaction { Sections.insert(row) }
        return row.id
    }

    override suspend fun setPin(id: Uuid, pin: String) {
        val salt = randomSalt()
        val hash = hashPin(pin, salt)
        db.suspendTransaction {
            Sections.update(
                SectionRow().apply {
                    this.pinSalt = salt
                    this.pinHash = hash
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun clearPin(id: Uuid) {
        db.suspendTransaction {
            Sections.update(
                SectionRow().apply { pinSalt = null; pinHash = null; updatedAt = Clock.System.now() },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun verifyPin(id: Uuid, pin: String): Boolean {
        val row = db.suspendAutocommit { Sections.findOne { where { Sections.id eq id } } } ?: return false
        val salt = row.pinSalt
        val hash = row.pinHash ?: return true // not locked: there is nothing to get wrong
        return salt != null && hashPin(pin, salt) == hash
    }
}

internal class KormiumCollectionRepository(
    private val db: SuspendDatabase<StramusDb>,
    private val defaultSectionId: Uuid,
) : CollectionRepository {

    override suspend fun all(): List<Collection> = db.suspendAutocommit {
        // Ordered by key, which orders each section's collections among themselves; collections of two
        // different sections do not compare, and the UI never asks them to — it walks the sections in
        // their own order and takes the collections of each.
        Collections.find {
            where { Collections.deletedAt.isNull() }
            orderBy ASC Collections.orderKey
            orderBy ASC Collections.id
        }.map { it.toModel() }
    }

    override suspend fun create(title: String, sectionId: Uuid): Collection = insertCollection(db, title, sectionId)

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Collections.update(
                CollectionRow().apply { this.title = title; this.updatedAt = Clock.System.now() },
            ) { where { Collections.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid): DeletedCollection? = deleteCollection(db, id)

    override suspend fun restore(deleted: DeletedCollection) = restoreCollection(db, deleted)

    override suspend fun moveToSection(id: Uuid, sectionId: Uuid) {
        // The end of the section it lands in — a key from the section it came from would name a place
        // among rows it has never been ordered against.
        val last = db.suspendAutocommit {
            Collections.find {
                where { (Collections.sectionId eq sectionId) and Collections.deletedAt.isNull() }
                orderBy DESC Collections.orderKey
                limit = 1
            }.firstOrNull()?.orderKey
        }
        db.suspendTransaction {
            Collections.update(
                CollectionRow().apply {
                    this.sectionId = sectionId
                    this.orderKey = appendKey(last)
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Collections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, toSectionId: Uuid, newIndex: Int) {
        db.suspendTransaction {
            if (Collections.findOne { where { Collections.id eq id } } == null) return@suspendTransaction

            val siblings = Collections.find {
                where { (Collections.sectionId eq toSectionId) and Collections.deletedAt.isNull() }
                orderBy ASC Collections.orderKey
                orderBy ASC Collections.id
            }.filter { it.id != id }

            // Section and place at once, and nothing else touched: where this used to renumber every
            // collection of every section, it now writes the one row that moved.
            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            Collections.update(
                CollectionRow().apply {
                    sectionId = toSectionId
                    orderKey = key
                    updatedAt = Clock.System.now()
                },
            ) { where { Collections.id eq id } }
        }
    }

    override suspend fun setReadOnly(id: Uuid, readOnly: Boolean) {
        db.suspendTransaction {
            Collections.update(
                CollectionRow().apply {
                    this.readOnly = if (readOnly) 1 else 0
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Collections.id eq id } }
        }
    }
}

internal class KormiumCardSectionRepository(
    private val db: SuspendDatabase<StramusDb>,
) : CardSectionRepository {

    override suspend fun byCollection(collectionId: Uuid): List<CardSection> = db.suspendAutocommit {
        CardSections.find {
            where { (CardSections.collectionId eq collectionId) and CardSections.deletedAt.isNull() }
            orderBy ASC CardSections.orderKey
            orderBy ASC CardSections.id
        }.map { it.toModel() }
    }

    override suspend fun create(collectionId: Uuid, title: String, description: String?): CardSection {
        val last = db.suspendAutocommit {
            CardSections.find {
                where { (CardSections.collectionId eq collectionId) and CardSections.deletedAt.isNull() }
                orderBy DESC CardSections.orderKey
                limit = 1
            }.firstOrNull()?.orderKey
        }
        val row = CardSectionRow().apply {
            this.id = Uuid.random()
            this.collectionId = collectionId
            this.title = title
            this.description = description
            this.orderKey = appendKey(last)
            this.collapsed = 0
            this.updatedAt = Clock.System.now()
        }
        db.suspendTransaction { CardSections.insert(row) }
        return row.toModel()
    }

    override suspend fun update(id: Uuid, title: String, description: String?) {
        db.suspendTransaction {
            CardSections.update(
                CardSectionRow().apply {
                    this.title = title
                    this.description = description
                    this.updatedAt = Clock.System.now()
                },
            ) { where { CardSections.id eq id } }
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.suspendTransaction {
            CardSections.update(
                CardSectionRow().apply {
                    this.collapsed = if (collapsed) 1 else 0
                    this.updatedAt = Clock.System.now()
                },
            ) { where { CardSections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val moving = CardSections.findOne { where { CardSections.id eq id } } ?: return@suspendTransaction
            val siblings = CardSections.find {
                where { (CardSections.collectionId eq moving.collectionId) and CardSections.deletedAt.isNull() }
                orderBy ASC CardSections.orderKey
                orderBy ASC CardSections.id
            }.filter { it.id != id }

            val key = keyAt(siblings.map { it.orderKey }, newIndex)
            CardSections.update(
                CardSectionRow().apply { orderKey = key; updatedAt = Clock.System.now() },
            ) { where { CardSections.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid): DeletedCardSection? {
        val row = db.suspendAutocommit { CardSections.findOne { where { CardSections.id eq id } } } ?: return null
        val cardIds = db.suspendAutocommit {
            Cards.find {
                where { Cards.cardSectionId eq id }
                orderBy ASC Cards.orderKey
            }
        }.map { it.id }
        db.suspendTransaction {
            // Detach the section's cards (they become ungrouped) before removing the section.
            Cards.update(
                CardRow().apply { cardSectionId = null; updatedAt = Clock.System.now() },
            ) { where { Cards.cardSectionId eq id } }

            val now = Clock.System.now()
            if (syncing()) {
                CardSections.update(
                    CardSectionRow().apply { deletedAt = now; updatedAt = now },
                ) { where { CardSections.id eq id } }
            } else {
                CardSections.deleteWhere { where { CardSections.id eq id } }
            }
        }
        return DeletedCardSection(row.toModel(), cardIds)
    }

    override suspend fun restore(deleted: DeletedCardSection) {
        db.suspendTransaction {
            CardSections.deleteWhere { where { CardSections.id eq deleted.cardSection.id } }
            CardSections.insert(deleted.cardSection.toRow())
            // The cards were left behind, ungrouped; the ones that were in this section rejoin it.
            // Any that the user has since moved elsewhere are simply no longer there to be found.
            deleted.cardIds.forEach { cardId ->
                Cards.update(
                    CardRow().apply {
                        this.cardSectionId = deleted.cardSection.id
                        this.updatedAt = Clock.System.now()
                    },
                ) { where { Cards.id eq cardId } }
            }
        }
    }
}

internal class KormiumCardRepository(
    private val db: SuspendDatabase<StramusDb>,
) : CardRepository {

    override suspend fun byCollection(collectionId: Uuid): List<Card> = db.suspendAutocommit {
        // Every card of the collection, ordered by key. Keys are per group, so this puts each group's
        // cards in the right order among themselves — which is all the UI reads, since it draws the
        // ungrouped cards, then each card section in turn.
        Cards.find {
            where { (Cards.collectionId eq collectionId) and Cards.deletedAt.isNull() }
            orderBy ASC Cards.orderKey
            orderBy ASC Cards.id
        }.map { it.toModel() }
    }

    override suspend fun count(collectionId: Uuid): Int = db.suspendAutocommit {
        Cards.count { where { (Cards.collectionId eq collectionId) and Cards.deletedAt.isNull() } }
    }.toInt()

    override suspend fun add(collectionId: Uuid, title: String, url: String, favicon: String?, cardSectionId: Uuid?): Card =
        insert(collectionId, cardSectionId, CardKind.LINK, title, url, favicon, content = null, mime = null)

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
    ): Card {
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
            this.orderKey = appendKey(lastKeyOfGroup(db, collectionId, cardSectionId))
            this.createdAt = Clock.System.now()
            this.updatedAt = Clock.System.now()
        }
        db.suspendTransaction {
            Cards.insert(row)
            // The card and its bytes land together: a card whose blob is missing would open empty.
            if (blob != null) {
                CardBlobs.insert(CardBlobRow().apply { this.cardId = row.id; this.data = blob })
            }
        }
        return row.toModel()
    }

    override suspend fun blob(id: Uuid): String? = db.suspendAutocommit {
        CardBlobs.findOne { where { CardBlobs.cardId eq id } }?.data
    }

    override suspend fun setThumb(id: Uuid, thumb: String) {
        db.suspendTransaction {
            Cards.update(
                CardRow().apply { this.thumb = thumb; this.updatedAt = Clock.System.now() },
            ) { where { Cards.id eq id } }
        }
    }

    override suspend fun imageFilesWithoutThumb(): List<Card> = db.suspendAutocommit {
        Cards.find { where { (Cards.kind eq CardKind.FILE.id) and Cards.deletedAt.isNull() } }.map { it.toModel() }
    }.filter { it.thumb == null && (it.mime ?: "").startsWith("image/") }

    override suspend fun updateNote(id: Uuid, title: String, content: String) {
        db.suspendTransaction {
            Cards.update(
                CardRow().apply {
                    this.title = title
                    this.content = content
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Cards.id eq id } }
        }
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Cards.update(
                CardRow().apply { this.title = title; this.updatedAt = Clock.System.now() },
            ) { where { Cards.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid) {
        db.suspendTransaction {
            if (syncing()) {
                // A tombstone, and the bytes left where they are: an undo has to be able to open the file
                // again, and the sweep below takes both once the deletion is old enough to be everywhere.
                val now = Clock.System.now()
                Cards.update(CardRow().apply { deletedAt = now; updatedAt = now }) { where { Cards.id eq id } }
            } else {
                CardBlobs.deleteWhere { where { CardBlobs.cardId eq id } }
                Cards.deleteWhere { where { Cards.id eq id } }
            }
        }
    }

    override suspend fun move(id: Uuid, toCollectionId: Uuid, cardSectionId: Uuid?, newIndex: Int) {
        db.suspendTransaction {
            if (Cards.findOne { where { Cards.id eq id } } == null) return@suspendTransaction

            // A card can only join a section of the collection it lands in; anything else (a stale
            // section from the collection it came from) would hide it from every group.
            val groups = CardSections.find {
                where { (CardSections.collectionId eq toCollectionId) and CardSections.deletedAt.isNull() }
            }.map { it.id }
            val group = cardSectionId?.takeIf { it in groups }

            val siblings = Cards.find {
                where {
                    val inGroup = if (group == null) Cards.cardSectionId.isNull() else (Cards.cardSectionId eq group)
                    (Cards.collectionId eq toCollectionId) and inGroup and Cards.deletedAt.isNull()
                }
                orderBy ASC Cards.orderKey
                orderBy ASC Cards.id
            }.filter { it.id != id }

            val key = keyAt(siblings.map { it.orderKey }, newIndex)

            // One row, one write — collection, group and place together. `group` may be null, and a
            // patch row says so: an assigned null writes NULL, where an unassigned column is left
            // alone. A card dragged out of a group has to end up ungrouped, and this is how it does.
            Cards.update(
                CardRow().apply {
                    this.collectionId = toCollectionId
                    this.cardSectionId = group
                    this.orderKey = key
                    this.updatedAt = Clock.System.now()
                },
            ) { where { Cards.id eq id } }
        }
    }

    override suspend fun reorder(collectionId: Uuid, cardSectionId: Uuid?, orderedIds: List<Uuid>) {
        db.suspendTransaction {
            val members = Cards.find {
                where {
                    val inGroup =
                        if (cardSectionId == null) Cards.cardSectionId.isNull() else (Cards.cardSectionId eq cardSectionId)
                    (Cards.collectionId eq collectionId) and inGroup and Cards.deletedAt.isNull()
                }
                orderBy ASC Cards.orderKey
                orderBy ASC Cards.id
            }.map { it.id }

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
                Cards.update(
                    CardRow().apply { orderKey = keys[i]; updatedAt = now },
                ) { where { Cards.id eq cardId } }
            }
        }
    }

    // Every word has to be there, but not side by side, and not all in the same field: "kotlin flow"
    // finds the card titled "Flow — Kotlin docs", and finds a note that mentions flows on a page of
    // kotlinlang.org. A single LIKE over the whole query would find neither — and the search box is
    // where a user types two words without thinking about it.
    override suspend fun search(query: String): List<Card> = db.suspendAutocommit {
        val words = query.trim().split(' ').filter { it.isNotBlank() }.take(SEARCH_MAX_WORDS)
        if (words.isEmpty()) {
            emptyList()
        } else {
            Cards.find {
                where {
                    words
                        .map { word ->
                            val pattern = "%$word%"
                            (Cards.title like pattern) or (Cards.url like pattern) or (Cards.content like pattern)
                        }
                        .reduce { all, word -> all and word }
                        .and(Cards.deletedAt.isNull())
                }
                orderBy ASC Cards.title
            }.map { it.toModel() }
        }
    }
}

/** The last key of a card group — the (collection, card section) a card is about to be appended to. */
private suspend fun lastKeyOfGroup(
    db: SuspendDatabase<StramusDb>,
    collectionId: Uuid,
    cardSectionId: Uuid?,
): String? = db.suspendAutocommit {
    Cards.find {
        where {
            val inGroup =
                if (cardSectionId == null) Cards.cardSectionId.isNull() else (Cards.cardSectionId eq cardSectionId)
            (Cards.collectionId eq collectionId) and inGroup and Cards.deletedAt.isNull()
        }
        orderBy DESC Cards.orderKey
        limit = 1
    }.firstOrNull()?.orderKey
}

internal class KormiumFaviconRepository(
    private val db: SuspendDatabase<StramusDb>,
) : FaviconRepository {

    override suspend fun all(): Map<String, CachedIcon> = db.suspendAutocommit {
        Favicons.all().associate { it.host to CachedIcon(it.dataUri, it.updatedAt) }
    }

    override suspend fun put(host: String, dataUri: String) {
        val row = FaviconRow().apply {
            this.host = host
            this.dataUri = dataUri
            this.updatedAt = Clock.System.now()
        }
        // Replace rather than update: the row for a host may or may not exist, and one row per host
        // is the whole invariant of the cache.
        db.suspendTransaction {
            Favicons.deleteWhere { where { Favicons.host eq host } }
            Favicons.insert(row)
        }
    }
}

internal class KormiumUsageRepository(
    private val db: SuspendDatabase<StramusDb>,
) : UsageRepository {

    override suspend fun all(): List<UsageStat> = db.suspendAutocommit {
        Usage.find { where { Usage.deletedAt.isNull() } }
            .map { UsageStat(it.url, it.title, it.host, it.hits, it.lastUsedAt) }
    }

    override suspend fun record(url: String, title: String) {
        // Read, add one, write back — all under one transaction, so two openings in the same instant
        // cannot both read the same count and each write it back plus one.
        db.suspendTransaction {
            val existing = Usage.find { where { Usage.url eq url } }.firstOrNull()
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
            Usage.deleteWhere { where { Usage.url eq url } }
            Usage.insert(row)
        }
    }

    override suspend fun forget(url: String) {
        db.suspendTransaction {
            if (syncing()) {
                // A tombstone, like any other deletion: without it the other device — which still has the
                // page — would push it back up, and the suggestion the user just dismissed would return.
                Usage.update(UsageRow().apply { deletedAt = Clock.System.now() }) { where { Usage.url eq url } }
            } else {
                Usage.deleteWhere { where { Usage.url eq url } }
            }
        }
    }
}

internal class KormiumActionUsageRepository(
    private val db: SuspendDatabase<StramusDb>,
) : ActionUsageRepository {

    override suspend fun all(): List<ActionStat> = db.suspendAutocommit {
        ActionUsage.all().map { ActionStat(it.kind, it.hits, it.lastUsedAt) }
    }

    override suspend fun record(kind: String) {
        // Read, add one, write back under one transaction — as with [KormiumUsageRepository.record],
        // so two rows taken in the same instant cannot both write back the same count plus one.
        db.suspendTransaction {
            val existing = ActionUsage.find { where { ActionUsage.kind eq kind } }.firstOrNull()
            val row = ActionUsageRow().apply {
                this.kind = kind
                this.hits = (existing?.hits ?: 0) + 1
                this.lastUsedAt = Clock.System.now()
            }
            ActionUsage.deleteWhere { where { ActionUsage.kind eq kind } }
            ActionUsage.insert(row)
        }
    }
}
