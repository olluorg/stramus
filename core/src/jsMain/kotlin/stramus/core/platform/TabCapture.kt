package stramus.core.platform

/** A browser tab read from the host environment (extension via chrome.tabs; web has none). */
data class CapturedTab(
    val id: Int,
    val title: String,
    val url: String,
    val favicon: String?,
)

/**
 * Platform capability for the user's open tabs. The web app passes `null` (a plain page has no such
 * access); the extension provides a `chrome.tabs`-backed implementation. When present, the shared UI
 * shows the live "Open tabs" sidebar — drag a tab onto a collection to save it as a card and close it.
 */
interface TabCapture {
    /** The open tabs of the current window (http(s) only). */
    suspend fun currentTabs(): List<CapturedTab>

    /** Close the browser tab with [id] (called after it has been saved into a collection). */
    suspend fun closeTab(id: Int)

    /** Subscribe to tab open/close/navigate changes so the list stays live; returns an unsubscribe. */
    fun onTabsChanged(listener: () -> Unit): () -> Unit
}
