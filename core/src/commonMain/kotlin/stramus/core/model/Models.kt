@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.model

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * `orderKey`, on each of the four things below, is where the row sits among its siblings — a section
 * among the sections, a card among the cards of its group. Sort by it (ties broken by id) and you have
 * the user's order. It is a string rather than an index because a string always has room between two
 * neighbours: see [stramus.core.order.OrderKey]. Nothing outside the repositories should build one.
 */

/**
 * A top-level group of collections in the sidebar. The default section — the one a first install is
 * given, named in the user's language — is not deletable.
 *
 * [locked] means a PIN stands between the user and everything in this section — not just the cards,
 * but the names of the collections holding them. The PIN, and its hash, never leave the repository:
 * the UI asks it to verify one, and learns nothing beyond whether the section is locked at all.
 */
data class Section(
    val id: Uuid,
    val title: String,
    val orderKey: String,
    val deletable: Boolean,
    val collapsed: Boolean,
    val locked: Boolean,
)

/**
 * A named group of saved links — the Toby "collection". Belongs to a [Section].
 *
 * [readOnly] is a guard against a slip of the hand, not against a person: the collection can be read
 * and its links opened, but nothing in it can be added, renamed, moved or deleted until it is turned
 * off. A section's PIN is what keeps *other people* out.
 */
data class Collection(
    val id: Uuid,
    val sectionId: Uuid,
    val title: String,
    val orderKey: String,
    val createdAt: Instant,
    val readOnly: Boolean,
)

/** A titled group of cards inside a collection, with an optional description (a Toby divider). */
data class CardSection(
    val id: Uuid,
    val collectionId: Uuid,
    val title: String,
    val description: String?,
    val orderKey: String,
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
 * The [kind] decides what carries the card's payload:
 *  - [CardKind.LINK]: [url] is the bookmark, [content] is null.
 *  - [CardKind.NOTE]: [content] is the markdown body, [url] is empty.
 *  - [CardKind.FILE]: [mime] is its type and [title] the file name, but the bytes are *not* here —
 *    a card is read whenever its collection is drawn, and file bytes have no upper bound. They are
 *    fetched on demand with `CardRepository.blob`; what the grid draws is [thumb], a downscaled
 *    preview of an image file (null for anything else, which shows a glyph instead).
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
    val thumb: String?,
    val mime: String?,
    /** The hash of a file's bytes — how the server names them, and how a card asks for them. */
    val blobSha: String?,
    val orderKey: String,
    val createdAt: Instant,
)
