package stramus.core.db

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/** Compile-time database identity for stramus. */
object StramusDb : Catalog

/*
 * Three columns run through every row the user can change — a section, a collection, a card section,
 * a card — and they are explained here rather than four times over:
 *
 *  - `orderKey` is where the row sits among its siblings. It is a string, not an index: a string
 *    always has room between two neighbours, so moving one row writes one row. See
 *    [stramus.core.order.OrderKey], which is where the reasoning lives.
 *
 *  - `updatedAt` is when the row was last written, and it is what a merge decides by (later write
 *    wins). It is stamped on every write, account or no account: a database that starts syncing next
 *    year must still be able to say what happened when, and it cannot work that out afterwards.
 *
 *  - `deletedAt` is a tombstone. It stays null while the app has no account — deletion is physical,
 *    as it always was, since a device that never syncs would otherwise hoard the dead forever. Once
 *    synchronisation is on, a deletion has to be a *thing that happened*: the other device, seeing
 *    only that a row is missing, would otherwise helpfully put it back.
 */

class SectionRow : Entity() {
    var id by Sections.id
    var title by Sections.title
    var orderKey by Sections.orderKey
    var deletable by Sections.deletable
    var collapsed by Sections.collapsed
    var pinSalt by Sections.pinSalt
    var pinHash by Sections.pinHash
    var updatedAt by Sections.updatedAt
    var deletedAt by Sections.deletedAt
}

object Sections : Table<StramusDb, SectionRow>("sections", ::SectionRow) {
    val id by Column.UUID().primaryKey()
    val title by Column.Text()
    val orderKey by Column.Text()
    val deletable by Column.Int() // 1 = user-created (deletable), 0 = the default section
    val collapsed by Column.Int() // 1 = collapsed in the sidebar, 0 = expanded

    // The PIN lock, which a whole section carries: while it holds, the section's collections are not
    // even named in the sidebar. Both null = open. The PIN itself is never stored — [pinHash] is the
    // SHA-256 of [pinSalt] + PIN, and entering a PIN re-derives the hash and compares.
    val pinSalt by Column.Text().nullable()
    val pinHash by Column.Text().nullable()

    val updatedAt by Column.Instant()
    val deletedAt by Column.Instant().nullable()

    init { id; title; orderKey; deletable; collapsed; pinSalt; pinHash; updatedAt; deletedAt }
}

class CollectionRow : Entity() {
    var id by Collections.id
    var sectionId by Collections.sectionId
    var title by Collections.title
    var orderKey by Collections.orderKey
    var createdAt by Collections.createdAt
    var readOnly by Collections.readOnly
    var updatedAt by Collections.updatedAt
    var deletedAt by Collections.deletedAt
}

object Collections : Table<StramusDb, CollectionRow>("collections", ::CollectionRow) {
    val id by Column.UUID().primaryKey()
    val sectionId by Column.UUID()
    val title by Column.Text()

    // Ordered within its own section, where it used to be numbered across the whole sidebar. A
    // collection's place is a fact about the section holding it; two sections reordered at the same
    // time on two devices have nothing to say to each other, and now they no longer try to.
    val orderKey by Column.Text()

    val createdAt by Column.Instant()
    val readOnly by Column.Int() // 1 = look, don't touch: no adding, editing, moving or deleting
    val updatedAt by Column.Instant()
    val deletedAt by Column.Instant().nullable()

    init { id; sectionId; title; orderKey; createdAt; readOnly; updatedAt; deletedAt }
}

class CardSectionRow : Entity() {
    var id by CardSections.id
    var collectionId by CardSections.collectionId
    var title by CardSections.title
    var description by CardSections.description
    var orderKey by CardSections.orderKey
    var collapsed by CardSections.collapsed
    var updatedAt by CardSections.updatedAt
    var deletedAt by CardSections.deletedAt
}

