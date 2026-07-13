package stramus.ui

import stramus.core.platform.CapturedTab

/**
 * How a window's open tabs are sorted from the tabs sidebar.
 *
 * Unlike [SortMode], which only decides the order cards are *drawn* in, this one is an action rather
 * than a view: the browser's own tab strip is rearranged, so the new order is what the user sees in
 * the browser too, and it outlives the page. There is nothing to persist and nothing to undo — the
 * tabs simply are where the sort put them.
 */
internal enum class TabSort(val id: String) {
    TITLE("title"),
    DOMAIN("domain"),
    URL("url"),
    ;

    /** The action's label in the current UI language, as the ⇅ menu lists it. */
    fun label(s: Strings): String = when (this) {
        TITLE -> s.sortTitle
        DOMAIN -> s.sortDomain
        URL -> s.sortUrl
    }

    /**
     * [tabs] in the order the browser should hold them. Sorting by domain gathers a site's tabs
     * together and orders them by title within it — the point of the sort is the grouping, and tabs of
     * one site left in an arbitrary order among themselves would only half deliver it.
     */
    fun apply(tabs: List<CapturedTab>): List<CapturedTab> = when (this) {
        TITLE -> tabs.sortedWith(compareBy({ it.title.lowercase() }, { it.url.lowercase() }))
        DOMAIN -> tabs.sortedWith(compareBy({ hostOf(it.url).lowercase() }, { it.title.lowercase() }))
        URL -> tabs.sortedBy { it.url.lowercase() }
    }

    companion object {
        fun from(id: String?): TabSort? = entries.firstOrNull { it.id == id }
    }
}
