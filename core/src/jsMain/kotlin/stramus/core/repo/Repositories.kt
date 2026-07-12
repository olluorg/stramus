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

    /** Move [id] to [toCollectionId] at [newIndex], renumbering both the source and target order. */
    suspend fun move(id: Uuid, toCollectionId: Uuid, newIndex: Int)

    /** Reassign [id] to [cardSectionId] (null = ungrouped) and place it at the end of that group. */
    suspend fun moveToSection(id: Uuid, cardSectionId: Uuid?)

    /** Cards whose title or URL contain [query] (case-insensitive), across all collections. */
    suspend fun search(query: String): List<Card>
}
