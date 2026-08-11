package stramus.core.platform

/**
 * A page staged by the background service worker — the keyboard shortcut, the right-click menu, or
 * the toolbar button — before the app was there to turn it into a card. [title] is blank for a link
 * captured by its address alone; the caller falls back to the page's host the same way a blank tab
 * title already does.
 */
data class CapturedPage(
    val title: String,
    val url: String,
    val favicon: String?,
)

/**
 * Platform capability for captures made outside the app itself. Present only in the extension, whose
 * background service worker can run without a stramus tab open; null in the web app, which has no
 * background page to stage anything in.
 */
interface QuickCaptureAccess {
    /** Everything staged since the last reconciliation, oldest first. */
    suspend fun pending(): List<CapturedPage>

    /** Drop what [pending] returned — called once each capture is safely a card. */
    suspend fun clear()

    /** Fires when the background worker stages a new capture while this page is open. */
    fun onCaptured(listener: () -> Unit): () -> Unit
}
