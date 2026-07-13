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
    var pinSalt by Sections.pinSalt
    var pinHash by Sections.pinHash
}

object Sections : Table<StramusDb, SectionRow>("sections", ::SectionRow) {
    val id by Column.UUID().primaryKey()
    val title by Column.Text()
    val position by Column.Int()
    val deletable by Column.Int() // 1 = user-created (deletable), 0 = the default section
    val collapsed by Column.Int() // 1 = collapsed in the sidebar, 0 = expanded

    // The PIN lock, which a whole section carries: while it holds, the section's collections are not
    // even named in the sidebar. Both null = open. The PIN itself is never stored — [pinHash] is the
    // SHA-256 of [pinSalt] + PIN, and entering a PIN re-derives the hash and compares.
    val pinSalt by Column.Text().nullable()
    val pinHash by Column.Text().nullable()

    init { id; title; position; deletable; collapsed; pinSalt; pinHash }
}

class CollectionRow : Entity() {
    var id by Collections.id
    var sectionId by Collections.sectionId
    var title by Collections.title
    var position by Collections.position
    var createdAt by Collections.createdAt
    var readOnly by Collections.readOnly
}

object Collections : Table<StramusDb, CollectionRow>("collections", ::CollectionRow) {
    val id by Column.UUID().primaryKey()
    val sectionId by Column.UUID()
    val title by Column.Text()
    val position by Column.Int()
    val createdAt by Column.Instant()
    val readOnly by Column.Int() // 1 = look, don't touch: no adding, editing, moving or deleting

    init { id; sectionId; title; position; createdAt; readOnly }
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
    var thumb by Cards.thumb
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
    val content by Column.Text().nullable() // note markdown; null for a file — see [CardBlobs]
    val thumb by Column.Text().nullable() // downscaled preview of an image file, a `data:` URI
    val mime by Column.Text().nullable() // file MIME type
    val position by Column.Int()
    val createdAt by Column.Instant()

    init { id; collectionId; cardSectionId; kind; title; url; favicon; content; thumb; mime; position; createdAt }
}

class CardBlobRow : Entity() {
    var cardId by CardBlobs.cardId
    var data by CardBlobs.data
}

/**
 * The bytes of a file card, one row per card, held as a `data:` URI.
 *
 * They live apart from [Cards] because they are the one thing in this database with no upper bound on
 * size, and the card grid needs none of them: reading a collection reads every column of every card,
 * so a file inline in `cards` would put megabytes of base64 into the page on every redraw — and into
 * every `LIKE` the search runs. The grid shows [Cards.thumb]; these bytes are read only when the file
 * is actually opened.
 */
object CardBlobs : Table<StramusDb, CardBlobRow>("card_blobs", ::CardBlobRow) {
    val cardId by Column.UUID().primaryKey()
    val data by Column.Text()

    init { cardId; data }
}

class UsageRow : Entity() {
    var url by Usage.url
    var title by Usage.title
    var host by Usage.host
    var hits by Usage.hits
    var lastUsedAt by Usage.lastUsedAt
}

/**
 * How often, and how recently, a page was opened *from stramus* — a card followed, a tab switched to,
 * a visited page reopened, an address typed into the search box. It is what puts the pages the user
 * actually lives in at the top of the search, and what fills the box with their top sites before a
 * single character is typed.
 *
 * The key is the normalised URL (no scheme, no `www.`, no trailing slash, no fragment or tracking
 * parameters), so the same page reached by two different links is one row. [host] is kept beside it
 * because a much-used site lends some of its weight to a page of that site seen for the first time.
 */
object Usage : Table<StramusDb, UsageRow>("usage", ::UsageRow) {
    val url by Column.Text().primaryKey()
    val title by Column.Text()
    val host by Column.Text()
    val hits by Column.Int()
    val lastUsedAt by Column.Instant()

    init { url; title; host; hits; lastUsedAt }
}

class FaviconRow : Entity() {
    var host by Favicons.host
    var dataUri by Favicons.dataUri
    var updatedAt by Favicons.updatedAt
}

/**
 * Cached favicon bytes, one row per host. A card only stores the *URL* of its icon, so without this
 * an offline load — or an icon source that went away — leaves the card blank. The bytes are held as
 * a `data:` URI, the form the UI hands straight to an `<img>`.
 */
object Favicons : Table<StramusDb, FaviconRow>("favicons", ::FaviconRow) {
    val host by Column.Text().primaryKey()
    val dataUri by Column.Text()
    val updatedAt by Column.Instant()

    init { host; dataUri; updatedAt }
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
        "pinSalt" text,
        "pinHash" text,
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
        "readOnly" integer NOT NULL DEFAULT 0,
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
        "thumb" text,
        "mime" text,
        "position" integer NOT NULL,
        "createdAt" text NOT NULL,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "card_blobs" (
        "cardId" text NOT NULL,
        "data" text NOT NULL,
        PRIMARY KEY ("cardId")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "favicons" (
        "host" text NOT NULL,
        "dataUri" text NOT NULL,
        "updatedAt" text NOT NULL,
        PRIMARY KEY ("host")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "usage" (
        "url" text NOT NULL,
        "title" text NOT NULL,
        "host" text NOT NULL,
        "hits" integer NOT NULL,
        "lastUsedAt" text NOT NULL,
        PRIMARY KEY ("url")
    )
    """.trimIndent(),
    """CREATE INDEX IF NOT EXISTS "idx_cards_collection" ON "cards" ("collectionId", "position")""",
    """CREATE INDEX IF NOT EXISTS "idx_card_sections_collection" ON "card_sections" ("collectionId", "position")""",
    """CREATE INDEX IF NOT EXISTS "idx_collections_section" ON "collections" ("sectionId", "position")""",
)
