package stramus.ui

import stramus.core.model.Card

/** Ordering applied to the cards shown in a collection. MANUAL keeps the drag-and-drop order. */
internal enum class SortMode(val id: String, val label: String) {
    MANUAL("manual", "Manual"),
    TITLE("title", "Title A–Z"),
    URL("url", "URL"),
    NEWEST("newest", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    ;

    /** Sort [cards] for display. MANUAL preserves the incoming (position) order. */
    fun apply(cards: List<Card>): List<Card> = when (this) {
        MANUAL -> cards
        TITLE -> cards.sortedBy { it.title.lowercase() }
        URL -> cards.sortedBy { hostOf(it.url).lowercase() }
        NEWEST -> cards.sortedByDescending { it.createdAt }
        OLDEST -> cards.sortedBy { it.createdAt }
    }

    companion object {
        fun from(id: String?): SortMode = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}
