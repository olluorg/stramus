@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What is in the note editor but not yet in the database.
 *
 * A note is only written when Save is pressed, and a browser tab is closed without asking — so
 * between the first keystroke and that press the text lives nowhere but in the DOM, which the closing
 * of the tab takes with it. A draft is that text, kept in localStorage as it is typed and put back
 * into the editor the next time the same note is opened, however the last visit ended: a closed tab,
 * a reload, a crash, or a modal shut by mistake.
 *
 * It is deliberately *not* in the database: a draft is not a note. It is per-browser, it must survive
 * a tab that never got to run another line of code, and nothing else in the app should see it — a
 * half-written note has no business appearing in a collection, in search, or in an export.
 */
internal data class NoteDraft(val title: String, val content: String)

private const val DRAFT_PREFIX = "noteDraft:"

/**
 * Where a draft of an existing note card is kept — by card id, so the same unsaved edit comes back
 * whichever collection is open when the card is next clicked.
 */
internal fun cardDraftKey(cardId: Uuid): String = "$DRAFT_PREFIX$cardId"

/**
 * Where a draft of a *new* note is kept. A new note has no id yet, so it is identified by the place it
 * would land in: reopening "+ Note" on the same group brings back what was being written for it, while
 * a new note started in another collection is a different note and gets its own draft.
 */
internal fun newNoteDraftKey(collectionId: Uuid, cardSectionId: Uuid?): String =
    "${DRAFT_PREFIX}new:$collectionId:${cardSectionId ?: "-"}"

/** Where a draft of a card section's description is kept. */
internal fun descDraftKey(cardSectionId: Uuid): String = "${DRAFT_PREFIX}desc:$cardSectionId"

/**
 * The draft under [key], or null if there is none.
 *
 * Stored as the title on the first line and the markdown body under it: the title comes from a
 * single-line input and can hold no newline of its own, so the first one is always the boundary.
 */
internal fun loadNoteDraft(key: String): NoteDraft? {
    val raw = prefGet(key) ?: return null
    val cut = raw.indexOf('\n')
    return if (cut < 0) NoteDraft(raw, "") else NoteDraft(raw.substring(0, cut), raw.substring(cut + 1))
}

internal fun saveNoteDraft(key: String, draft: NoteDraft) {
    prefSet(key, "${draft.title}\n${draft.content}")
}

internal fun clearNoteDraft(key: String) {
    prefRemove(key)
}