object CardSections : Table<StramusDb, CardSectionRow>("card_sections", ::CardSectionRow) {
    val id by Column.UUID().primaryKey()
    val collectionId by Column.UUID()
    val title by Column.Text()
    val description by Column.Text().nullable()
    val orderKey by Column.Text()
    val collapsed by Column.Int() // 1 = collapsed inside the collection, 0 = expanded
    val updatedAt by Column.Instant()
    val deletedAt by Column.Instant().nullable()

    init { id; collectionId; title; description; orderKey; collapsed; updatedAt; deletedAt }
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
    var blobSha by Cards.blobSha
    var orderKey by Cards.orderKey
    var createdAt by Cards.createdAt
    var updatedAt by Cards.updatedAt
    var deletedAt by Cards.deletedAt
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

    /**
     * The SHA-256 of a file card's bytes — the name the server stores them under, and the only part of a
     * file that travels in the sync delta. Null for anything that is not a file.
     *
     * It lives on the card rather than beside the bytes because that is where a device that has *not* got
     * the bytes can see it: a card arriving from another machine says "my file is this one", and the engine
     * goes and fetches it.
     */
    val blobSha by Column.Text().nullable()

    // Ordered within its group — the (collection, card section) it hangs under, ungrouped cards being
    // the group whose section is null. Cards of different groups never compare, so the positions of a
    // collection are no longer one sequence that every move has to renumber.
    val orderKey by Column.Text()

    val createdAt by Column.Instant()
    val updatedAt by Column.Instant()
    val deletedAt by Column.Instant().nullable()

    init {
        id; collectionId; cardSectionId; kind; title; url; favicon; content; thumb; mime; blobSha
        orderKey; createdAt; updatedAt; deletedAt
    }
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
 *
 * There is no `updatedAt` here, unlike the tables above, and that is deliberate: this is a counter,
 * and counters do not merge by last write. Taking the whole row of whichever device wrote last would
 * throw away the openings the other one counted — [hits] and [lastUsedAt] merge by *maximum* instead.
 */
object Usage : Table<StramusDb, UsageRow>("usage", ::UsageRow) {
    val url by Column.Text().primaryKey()
    val title by Column.Text()
    val host by Column.Text()
    val hits by Column.Int()
    val lastUsedAt by Column.Instant()

    init { url; title; host; hits; lastUsedAt }
}

class ActionUsageRow : Entity() {
    var kind by ActionUsage.kind
    var hits by ActionUsage.hits
    var lastUsedAt by ActionUsage.lastUsedAt
}

/**
 * How often, and how recently, the user takes each *kind* of row of the search box — switching to a
 * tab, following a saved card, asking the model, searching the web. Where [Usage] learns which pages
 * a user lives in, this learns what they come to the box to do: someone who asks the assistant all
 * day should find that row above the rest, and someone who never does should not.
 *
 * One row per kind (see `HitAction` in the UI), so the whole table is a handful of rows, read once on
 * start and kept in memory for the session. Merges by maximum, for the reason [Usage] gives.
 */
object ActionUsage : Table<StramusDb, ActionUsageRow>("action_usage", ::ActionUsageRow) {
    val kind by Column.Text().primaryKey()
    val hits by Column.Int()
    val lastUsedAt by Column.Instant()

    init { kind; hits; lastUsedAt }
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

class SyncMetaRow : Entity() {
    var tbl by SyncMeta.tbl
    var rowId by SyncMeta.rowId
    var hash by SyncMeta.hash
    var rev by SyncMeta.rev
}

/**
 * The *base* version of every row the server has confirmed: what it looked like at the last successful
 * synchronisation, and the revision it arrived in.
 *
 * This is the bookkeeping that makes a merge honest. Without it, two situations are indistinguishable
 * — "this row changed here" and "this row changed on both sides" — and a last-write-wins merge quietly
 * drops a version in the second case while believing it is in the first. With it, the client pushes
 * only what really changed, and a real conflict announces itself as one.
 *
 * A [hash], not a copy of the row: the only question ever asked of the base is "is the row still what
 * the server confirmed?", and a hash answers it without keeping every note and every preview twice
 * over. It also cannot drift out of step with the data, the way a `dirty` flag can — a flag stays set
 * through an edit that was undone, and is lost entirely by a crash between writing the row and setting
 * it.
 */
object SyncMeta : Table<StramusDb, SyncMetaRow>("sync_meta", ::SyncMetaRow) {
    val tbl by Column.Text().primaryKey()
    val rowId by Column.Text().primaryKey()
    val hash by Column.Text()
    val rev by Column.Long()

