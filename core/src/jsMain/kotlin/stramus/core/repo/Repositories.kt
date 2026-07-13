@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.repo

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.model.Card
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section

/**
 * What a deletion took away, and everything needed to put it back exactly as it was — same ids, same
 * order, same bytes. Deleting is immediate: the database is left consistent and the search stops
 * finding what is gone. Undoing is a re-insert of this, so a restored card is the same card, in the
 * same place, not a copy of it.
 */
data class DeletedCardSection(
    val cardSection: CardSection,
    /** The cards that were in it. They were not deleted with it — they were left ungrouped. */
    val cardIds: List<Uuid>,
)

data class DeletedCollection(
    val collection: Collection,
    val cardSections: List<CardSection>,
    val cards: List<Card>,
    /** The file cards' bytes, which go out with them and come back with them. */
    internal val blobs: Map<Uuid, String>,
)

data class DeletedSection(
    val section: Section,
    /** Its collections went with it — a section owns them (see [SectionRepository.delete]). */
    val collections: List<DeletedCollection>,
    // The PIN of a deleted section does not leave the repository any more than a live one's does:
    // the salt and hash pass through here to be put back, and are invisible outside this module.
    internal val pinSalt: String?,
    internal val pinHash: String?,
)

/** Storage-agnostic access to sections (the sidebar groups that contain collections). */
interface SectionRepository {
    suspend fun all(): List<Section>

    /**
     * Create a section — together with a first collection of the same name. A section with nothing
     * under it is not a place anything can be saved to, and naming one is already saying what the
     * collection in it is for; the user is free to rename or delete that collection afterwards.
     */
    suspend fun create(title: String): Section

    suspend fun rename(id: Uuid, title: String)

    /**
     * Delete a section with everything under it — its collections, their card sections and their
     * cards. Returns what was taken, for [restore]; null if [id] is the default section (which is not
     * deletable) or no section at all.
     */
    suspend fun delete(id: Uuid): DeletedSection?

    /** Put a deleted section, and everything that went with it, back where it was. */
    suspend fun restore(deleted: DeletedSection)
    /** Persist whether the section is collapsed (hidden) in the sidebar. */
    suspend fun setCollapsed(id: Uuid, collapsed: Boolean)
    /** The id of the non-deletable default section, creating it if the database has none. */
    suspend fun defaultSectionId(): Uuid

    /**
     * Put the section behind [pin] (or replace the PIN it already has). Stored salted and hashed —
     * see `stramus.core.crypto`, which also spells out what the lock does and does not protect.
     */
    suspend fun setPin(id: Uuid, pin: String)

    /** Take the lock off, leaving the section open to anyone with the app. */
    suspend fun clearPin(id: Uuid)

    /** Whether [pin] opens the section. An unlocked section takes any PIN — there is none to get wrong. */
    suspend fun verifyPin(id: Uuid, pin: String): Boolean
}

/**
 * Storage-agnostic access to collections. The web app and the extension both depend on this
 * interface, not on Kormium — the local (SQLite/IndexedDB) implementation lives in `stramus.core.db`
 * and a future remote (Ktor) implementation can slot in without touching the UI.
 */
interface CollectionRepository {
    suspend fun all(): List<Collection>
    suspend fun create(title: String, sectionId: Uuid): Collection
    suspend fun rename(id: Uuid, title: String)

    /**
     * Delete a collection with its card sections and its cards (the file cards' bytes included).
     * Returns what was taken, for [restore]; null if there is no such collection.
     */
    suspend fun delete(id: Uuid): DeletedCollection?

    /** Put a deleted collection, and everything that was in it, back where it was. */
    suspend fun restore(deleted: DeletedCollection)

    /** Move a collection to a different section. */
    suspend fun moveToSection(id: Uuid, sectionId: Uuid)

    /**
     * Move [id] into [toSectionId] at [newIndex] (among that section's collections, ordered), then
     * renumber positions so ordering within and across sections stays consistent. Powers sidebar
     * drag-and-drop reordering of collections within a section and between sections.
     */
    suspend fun move(id: Uuid, toSectionId: Uuid, newIndex: Int)

    /** Turn the collection's read-only guard on or off. */
    suspend fun setReadOnly(id: Uuid, readOnly: Boolean)
}

/** Storage-agnostic access to the card sections (dividers) inside a collection. */
interface CardSectionRepository {
    suspend fun byCollection(collectionId: Uuid): List<CardSection>
    suspend fun create(collectionId: Uuid, title: String, description: String?): CardSection
    suspend fun update(id: Uuid, title: String, description: String?)
    /** Persist whether the section is collapsed (hidden) inside its collection. */
    suspend fun setCollapsed(id: Uuid, collapsed: Boolean)

    /**
     * Delete a section; its cards are not deleted with it, they become ungrouped (cardSectionId =
     * null). Returns what was taken, for [restore]; null if there is no such section.
     */
    suspend fun delete(id: Uuid): DeletedCardSection?

    /** Put a deleted card section back, and its cards back into it. */
    suspend fun restore(deleted: DeletedCardSection)
}

