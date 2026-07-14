package stramus.ui

import stramus.core.model.Card

/**
 * How one card section's cards are put in order from its ⇅ menu.
 *
 * Like [TabSort], and unlike the view setting this replaced, it is an action rather than a state: the
 * sort rearranges the cards once — their stored order, the one a drag writes — and is done. A card
 * saved a minute later lands at the end, where it was put, and not where a remembered "sorted by
 * title" would have slipped it in. So there is no "manual" here: the manual order is simply the order
 * the cards are in, and every sort is a one-off edit of it.
 */
internal enum class CardSort(val id: String) {
    TITLE("title"),
    DOMAIN("domain"),
    URL("url"),
    NEWEST("newest"),
    OLDEST("oldest"),
    ;

    /** The action's label in the current UI language, as the ⇅ menu lists it. */
    fun label(s: Strings): String = when (this) {
        TITLE -> s.sortTitle
        DOMAIN -> s.sortDomain
        URL -> s.sortUrl
        NEWEST -> s.sortNewest
        OLDEST -> s.sortOldest
    }

    /**
     * [cards] in the order this sort puts them. Sorting by domain orders a site's cards by title among
     * themselves, as [TabSort] does: the point of the sort is the grouping, and cards of one site left
     * in an arbitrary order within it would only half deliver it.
     */
    fun apply(cards: List<Card>): List<Card> = when (this) {
        TITLE -> cards.sortedBy { it.title.lowercase() }
        DOMAIN -> cards.sortedWith(compareBy({ hostOf(it.url).lowercase() }, { it.title.lowercase() }))
        URL -> cards.sortedBy { it.url.lowercase() }
        NEWEST -> cards.sortedByDescending { it.createdAt }
        OLDEST -> cards.sortedBy { it.createdAt }
    }

    companion object {
        fun from(id: String?): CardSort? = entries.firstOrNull { it.id == id }
    }
}
