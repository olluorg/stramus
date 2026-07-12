package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.HistoryAccess
import stramus.core.platform.HistoryEntry
import kotlin.js.json

/**
 * chrome.history-backed [HistoryAccess]: searches the user's visited pages, removes one from the
 * browser's history, and keeps the UI list live via history events.
 */
object ChromeHistoryAccess : HistoryAccess {
    override suspend fun search(query: String, limit: Int): List<HistoryEntry> =
        // `startTime` has to be given: chrome.history.search searches only the last 24 hours without
        // it, which is not what "history" means here. 0 is the epoch — everything the browser kept.
        chrome.history.search(json("text" to query, "maxResults" to limit, "startTime" to 0)).await()
            .toList()
            .mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                // Same filter as the tabs list: chrome:// and other non-web pages are not bookmarkable.
                if (!url.startsWith("http")) return@mapNotNull null
                HistoryEntry(
                    url = url,
                    title = item.title ?: url,
                    lastVisit = item.lastVisitTime ?: 0.0,
                )
            }

    override suspend fun deleteUrl(url: String) {
        chrome.history.deleteUrl(json("url" to url)).await()
    }

    override fun onHistoryChanged(listener: () -> Unit): () -> Unit {
        val callback: (Any?) -> Unit = { listener() }
        val events = with(chrome.history) { listOf(onVisited, onVisitRemoved) }
        events.forEach { it.addListener(callback) }
        return { events.forEach { it.removeListener(callback) } }
    }
}
