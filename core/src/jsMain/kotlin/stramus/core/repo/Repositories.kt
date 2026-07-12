@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.repo

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.model.Card
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section

/** Storage-agnostic access to sections (the sidebar groups that contain collections). */
interface SectionRepository {
    suspend fun all(): List<Section>
    suspend fun create(title: String): Section
    suspend fun rename(id: Uuid, title: String)
    /** Delete a section and reassign its collections to the default one. No-op for the default. */
    suspend fun delete(id: Uuid)
    /** Persist whether the section is collapsed (hidden) in the sidebar. */
    suspend fun setCollapsed(id: Uuid, collapsed: Boolean)
    /** The id of the non-deletable "Главный" section. */
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
    suspend fun delete(id: Uuid)
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
    /** Delete a section; its cards become ungrouped (cardSectionId = null). */
    suspend fun delete(id: Uuid)
}

/** Storage-agnostic access to the cards inside a collection. */
interface CardRepository {
    suspend fun byCollection(collectionId: Uuid): List<Card>
    suspend fun add(collectionId: Uuid, title: String, url: String, favicon: String?, cardSectionId: Uuid? = null): Card

    /** Create a markdown note card. */
    suspend fun addNote(collectionId: Uuid, title: String, content: String, cardSectionId: Uuid? = null): Card

    /** Create a file card whose bytes are held inline as a `data:` URI. */
    suspend fun addFile(collectionId: Uuid, title: String, dataUri: String, mime: String, cardSectionId: Uuid? = null): Card

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

    /** Cards whose title or URL contain [query] (case-insensitive), across all collections. */
    suspend fun search(query: String): List<Card>
}

/**
 * The favicon cache: icon bytes as `data:` URIs, keyed by host. A card stores only the *URL* of its
 * icon, which says nothing once the network — or the icon service — is unavailable; the cached bytes
 * are what keep a saved link recognisable offline. Entries are refreshed whenever a fetch succeeds.
 */
interface FaviconRepository {
    /** Every cached icon, host → data URI. Read once on start so the first paint needs no network. */
    suspend fun all(): Map<String, String>

    /** Store (or replace) the icon cached for [host]. */
    suspend fun put(host: String, dataUri: String)
}
