@file:OptIn(ExperimentalUuidApi::class, DelicateKormiumApi::class)

package stramus.core.db

import io.github.kormium.DelicateKormiumApi
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
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.repo.CardRepository
import stramus.core.repo.CardSectionRepository
import stramus.core.repo.CollectionRepository
import stramus.core.repo.SectionRepository

/** Everything the UI needs: the open database plus the repositories over it. */
class StramusStore internal constructor(
    val db: SuspendDatabase<StramusDb>,
    val sections: SectionRepository,
    val collections: CollectionRepository,
    val cardSections: CardSectionRepository,
    val cards: CardRepository,
)

private const val DEFAULT_SECTION_TITLE = "Главный"

/**
 * Opens the IndexedDB-persisted stramus database (via Kormium's Kotlin/JS wa-sqlite engine), creates
 * the schema if needed, ensures the default section exists, and returns the store. [name] is the
 * IndexedDB database name.
 */
suspend fun openStramusStore(name: String = "stramus"): StramusStore {
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
    }

    val sections = KormiumSectionRepository(db)
    val defaultId = sections.defaultSectionId()
    // Attach any collections without a section (fresh migration) to the default one.
    db.suspendTransaction {
        Collections.execSql("""UPDATE "collections" SET "sectionId" = '$defaultId' WHERE "sectionId" IS NULL OR "sectionId" = ''""")
    }

    return StramusStore(
        db,
        sections,
        KormiumCollectionRepository(db, defaultId),
        KormiumCardSectionRepository(db),
        KormiumCardRepository(db),
    )
}

private fun SectionRow.toModel() = Section(id, title, position, deletable != 0, collapsed != 0)
private fun CollectionRow.toModel() = Collection(id, sectionId, title, position, createdAt)
private fun CardSectionRow.toModel() = CardSection(id, collectionId, title, description, position, collapsed != 0)
private fun CardRow.toModel() =
    Card(id, collectionId, cardSectionId, CardKind.from(kind), title, url, favicon, content, mime, position, createdAt)

internal class KormiumSectionRepository(
    private val db: SuspendDatabase<StramusDb>,
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
        }
        db.suspendTransaction { Sections.insert(row) }
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

    override suspend fun delete(id: Uuid) {
        val default = defaultSectionId()
        if (id == default) return // the default section is not deletable
        db.suspendTransaction {
            Collections.update(CollectionRow().apply { sectionId = default }) { where { Collections.sectionId eq id } }
            Sections.deleteWhere { where { Sections.id eq id } }
        }
    }

    /** Returns the default section id, creating the non-deletable "Главный" section if absent. */
    override suspend fun defaultSectionId(): Uuid {
        val existing = db.suspendAutocommit { Sections.all() }.firstOrNull { it.deletable == 0 }
        if (existing != null) return existing.id
        val row = SectionRow().apply {
            this.id = Uuid.random()
            this.title = DEFAULT_SECTION_TITLE
            this.position = 0
            this.deletable = 0
            this.collapsed = 0
        }
        db.suspendTransaction { Sections.insert(row) }
        return row.id
    }
}

