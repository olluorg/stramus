@file:OptIn(ExperimentalUuidApi::class, DelicateKormiumApi::class)

package stramus.core.db

import io.github.kormium.DelicateKormiumApi
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.count
import io.github.kormium.eq
import io.github.kormium.like
import io.github.kormium.or
import io.github.kormium.sqlite.js.createSqliteJsDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.crypto.hashPin
import stramus.core.crypto.randomSalt
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
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
 * Opens the IndexedDB-persisted stramus database (via Kormium's Kotlin/JS wa-sqlite engine), creates
 * the schema if needed, ensures the default section exists, seeds a first install from [seed], and
 * returns the store. [name] is the IndexedDB database name.
 */
suspend fun openStramusStore(name: String = "stramus", seed: StoreSeed = StoreSeed.Default): StramusStore {
    val db: SuspendDatabase<StramusDb> = createSqliteJsDatabase(name)
    db.suspendTransaction {
        schemaDdl.forEach { Sections.execSql(it) }
        // Migrate databases created before these columns existed (ignore if already present).
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
    }

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
        // Attach any collections without a section (fresh migration) to the default one.
        Collections.execSql("""UPDATE "collections" SET "sectionId" = '$defaultId' WHERE "sectionId" IS NULL OR "sectionId" = ''""")
        // Un-group cards pointing at a card section of another collection: earlier builds moved a
        // card between collections without clearing its section, and such a card matched no group
        // and so was drawn nowhere. Ungrouped is the only place it can be shown.
        Cards.execSql(
            """
            UPDATE "cards" SET "cardSectionId" = NULL
            WHERE "cardSectionId" IS NOT NULL AND "cardSectionId" NOT IN (
                SELECT "id" FROM "card_sections" WHERE "collectionId" = "cards"."collectionId"
            )
            """.trimIndent(),
        )
        // Earlier builds held a file's bytes in cards.content, which put every file of a collection
        // into the page each time it was drawn and into every LIKE the search ran. Move them out;
        // the grid's preview (thumb) is regenerated from the bytes by the UI, once, on next start.
        Cards.execSql(
            """
            INSERT OR IGNORE INTO "card_blobs" ("cardId", "data")
            SELECT "id", "content" FROM "cards" WHERE "kind" = 'file' AND "content" IS NOT NULL
            """.trimIndent(),
        )
        Cards.execSql("""UPDATE "cards" SET "content" = NULL WHERE "kind" = 'file'""")
    }

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

private fun SectionRow.toModel() = Section(id, title, position, deletable != 0, collapsed != 0, pinHash != null)
private fun CollectionRow.toModel() = Collection(id, sectionId, title, position, createdAt, readOnly != 0)
private fun CardSectionRow.toModel() = CardSection(id, collectionId, title, description, position, collapsed != 0)
private fun CardRow.toModel() =
    Card(id, collectionId, cardSectionId, CardKind.from(kind), title, url, favicon, content, thumb, mime, position, createdAt)

// The way back from a model to the row it came from — what an undo writes. A restored row keeps its
// id and its position, so what comes back is the thing that was deleted and not a copy of it.
private fun Collection.toRow() = CollectionRow().apply {
    this.id = this@toRow.id
    this.sectionId = this@toRow.sectionId
    this.title = this@toRow.title
    this.position = this@toRow.position
    this.createdAt = this@toRow.createdAt
    this.readOnly = if (this@toRow.readOnly) 1 else 0
}

private fun CardSection.toRow() = CardSectionRow().apply {
    this.id = this@toRow.id
    this.collectionId = this@toRow.collectionId
    this.title = this@toRow.title
    this.description = this@toRow.description
    this.position = this@toRow.position
    this.collapsed = if (this@toRow.collapsed) 1 else 0
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
    this.position = this@toRow.position
    this.createdAt = this@toRow.createdAt
}

/**
 * Insert a collection at the end of the sidebar's order. Shared by the collection repository and by
 * [KormiumSectionRepository.create], which gives every new section a collection of its own name.
 */
private suspend fun insertCollection(db: SuspendDatabase<StramusDb>, title: String, sectionId: Uuid): Collection {
    val row = CollectionRow().apply {
        this.id = Uuid.random()
        this.sectionId = sectionId
        this.title = title
        this.position = db.suspendAutocommit { Collections.count() }.toInt()
        this.createdAt = Clock.System.now()
        this.readOnly = 0
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
            orderBy ASC CardSections.position
        }
    }
    val cards = db.suspendAutocommit {
        Cards.find {
            where { Cards.collectionId eq id }
            orderBy ASC Cards.position
        }
    }
    // The bytes are read out before they are deleted: an undone deletion has to open the file again.
    val blobs = db.suspendAutocommit {
        cards.mapNotNull { card ->
            CardBlobs.findOne { where { CardBlobs.cardId eq card.id } }?.let { card.id to it.data }
        }
    }.toMap()

    db.suspendTransaction {
        CardBlobs.execSql(
            """DELETE FROM "card_blobs" WHERE "cardId" IN (SELECT "id" FROM "cards" WHERE "collectionId" = '$id')""",
        )
        Cards.deleteWhere { where { Cards.collectionId eq id } }
        CardSections.deleteWhere { where { CardSections.collectionId eq id } }
        Collections.deleteWhere { where { Collections.id eq id } }
    }
    return DeletedCollection(collection.toModel(), cardSections.map { it.toModel() }, cards.map { it.toModel() }, blobs)
}

