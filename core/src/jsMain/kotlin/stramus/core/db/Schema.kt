package stramus.core.db

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/** Compile-time database identity for stramus. */
object StramusDb : Catalog

class SectionRow : Entity() {
    var id by Sections.id
    var title by Sections.title
    var position by Sections.position
    var deletable by Sections.deletable
    var collapsed by Sections.collapsed
}

object Sections : Table<StramusDb, SectionRow>("sections", ::SectionRow) {
    val id by Column.UUID().primaryKey()
    val title by Column.Text()
    val position by Column.Int()
    val deletable by Column.Int() // 1 = user-created (deletable), 0 = the default "Главный"
    val collapsed by Column.Int() // 1 = collapsed in the sidebar, 0 = expanded

    init { id; title; position; deletable; collapsed }
}

class CollectionRow : Entity() {
    var id by Collections.id
    var sectionId by Collections.sectionId
    var title by Collections.title
    var position by Collections.position
    var createdAt by Collections.createdAt
}

object Collections : Table<StramusDb, CollectionRow>("collections", ::CollectionRow) {
    val id by Column.UUID().primaryKey()
    val sectionId by Column.UUID()
    val title by Column.Text()
    val position by Column.Int()
    val createdAt by Column.Instant()

    init { id; sectionId; title; position; createdAt }
}

class CardSectionRow : Entity() {
    var id by CardSections.id
    var collectionId by CardSections.collectionId
    var title by CardSections.title
    var description by CardSections.description
    var position by CardSections.position
    var collapsed by CardSections.collapsed
}

object CardSections : Table<StramusDb, CardSectionRow>("card_sections", ::CardSectionRow) {
    val id by Column.UUID().primaryKey()
    val collectionId by Column.UUID()
    val title by Column.Text()
    val description by Column.Text().nullable()
    val position by Column.Int()
    val collapsed by Column.Int() // 1 = collapsed inside the collection, 0 = expanded

    init { id; collectionId; title; description; position; collapsed }
}

class CardRow : Entity() {
    var id by Cards.id
    var collectionId by Cards.collectionId
    var cardSectionId by Cards.cardSectionId
    var kind by Cards.kind
    var title by Cards.title
    var url by Cards.url
    var favicon by Cards.favicon
    var content by Cards.content
    var mime by Cards.mime
    var position by Cards.position
    var createdAt by Cards.createdAt
}

object Cards : Table<StramusDb, CardRow>("cards", ::CardRow) {
    val id by Column.UUID().primaryKey()
    val collectionId by Column.UUID()
    val cardSectionId by Column.UUID().nullable()
    val kind by Column.Text() // "link" | "note" | "file"
    val title by Column.Text()
    val url by Column.Text()
    val favicon by Column.Text().nullable()
    val content by Column.Text().nullable() // note markdown or file data-URI
    val mime by Column.Text().nullable() // file MIME type
    val position by Column.Int()
    val createdAt by Column.Instant()

    init { id; collectionId; cardSectionId; kind; title; url; favicon; content; mime; position; createdAt }
}

// Kormium does not own DDL — create the schema explicitly on open. `IF NOT EXISTS` makes this
// idempotent across reloads of the IndexedDB-persisted database.
internal val schemaDdl: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS "sections" (
        "id" text NOT NULL,
        "title" text NOT NULL,
        "position" integer NOT NULL,
        "deletable" integer NOT NULL,
        "collapsed" integer NOT NULL DEFAULT 0,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "collections" (
        "id" text NOT NULL,
        "sectionId" text NOT NULL DEFAULT '',
        "title" text NOT NULL,
        "position" integer NOT NULL,
        "createdAt" text NOT NULL,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "card_sections" (
        "id" text NOT NULL,
        "collectionId" text NOT NULL,
        "title" text NOT NULL,
        "description" text,
        "position" integer NOT NULL,
        "collapsed" integer NOT NULL DEFAULT 0,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "cards" (
        "id" text NOT NULL,
        "collectionId" text NOT NULL,
        "cardSectionId" text,
        "kind" text NOT NULL DEFAULT 'link',
        "title" text NOT NULL,
        "url" text NOT NULL,
        "favicon" text,
        "content" text,
        "mime" text,
        "position" integer NOT NULL,
        "createdAt" text NOT NULL,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """CREATE INDEX IF NOT EXISTS "idx_cards_collection" ON "cards" ("collectionId", "position")""",
)