/**
 * Storage-agnostic access to the cards inside a collection.
 *
 * A file card's bytes are deliberately not part of [Card]: [byCollection] runs on every redraw of a
 * collection, so the bytes are kept out of it and read one card at a time with [blob]. See [Card].
 */
interface CardRepository {
    suspend fun byCollection(collectionId: Uuid): List<Card>

    /**
     * How many cards a collection holds, without reading them. What the UI asks before deleting a
     * collection it does not have open: whether to warn is a question about the cards, and reading
     * them all — file previews and note bodies — to count them would be to read a collection for the
     * sake of one number.
     */
    suspend fun count(collectionId: Uuid): Int
    suspend fun add(collectionId: Uuid, title: String, url: String, favicon: String?, cardSectionId: Uuid? = null): Card

    /** Create a markdown note card. */
    suspend fun addNote(collectionId: Uuid, title: String, content: String, cardSectionId: Uuid? = null): Card

    /**
     * Create a file card. [dataUri] (the file's bytes) is stored apart from the card and comes back
     * only from [blob]; [thumb] is the small preview the grid draws, and is null for a file that has
     * none — anything that is not an image, or an image that could not be downscaled.
     */
    suspend fun addFile(
        collectionId: Uuid,
        title: String,
        dataUri: String,
        mime: String,
        thumb: String? = null,
        cardSectionId: Uuid? = null,
    ): Card

    /** The bytes of a file card as a `data:` URI, or null if [id] is no file card. */
    suspend fun blob(id: Uuid): String?

    /** Attach the grid's preview image to a file card. */
    suspend fun setThumb(id: Uuid, thumb: String)

    /**
     * Image file cards that carry no [Card.thumb] — the ones saved before previews existed, whose
     * bytes the grid can no longer reach for. The UI regenerates a preview for each, once.
     */
    suspend fun imageFilesWithoutThumb(): List<Card>

    /** Update a note card's title and markdown body. */
    suspend fun updateNote(id: Uuid, title: String, content: String)

    suspend fun rename(id: Uuid, title: String)
    suspend fun delete(id: Uuid)

    /**
     * Move [id] into [toCollectionId], into the group [cardSectionId] (null = ungrouped), at
     * [newIndex] among that group's cards — [Int.MAX_VALUE] appends. Collection, group and order all
     * move together: a card dropped on a section always ends up *in* that section. Positions in the
     * source and target collection are renumbered so each group's cards stay contiguous.
     *
     * [cardSectionId] must name a section of [toCollectionId]; anything else lands ungrouped.
     */
    suspend fun move(id: Uuid, toCollectionId: Uuid, cardSectionId: Uuid?, newIndex: Int)

    /**
     * Cards whose title, URL or note body contain [query] (case-insensitive), across all
     * collections. A file's bytes are not searched — they are not in the table this reads.
     */
    suspend fun search(query: String): List<Card>
}

/**
 * One page the user has opened from stramus, and how much they use it: [hits] times in all, the last
 * of them at [lastUsedAt]. [url] is normalised (see `normalizeUrl`), so the same page saved as a card
 * and visited as a tab is one row; [host] is kept beside it so a much-used site can lend some of its
 * weight to a page of that site the user has never opened before.
 */
data class UsageStat(
    val url: String,
    val title: String,
    val host: String,
    val hits: Int,
    val lastUsedAt: Instant,
)

/**
 * What the user actually uses, as opposed to what they once saved: every page opened from stramus —
 * a card followed, a tab switched to, a visited page reopened, an address typed — is counted here.
 * The search ranks by it, and an empty search box offers the top of it as the user's top sites.
 *
 * The table is small (one row per distinct page) and read once on start, so the ranking costs no
 * database round-trip; see `Usage.kt` in the UI, which holds it in memory for the session.
 */
interface UsageRepository {
    /** Every page ever opened from stramus. Read once on start into the in-memory index. */
    suspend fun all(): List<UsageStat>

    /** Count one opening of [url] (normalised by the caller), now. [title] refreshes the stored one. */
    suspend fun record(url: String, title: String)

    /** Forget a page entirely — it drops out of the ranking and out of the top sites. */
    suspend fun forget(url: String)
}

/**
 * The favicon cache: icon bytes as `data:` URIs, keyed by host. A card stores only the *URL* of its
 * icon, which says nothing once the network — or the icon service — is unavailable; the cached bytes
 * are what keep a saved link recognisable offline. Entries are refreshed whenever a fetch succeeds.
 */
interface FaviconRepository {
    /** Every cached icon, host → icon. Read once on start so the first paint needs no network. */
    suspend fun all(): Map<String, CachedIcon>

    /** Store (or replace) the icon cached for [host]. */
    suspend fun put(host: String, dataUri: String)
}

/**
 * A cached site icon: the bytes, and when they were last fetched. The age is what decides whether the
 * icon is asked for again — without it, every page load would go back to the icon services for every
 * host on screen, having already been given a perfectly good answer.
 */
data class CachedIcon(val dataUri: String, val updatedAt: Instant)