internal class KormiumCollectionRepository(
    private val db: SuspendDatabase<StramusDb>,
    private val defaultSectionId: Uuid,
) : CollectionRepository {

    override suspend fun all(): List<Collection> = db.suspendAutocommit {
        Collections.find { orderBy ASC Collections.position }.map { it.toModel() }
    }

    override suspend fun create(title: String, sectionId: Uuid): Collection {
        val row = CollectionRow().apply {
            this.id = Uuid.random()
            this.sectionId = sectionId
            this.title = title
            this.position = db.suspendAutocommit { Collections.count() }.toInt()
            this.createdAt = Clock.System.now()
        }
        db.suspendTransaction { Collections.insert(row) }
        return row.toModel()
    }

    override suspend fun rename(id: Uuid, title: String) {
        db.suspendTransaction {
            Collections.update(CollectionRow().apply { this.title = title }) { where { Collections.id eq id } }
        }
    }

    override suspend fun delete(id: Uuid) {
        db.suspendTransaction {
            Cards.deleteWhere { where { Cards.collectionId eq id } }
            Collections.deleteWhere { where { Collections.id eq id } }
        }
    }

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

    override suspend fun delete(id: Uuid) {
        db.suspendTransaction {
            // Detach the section's cards (they become ungrouped) before removing the section.
            Cards.execSql("""UPDATE "cards" SET "cardSectionId" = NULL WHERE "cardSectionId" = '$id'""")
            CardSections.deleteWhere { where { CardSections.id eq id } }
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

    override suspend fun add(collectionId: Uuid, title: String, url: String, favicon: String?, cardSectionId: Uuid?): Card =
        insert(collectionId, cardSectionId, CardKind.LINK, title, url, favicon, content = null, mime = null)

    override suspend fun addNote(collectionId: Uuid, title: String, content: String, cardSectionId: Uuid?): Card =
        insert(collectionId, cardSectionId, CardKind.NOTE, title, url = "", favicon = null, content = content, mime = null)

    override suspend fun addFile(collectionId: Uuid, title: String, dataUri: String, mime: String, cardSectionId: Uuid?): Card =
        insert(collectionId, cardSectionId, CardKind.FILE, title, url = "", favicon = null, content = dataUri, mime = mime)

    private suspend fun insert(
        collectionId: Uuid,
        cardSectionId: Uuid?,
        kind: CardKind,
        title: String,
        url: String,
        favicon: String?,
        content: String?,
        mime: String?,
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
            this.mime = mime
            this.position = db.suspendAutocommit {
                Cards.count { where { Cards.collectionId eq collectionId } }
            }.toInt()
            this.createdAt = Clock.System.now()
        }
        db.suspendTransaction { Cards.insert(row) }
        return row.toModel()
    }

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
        db.suspendTransaction { Cards.deleteWhere { where { Cards.id eq id } } }
    }

    override suspend fun move(id: Uuid, toCollectionId: Uuid, newIndex: Int) {
        db.suspendTransaction {
            val moving = Cards.findOne { where { Cards.id eq id } } ?: return@suspendTransaction
            val fromCollectionId = moving.collectionId

            // Reattach to the target collection first, then renumber the target order with the moved
            // card spliced in at newIndex; each update writes only the column it assigns.
            if (fromCollectionId != toCollectionId) {
                Cards.update(CardRow().apply { collectionId = toCollectionId }) { where { Cards.id eq id } }
            }

            val target = Cards.find {
                where { Cards.collectionId eq toCollectionId }
                orderBy ASC Cards.position
            }.map { it.id }.filter { it != id }.toMutableList()
            target.add(newIndex.coerceIn(0, target.size), id)
            target.forEachIndexed { i, cid ->
                Cards.update(CardRow().apply { position = i }) { where { Cards.id eq cid } }
            }

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

    override suspend fun moveToSection(id: Uuid, cardSectionId: Uuid?) {
        db.suspendTransaction {
            val moving = Cards.findOne { where { Cards.id eq id } } ?: return@suspendTransaction
            // Place the card at the end of its collection's order so it lands last in the new group.
            val end = Cards.count { where { Cards.collectionId eq moving.collectionId } }.toInt()
            val value = if (cardSectionId == null) "NULL" else "'$cardSectionId'"
            Cards.execSql("""UPDATE "cards" SET "cardSectionId" = $value, "position" = $end WHERE "id" = '$id'""")
        }
    }

    override suspend fun search(query: String): List<Card> = db.suspendAutocommit {
        val pattern = "%$query%"
        Cards.find {
            where { (Cards.title like pattern) or (Cards.url like pattern) or (Cards.content like pattern) }
            orderBy ASC Cards.title
        }.map { it.toModel() }
    }
}
