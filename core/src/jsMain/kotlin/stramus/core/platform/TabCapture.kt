package stramus.core.platform

/**
 * A browser tab read from the host environment (extension via chrome.tabs; web has none).
 *
 * [windowId] and [index] locate the tab in the browser: which window it belongs to, and its position
 * in that window's tab strip. Both are the browser's own ids — they are what a move is expressed in,
 * so the UI never has to translate its own list positions back into browser positions.
 */
data class CapturedTab(
    val id: Int,
    val windowId: Int,
    val index: Int,
    val title: String,
    val url: String,
    val favicon: String?,
    /** The tab currently selected in its window — one per window, marked in the list. */
    val active: Boolean,
)

/**
 * Platform capability for the user's open tabs. The web app passes `null` (a plain page has no such
 * access); the extension provides a `chrome.tabs`-backed implementation. When present, the shared UI
 * shows the live "Open tabs" sidebar: tabs of every window, grouped by window, which can be searched,
 * jumped to, reordered, closed, or dragged onto a collection to be saved as a card.
 */
interface TabCapture {
    /** The open tabs of *every* window (http(s) only), each carrying its window and position. */
    suspend fun currentTabs(): List<CapturedTab>

    /** The window this page is displayed in, so its group can be told apart from the other windows. */
    suspend fun currentWindowId(): Int

    /** Close the browser tab with [id] (after saving it into a collection, or from the × button). */
    suspend fun closeTab(id: Int)

    /** Focus [windowId] and select the tab with [id] in it — "take me to this tab". */
    suspend fun activateTab(id: Int, windowId: Int)

    /**
     * Move the tab with [id] to [index] in [windowId] — reordering within a window and dragging a tab
     * across windows are the same call. [index] is a position in that window's own tab strip; -1 puts
     * the tab last.
     */
    suspend fun moveTab(id: Int, windowId: Int, index: Int)

    /** Subscribe to tab open/close/move/navigate changes so the list stays live; returns an unsubscribe. */
    fun onTabsChanged(listener: () -> Unit): () -> Unit
}