/** The undo of [deleteCollection]: every row back, with the id and the place it had. */
private suspend fun restoreCollection(db: SuspendDatabase<StramusDb>, deleted: DeletedCollection) {
    db.suspendTransaction {
        Collections.insert(deleted.collection.toRow())
        deleted.cardSections.forEach { CardSections.insert(it.toRow()) }
        deleted.cards.forEach { card ->
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
        Sections.find { orderBy ASC Sections.position }.map { it.toModel() }
    }

    override suspend fun create(title: String): Section {
        val row = SectionRow().apply {
            this.id = Uuid.random()
            this.title = title
            this.position = db.suspendAutocommit { Sections.count() }.toInt()
            this.deletable = 1
            this.collapsed = 0
            this.pinSalt = null
            this.pinHash = null
        }
        db.suspendTransaction { Sections.insert(row) }
        // A section with no collection in it can hold nothing, so it comes with one, named after it.
        insertCollection(db, title, row.id)
        return row.toModel()
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Sections.update(SectionRow().apply { this.title = title }) { where { Sections.id eq id } }
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.suspendTransaction {
            Sections.update(SectionRow().apply { this.collapsed = if (collapsed) 1 else 0 }) { where { Sections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val order = Sections.find { orderBy ASC Sections.position }.map { it.id }.toMutableList()
            if (!order.remove(id)) return@suspendTransaction
            order.add(newIndex.coerceIn(0, order.size), id)
            order.forEachIndexed { i, sectionId ->
                Sections.update(SectionRow().apply { position = i }) { where { Sections.id eq sectionId } }
            }
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
                orderBy ASC Collections.position
            }
        }.map { it.id }
        val collections = collectionIds.mapNotNull { deleteCollection(db, it) }

        db.suspendTransaction { Sections.deleteWhere { where { Sections.id eq id } } }
        return DeletedSection(row.toModel(), collections, row.pinSalt, row.pinHash)
    }

    override suspend fun restore(deleted: DeletedSection) {
        val row = SectionRow().apply {
            this.id = deleted.section.id
            this.title = deleted.section.title
            this.position = deleted.section.position
            this.deletable = if (deleted.section.deletable) 1 else 0
            this.collapsed = if (deleted.section.collapsed) 1 else 0
            // The PIN comes back with the section: an undone deletion must not be a way past a lock.
            this.pinSalt = deleted.pinSalt
            this.pinHash = deleted.pinHash
        }
        db.suspendTransaction { Sections.insert(row) }
        deleted.collections.forEach { restoreCollection(db, it) }
    }

    /** Returns the default section id, creating the non-deletable default section if absent. */
    override suspend fun defaultSectionId(): Uuid {
        val existing = db.suspendAutocommit { Sections.all() }.firstOrNull { it.deletable == 0 }
        if (existing != null) return existing.id
        val row = SectionRow().apply {
            this.id = Uuid.random()
            this.title = defaultTitle
            this.position = 0
            this.deletable = 0
            this.collapsed = 0
            this.pinSalt = null
            this.pinHash = null
        }
        db.suspendTransaction { Sections.insert(row) }
        return row.id
    }

    override suspend fun setPin(id: Uuid, pin: String) {
        val salt = randomSalt()
        val hash = hashPin(pin, salt)
        db.suspendTransaction {
            Sections.update(
                SectionRow().apply { this.pinSalt = salt; this.pinHash = hash },
            ) { where { Sections.id eq id } }
        }
    }

    override suspend fun clearPin(id: Uuid) {
        // SQL, not an update built from a row: a row cannot carry "set this column back to null" —
        // an unset column and one assigned null are the same thing to it.
        db.suspendTransaction {
            Sections.execSql("""UPDATE "sections" SET "pinSalt" = NULL, "pinHash" = NULL WHERE "id" = '$id'""")
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
        Collections.find { orderBy ASC Collections.position }.map { it.toModel() }
    }

    override suspend fun create(title: String, sectionId: Uuid): Collection = insertCollection(db, title, sectionId)

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Collections.update(CollectionRow().apply { this.title = title }) { where { Collections.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid): DeletedCollection? = deleteCollection(db, id)

    override suspend fun restore(deleted: DeletedCollection) = restoreCollection(db, deleted)

    override suspend fun moveToSection(id: Uuid, sectionId: Uuid) {
        db.suspendTransaction {
            Collections.update(CollectionRow().apply { this.sectionId = sectionId }) { where { Collections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, toSectionId: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val moving = Collections.findOne { where { Collections.id eq id } } ?: return@suspendTransaction

            // Reattach to the target section, then splice the moved collection into that section's
            // order at newIndex.
            if (moving.sectionId != toSectionId) {
                Collections.update(CollectionRow().apply { sectionId = toSectionId }) { where { Collections.id eq id } }
            }

            val all = Collections.find { orderBy ASC Collections.position }
            val sections = Sections.find { orderBy ASC Sections.position }.map { it.id }

            val target = all.filter { it.sectionId == toSectionId && it.id != id }.map { it.id }.toMutableList()
            target.add(newIndex.coerceIn(0, target.size), id)

            // Renumber every collection with a single global position sequence, walking sections in
            // their sidebar order and using the spliced order for the target section.
            var pos = 0
            for (sectionId in sections) {
                val ids = if (sectionId == toSectionId) target
                else all.filter { it.sectionId == sectionId && it.id != id }.map { it.id }
                for (cid in ids) {
                    Collections.update(CollectionRow().apply { position = pos }) { where { Collections.id eq cid } }
                    pos++
                }
            }
        }
    }

    override suspend fun setReadOnly(id: Uuid, readOnly: Boolean) {
        db.suspendTransaction {
            Collections.update(
                CollectionRow().apply { this.readOnly = if (readOnly) 1 else 0 },
            ) { where { Collections.id eq id } }
        }
    }
}

internal class KormiumCardSectionRepository(
    private val db: SuspendDatabase<StramusDb>,
) : CardSectionRepository {

    override suspend fun byCollection(collectionId: Uuid): List<CardSection> = db.suspendAutocommit {
        CardSections.find {
            where { CardSections.collectionId eq collectionId }
            orderBy ASC CardSections.position
        }.map { it.toModel() }
    }

    override suspend fun create(collectionId: Uuid, title: String, description: String?): CardSection {
        val row = CardSectionRow().apply {
            this.id = Uuid.random()
            this.collectionId = collectionId
            this.title = title
            this.description = description
            this.position = db.suspendAutocommit {
                CardSections.count { where { CardSections.collectionId eq collectionId } }
            }.toInt()
            this.collapsed = 0
        }
        db.suspendTransaction { CardSections.insert(row) }
        return row.toModel()
    }

    override suspend fun update(id: Uuid, title: String, description: String?) {
        db.suspendTransaction {
            CardSections.update(
                CardSectionRow().apply { this.title = title; this.description = description },
            ) { where { CardSections.id eq id } }
        }
    }

    override suspend fun setCollapsed(id: Uuid, collapsed: Boolean) {
        db.suspendTransaction {
            CardSections.update(
                CardSectionRow().apply { this.collapsed = if (collapsed) 1 else 0 },
            ) { where { CardSections.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val moving = CardSections.findOne { where { CardSections.id eq id } } ?: return@suspendTransaction
            val order = CardSections.find {
                where { CardSections.collectionId eq moving.collectionId }
                orderBy ASC CardSections.position
            }.map { it.id }.toMutableList()
            if (!order.remove(id)) return@suspendTransaction
            order.add(newIndex.coerceIn(0, order.size), id)
            order.forEachIndexed { i, sectionId ->
                CardSections.update(CardSectionRow().apply { position = i }) { where { CardSections.id eq sectionId } }
            }
        }
    }

    override suspend fun delete(id: Uuid): DeletedCardSection? {
        val row = db.suspendAutocommit { CardSections.findOne { where { CardSections.id eq id } } } ?: return null
        val cardIds = db.suspendAutocommit {
            Cards.find {
                where { Cards.cardSectionId eq id }
                orderBy ASC Cards.position
            }
        }.map { it.id }
        db.suspendTransaction {
            // Detach the section's cards (they become ungrouped) before removing the section.
            Cards.execSql("""UPDATE "cards" SET "cardSectionId" = NULL WHERE "cardSectionId" = '$id'""")
            CardSections.deleteWhere { where { CardSections.id eq id } }
        }
        return DeletedCardSection(row.toModel(), cardIds)
    }

    override suspend fun restore(deleted: DeletedCardSection) {
        db.suspendTransaction {
            CardSections.insert(deleted.cardSection.toRow())
            // The cards were left behind, ungrouped; the ones that were in this section rejoin it.
            // Any that the user has since moved elsewhere are simply no longer there to be found.
            deleted.cardIds.forEach { cardId ->
                Cards.update(
                    CardRow().apply { this.cardSectionId = deleted.cardSection.id },
                ) { where { Cards.id eq cardId } }
            }
        }
    }
}

internal class KormiumCardRepository(
    private val db: SuspendDatabase<StramusDb>,
) : CardRepository {

    override suspend fun byCollection(collectionId: Uuid): List<Card> = db.suspendAutocommit {
        Cards.find {
            where { Cards.collectionId eq collectionId }
            orderBy ASC Cards.position
        }.map { it.toModel() }
    }

    override suspend fun count(collectionId: Uuid): Int = db.suspendAutocommit {
        Cards.count { where { Cards.collectionId eq collectionId } }
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
            this.position = db.suspendAutocommit {
                Cards.count { where { Cards.collectionId eq collectionId } }
            }.toInt()
            this.createdAt = Clock.System.now()
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
            Cards.update(CardRow().apply { this.thumb = thumb }) { where { Cards.id eq id } }
        }
    }

    override suspend fun imageFilesWithoutThumb(): List<Card> = db.suspendAutocommit {
        Cards.find { where { Cards.kind eq CardKind.FILE.id } }.map { it.toModel() }
    }.filter { it.thumb == null && (it.mime ?: "").startsWith("image/") }

    override suspend fun updateNote(id: Uuid, title: String, content: String) {
        db.suspendTransaction {
            Cards.update(CardRow().apply { this.title = title; this.content = content }) { where { Cards.id eq id } }
        }
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Cards.update(CardRow().apply { this.title = title }) { where { Cards.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid) {
        db.suspendTransaction {
            CardBlobs.deleteWhere { where { CardBlobs.cardId eq id } }
            Cards.deleteWhere { where { Cards.id eq id } }
        }
    }

    override suspend fun move(id: Uuid, toCollectionId: Uuid, cardSectionId: Uuid?, newIndex: Int) {
        db.suspendTransaction {
            val moving = Cards.findOne { where { Cards.id eq id } } ?: return@suspendTransaction
            val fromCollectionId = moving.collectionId

            // A card can only join a section of the collection it lands in; anything else (a stale
            // section from the collection it came from) would hide it from every group.
            val groups = CardSections.find {
                where { CardSections.collectionId eq toCollectionId }
                orderBy ASC CardSections.position
            }.map { it.id }
            val group = cardSectionId?.takeIf { it in groups }

            // Reattach first, so the renumbering below sees the card in its new home. This goes
            // through SQL because an update built from a row can't clear cardSectionId — an unset
            // column and one assigned null look the same to it.
            val groupValue = if (group == null) "NULL" else "'$group'"
            Cards.execSql(
                """UPDATE "cards" SET "collectionId" = '$toCollectionId', "cardSectionId" = $groupValue WHERE "id" = '$id'""",
            )

            // Renumber the target collection one group at a time — ungrouped first, then each card
            // section in its own order — with the moved card spliced into its group at newIndex.
            // Positions stay a single collection-wide sequence, so a group's cards never interleave.
            val target = Cards.find {
                where { Cards.collectionId eq toCollectionId }
                orderBy ASC Cards.position
            }
            var pos = 0
            for (g in listOf<Uuid?>(null) + groups) {
                val ids = target.filter { it.cardSectionId == g && it.id != id }.map { it.id }.toMutableList()
                if (g == group) ids.add(newIndex.coerceIn(0, ids.size), id)
                for (cid in ids) {
                    Cards.update(CardRow().apply { position = pos }) { where { Cards.id eq cid } }
                    pos++
                }
            }

            // The card left a hole in the order of the collection it came from.
            if (fromCollectionId != toCollectionId) {
                Cards.find {
                    where { Cards.collectionId eq fromCollectionId }
                    orderBy ASC Cards.position
                }.map { it.id }.forEachIndexed { i, cid ->
                    Cards.update(CardRow().apply { position = i }) { where { Cards.id eq cid } }
                }
            }
        }
    }

    override suspend fun reorder(collectionId: Uuid, cardSectionId: Uuid?, orderedIds: List<Uuid>) {
        db.suspendTransaction {
            val groups = CardSections.find {
                where { CardSections.collectionId eq collectionId }
                orderBy ASC CardSections.position
            }.map { it.id }
            val all = Cards.find {
                where { Cards.collectionId eq collectionId }
                orderBy ASC Cards.position
            }

            // The collection is renumbered as a whole — ungrouped first, then each section in turn —
            // because positions are one collection-wide sequence (see [move]). Only the named group
            // changes order: everywhere else the cards are written back in the order they were read.
            var pos = 0
            for (g in listOf<Uuid?>(null) + groups) {
                val members = all.filter { it.cardSectionId == g }.map { it.id }
                val ids = if (g == cardSectionId) {
                    val sorted = orderedIds.filter { it in members }
                    sorted + members.filterNot { it in sorted }
                } else {
                    members
                }
                for (cid in ids) {
                    Cards.update(CardRow().apply { position = pos }) { where { Cards.id eq cid } }
                    pos++
                }
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
                }
                orderBy ASC Cards.title
            }.map { it.toModel() }
        }
    }
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
        Usage.all().map { UsageStat(it.url, it.title, it.host, it.hits, it.lastUsedAt) }
    }

    override suspend fun record(url: String, title: String) {
        // Read, add one, write back — all under one transaction, so two openings in the same instant
        // cannot both read the same count and each write it back plus one.
        db.suspendTransaction {
            val existing = Usage.find { where { Usage.url eq url } }.firstOrNull()
            val row = UsageRow().apply {
                this.url = url
                // A page whose title is not known this time keeps the one it had: a card renamed to
                // nothing, or a tab still loading, should not blank out a name the user recognises.
                this.title = title.ifBlank { existing?.title ?: url }
                this.host = url.substringBefore('/')
                this.hits = (existing?.hits ?: 0) + 1
                this.lastUsedAt = Clock.System.now()
            }
            Usage.deleteWhere { where { Usage.url eq url } }
            Usage.insert(row)
        }
    }

    override suspend fun forget(url: String) {
        db.suspendTransaction {
            Usage.deleteWhere { where { Usage.url eq url } }
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
