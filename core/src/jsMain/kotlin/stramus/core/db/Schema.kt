package stramus.core.db

import io.github.kidx.Field
import io.github.kidx.Migration
import io.github.kidx.Row
import io.github.kidx.Schema
import io.github.kidx.SchemaStep
import io.github.kidx.Store
import io.github.kidx.index
import kotlin.time.Instant
import kotlin.uuid.Uuid

/*
 * Three fields run through every row the user can change — a section, a collection, a card section,
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
 *
 * None of the three is indexed. IndexedDB cannot index a nullable field ([deletedAt]) at all, and
 * every table here is small enough (a personal sidebar, not a multi-tenant one) that reading it whole
 * and filtering in Kotlin is the same cost SQLite's own query planner paid before: none of these
 * tables had a matching index either, only the three named below did.
 */

class SectionRow : Row() {
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

object Sections : Store<SectionRow>("sections", ::SectionRow) {
    val id by Field.UUID().primaryKey()
    val title by Field.Text()
    val orderKey by Field.Text()
    val deletable by Field.Int() // 1 = user-created (deletable), 0 = the default section
    val collapsed by Field.Int() // 1 = collapsed in the sidebar, 0 = expanded

    // The PIN lock, which a whole section carries: while it holds, the section's collections are not
    // even named in the sidebar. Both null = open. The PIN itself is never stored — [pinHash] is the
    // SHA-256 of [pinSalt] + PIN, and entering a PIN re-derives the hash and compares.
    val pinSalt by Field.Text().nullable()
    val pinHash by Field.Text().nullable()

    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()
}

class CollectionRow : Row() {
    var id by Collections.id
    var sectionId by Collections.sectionId
    var title by Collections.title
    var orderKey by Collections.orderKey
    var createdAt by Collections.createdAt
    var readOnly by Collections.readOnly
    var updatedAt by Collections.updatedAt
    var deletedAt by Collections.deletedAt
}

object Collections : Store<CollectionRow>("collections", ::CollectionRow) {
    val id by Field.UUID().primaryKey()
    val sectionId by Field.UUID()
    val title by Field.Text()

    // Ordered within its own section, where it used to be numbered across the whole sidebar. A
    // collection's place is a fact about the section holding it; two sections reordered at the same
    // time on two devices have nothing to say to each other, and now they no longer try to.
    val orderKey by Field.Text()

    val createdAt by Field.Instant()
    val readOnly by Field.Int() // 1 = look, don't touch: no adding, editing, moving or deleting
    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()

    /** Every collection of one section, in sidebar order — the one range query this store needs. */
    val bySection by index(sectionId, orderKey)
}

class CardSectionRow : Row() {
    var id by CardSections.id
    var collectionId by CardSections.collectionId
    var title by CardSections.title
    var description by CardSections.description
    var orderKey by CardSections.orderKey
    var collapsed by CardSections.collapsed
    var updatedAt by CardSections.updatedAt
    var deletedAt by CardSections.deletedAt
}

object CardSections : Store<CardSectionRow>("card_sections", ::CardSectionRow) {
    val id by Field.UUID().primaryKey()
    val collectionId by Field.UUID()
    val title by Field.Text()
    val description by Field.Text().nullable()
    val orderKey by Field.Text()
    val collapsed by Field.Int() // 1 = collapsed inside the collection, 0 = expanded
    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()

    val byCollection by index(collectionId, orderKey)
}

class CardRow : Row() {
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

object Cards : Store<CardRow>("cards", ::CardRow) {
    val id by Field.UUID().primaryKey()
    val collectionId by Field.UUID()
    val cardSectionId by Field.UUID().nullable()
    val kind by Field.Text() // "link" | "note" | "file"
    val title by Field.Text()
    val url by Field.Text()
    val favicon by Field.Text().nullable()
    val content by Field.Text().nullable() // note markdown; null for a file — see [CardBlobs]
    val thumb by Field.Text().nullable() // downscaled preview of an image file, a `data:` URI
    val mime by Field.Text().nullable() // file MIME type

    /**
     * The SHA-256 of a file card's bytes — the name the server stores them under, and the only part of a
     * file that travels in the sync delta. Null for anything that is not a file.
     *
     * It lives on the card rather than beside the bytes because that is where a device that has *not* got
     * the bytes can see it: a card arriving from another machine says "my file is this one", and the engine
     * goes and fetches it.
     */
    val blobSha by Field.Text().nullable()

    // Ordered within its group — the (collection, card section) it hangs under, ungrouped cards being
    // the group whose section is null. Cards of different groups never compare, so the positions of a
    // collection are no longer one sequence that every move has to renumber.
    val orderKey by Field.Text()

    val createdAt by Field.Instant()
    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()

