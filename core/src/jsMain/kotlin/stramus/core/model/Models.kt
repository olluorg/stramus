@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.model

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A top-level group of collections in the sidebar. The default "Главный" section is not deletable. */
data class Section(
    val id: Uuid,
    val title: String,
    val position: Int,
    val deletable: Boolean,
    val collapsed: Boolean,
)

/** A named group of saved links — the Toby "collection". Belongs to a [Section]. */
data class Collection(
    val id: Uuid,
    val sectionId: Uuid,
    val title: String,
    val position: Int,
    val createdAt: Instant,
)

/** A titled group of cards inside a collection, with an optional description (a Toby divider). */
data class CardSection(
    val id: Uuid,
    val collectionId: Uuid,
    val title: String,
    val description: String?,
    val position: Int,
    val collapsed: Boolean,
)

/** What a [Card] holds: a bookmarked link, a markdown note, or an uploaded file. */
enum class CardKind(val id: String) {
    LINK("link"),
    NOTE("note"),
    FILE("file"),
    ;

    companion object {
        fun from(id: String?): CardKind = entries.firstOrNull { it.id == id } ?: LINK
    }
}

/**
 * One item inside a [Collection] — the Toby "card". [cardSectionId] null = ungrouped.
 *
 * The [kind] decides how [content] is used:
 *  - [CardKind.LINK]: [url] is the bookmark, [content] is null.
 *  - [CardKind.NOTE]: [content] is the markdown body, [url] is empty.
 *  - [CardKind.FILE]: [content] is a `data:` URI of the file bytes, [mime] its type, [title] the
 *    file name.
 */
data class Card(
    val id: Uuid,
    val collectionId: Uuid,
    val cardSectionId: Uuid?,
    val kind: CardKind,
    val title: String,
    val url: String,
    val favicon: String?,
    val content: String?,
    val mime: String?,
    val position: Int,
    val createdAt: Instant,
)