    init { tbl; rowId; hash; rev }
}

class SyncStateRow : Entity() {
    var k by SyncState.k
    var v by SyncState.v
}

/** This device's side of the conversation: the cursor it has read up to, its own id, whose account it is. */
object SyncState : Table<StramusDb, SyncStateRow>("sync_state", ::SyncStateRow) {
    val k by Column.Text().primaryKey()
    val v by Column.Text()

    init { k; v }
}

// Kormium does not own DDL — create the schema explicitly on open. `IF NOT EXISTS` makes this
// idempotent across reloads of the IndexedDB-persisted database. A database written by an earlier
// version of the app is brought up to this shape by `migrateToOrderKeys` in Store.kt.
//
// Tables and indexes are two lists rather than one because on a database that still orders its rows by
// an integer, the indexes below name a column that is not there yet: they can only be built once that
// migration has run, and it in turn needs the tables.
internal val schemaTableDdl: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS "sections" (
        "id" text NOT NULL,
        "title" text NOT NULL,
        "orderKey" text NOT NULL,
        "deletable" integer NOT NULL,
        "collapsed" integer NOT NULL DEFAULT 0,
        "pinSalt" text,
        "pinHash" text,
        "updatedAt" text NOT NULL,
        "deletedAt" text,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "collections" (
        "id" text NOT NULL,
        "sectionId" text NOT NULL DEFAULT '',
        "title" text NOT NULL,
        "orderKey" text NOT NULL,
        "createdAt" text NOT NULL,
        "readOnly" integer NOT NULL DEFAULT 0,
        "updatedAt" text NOT NULL,
        "deletedAt" text,
        PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "card_sections" (
        "id" text NOT NULL,
        "collectionId" text NOT NULL,
        "title" text NOT NULL,
        "description" text,
        "orderKey" text NOT NULL,
        "collapsed" integer NOT NULL DEFAULT 0,
        "updatedAt" text NOT NULL,
        "deletedAt" text,
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
        "blobSha" text,
        "orderKey" text NOT NULL,
        "createdAt" text NOT NULL,
        "updatedAt" text NOT NULL,
        "deletedAt" text,
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
    """
    CREATE TABLE IF NOT EXISTS "action_usage" (
        "kind" text NOT NULL,
        "hits" integer NOT NULL,
        "lastUsedAt" text NOT NULL,
        PRIMARY KEY ("kind")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "sync_meta" (
        "tbl" text NOT NULL,
        "rowId" text NOT NULL,
        "hash" text NOT NULL,
        "rev" integer NOT NULL,
        PRIMARY KEY ("tbl", "rowId")
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS "sync_state" (
        "k" text NOT NULL,
        "v" text NOT NULL,
        PRIMARY KEY ("k")
    )
    """.trimIndent(),
)

internal val schemaIndexDdl: List<String> = listOf(
    """CREATE INDEX IF NOT EXISTS "idx_cards_collection" ON "cards" ("collectionId", "orderKey")""",
    """CREATE INDEX IF NOT EXISTS "idx_card_sections_collection" ON "card_sections" ("collectionId", "orderKey")""",
    """CREATE INDEX IF NOT EXISTS "idx_collections_section" ON "collections" ("sectionId", "orderKey")""",
)