    /**
     * Every card of one collection, in group order. `cardSectionId` (which group, within the
     * collection) is not part of this index — kidx allows only a prefix-of-one-index query, and
     * grouping a collection's cards by section is a handful of rows filtered in Kotlin, not a range
     * scan worth a compound index over.
     */
    val byCollection by index(collectionId, orderKey)
}

class CardBlobRow : Row() {
    var cardId by CardBlobs.cardId
    var data by CardBlobs.data
}

/**
 * The bytes of a file card, one row per card, held as a `data:` URI.
 *
 * They live apart from [Cards] because they are the one thing in this database with no upper bound on
 * size, and the card grid needs none of them: reading a collection reads every column of every card,
 * so a file inline in `cards` would put megabytes of base64 into the page on every redraw — and into
 * every search kromus runs. The grid shows [Cards.thumb]; these bytes are read only when the file is
 * actually opened.
 */
object CardBlobs : Store<CardBlobRow>("card_blobs", ::CardBlobRow) {
    val cardId by Field.UUID().primaryKey()
    val data by Field.Text()
}

class UsageRow : Row() {
    var url by Usage.url
    var title by Usage.title
    var host by Usage.host
    var hits by Usage.hits
    var lastUsedAt by Usage.lastUsedAt
    var deletedAt by Usage.deletedAt
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
object Usage : Store<UsageRow>("usage", ::UsageRow) {
    val url by Field.Text().primaryKey()
    val title by Field.Text()
    val host by Field.Text()
    val hits by Field.Int()
    val lastUsedAt by Field.Instant()

    /**
     * A page the user asked us to forget.
     *
     * The one counter that can be *un*counted, and so the one that needs a tombstone: "stop suggesting this
     * page" has to be a thing that happened, or the device that still remembers it would push it back on its
     * next sync and the suggestion would return — which, for a page someone deliberately asked to be rid of,
     * is about the worst thing this app could do.
     */
    val deletedAt by Field.Instant().nullable()
}

class ActionUsageRow : Row() {
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
object ActionUsage : Store<ActionUsageRow>("action_usage", ::ActionUsageRow) {
    val kind by Field.Text().primaryKey()
    val hits by Field.Int()
    val lastUsedAt by Field.Instant()
}

class FaviconRow : Row() {
    var host by Favicons.host
    var dataUri by Favicons.dataUri
    var updatedAt by Favicons.updatedAt
}

/**
 * Cached favicon bytes, one row per host. A card only stores the *URL* of its icon, so without this
 * an offline load — or an icon source that went away — leaves the card blank. The bytes are held as
 * a `data:` URI, the form the UI hands straight to an `<img>`.
 */
object Favicons : Store<FaviconRow>("favicons", ::FaviconRow) {
    val host by Field.Text().primaryKey()
    val dataUri by Field.Text()
    val updatedAt by Field.Instant()
}

class SyncMetaRow : Row() {
    var tbl by SyncMeta.tbl
    var rowId by SyncMeta.rowId
    var hash by SyncMeta.hash

    // kidx has no built-in Long field type (a KMP/JS Long is not one JS number, and would not survive
    // structured cloning) — stored as text and converted here, the same way `SyncState`'s `lastRev`
    // already carries a revision number as a string.
    private var revText by SyncMeta.rev
    var rev: Long
        get() = revText.toLong()
        set(value) { revText = value.toString() }
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
 *
 * Keyed by `(tbl, rowId)` — an IndexedDB composite primary key, so a base is looked up and replaced by
 * `get`/`put` on the pair directly, with no scan.
 */
object SyncMeta : Store<SyncMetaRow>("sync_meta", ::SyncMetaRow) {
    val tbl by Field.Text().primaryKey()
    val rowId by Field.Text().primaryKey()
    val hash by Field.Text()
    val rev by Field.Text()
}

class SyncStateRow : Row() {
    var k by SyncState.k
    var v by SyncState.v
}

/** This device's side of the conversation: the cursor it has read up to, its own id, whose account it is. */
object SyncState : Store<SyncStateRow>("sync_state", ::SyncStateRow) {
    val k by Field.Text().primaryKey()
    val v by Field.Text()
}

/**
 * The database kidx opens. Named apart from the old `"stramus"` IndexedDB database, which is a
 * SQLite-on-WASM file (wa-sqlite's VFS) that a native-IndexedDB layer cannot read at all — kidx just
 * creates this one fresh and never touches the old one. See `StoreJs.kt`.
 *
 * One migration, because there is nothing to carry forward: a database already on the old engine
 * starts here empty, seeded the same way a first install always has been ([StoreSeed]).
 */
val stramusSchema: Schema = Schema(
    "stramus-kidx",
    listOf(
        Migration(
            1,
            listOf(
                SchemaStep.CreateStore(Sections),
                SchemaStep.CreateStore(Collections),
                SchemaStep.AddIndex(Collections, Collections.bySection),
                SchemaStep.CreateStore(CardSections),
                SchemaStep.AddIndex(CardSections, CardSections.byCollection),
                SchemaStep.CreateStore(Cards),
                SchemaStep.AddIndex(Cards, Cards.byCollection),
                SchemaStep.CreateStore(CardBlobs),
                SchemaStep.CreateStore(Usage),
                SchemaStep.CreateStore(ActionUsage),
                SchemaStep.CreateStore(Favicons),
                SchemaStep.CreateStore(SyncMeta),
                SchemaStep.CreateStore(SyncState),
            ),
        ),
    ),
)
