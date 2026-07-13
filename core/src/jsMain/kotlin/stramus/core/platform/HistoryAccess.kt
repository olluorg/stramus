package stramus.core.platform

/**
 * One page in the user's browsing history, as read from the host environment.
 *
 * [lastVisit] is epoch milliseconds — the moment the page was last opened; it is what the UI groups
 * the entries by (one group per day) and orders them with. The browser keeps no icon URL with a
 * visit, so there is none here: the favicon cache resolves the icon from the host instead.
 *
 * [visitCount] and [typedCount] are how often the page was opened at all, and how often its address
 * was typed out by hand — the browser's own measure of what the user uses. The search ranks with
 * them, alongside stramus's own count of what has been opened from here; a host with no such history
 * (the web app, where there is no history at all) simply reports zero.
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val lastVisit: Double,
    val visitCount: Int = 0,
    val typedCount: Int = 0,
)

/**
 * Platform capability for the user's browsing history. The web app passes `null` (a plain page has no
 * such access); the extension provides a `chrome.history`-backed implementation. When present, the
 * right sidebar can be switched from the open tabs to the history: the visited pages, searchable,
 * removable, and — like a tab — draggable onto a collection to be saved as a card.
 */
interface HistoryAccess {
    /**
     * The most recently visited pages matching [query] (blank = everything), at most [limit] of them.
     * The search is the browser's own: it matches the page title and its URL.
     */
    suspend fun search(query: String, limit: Int): List<HistoryEntry>

    /** Forget every visit to [url] — the browser's own "remove from history". */
    suspend fun deleteUrl(url: String)

    /** Subscribe to visits and removals so the list stays live; returns an unsubscribe. */
    fun onHistoryChanged(listener: () -> Unit): () -> Unit
}
