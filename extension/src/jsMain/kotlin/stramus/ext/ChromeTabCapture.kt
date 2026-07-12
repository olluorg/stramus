package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.CapturedTab
import stramus.core.platform.TabCapture
import kotlin.js.json

/**
 * chrome.tabs-backed [TabCapture]: reads, activates, reorders and closes the user's tabs, and keeps
 * the UI list live via tab events.
 */
object ChromeTabCapture : TabCapture {
    override suspend fun currentTabs(): List<CapturedTab> =
        // Every *normal* window, not just this one — the sidebar shows one group per window. Popups,
        // devtools and app windows are not part of the user's tab strip, so they are left out.
        chrome.tabs.query(json("windowType" to "normal")).await()
            .toList()
            .mapNotNull { tab ->
                val id = tab.id ?: return@mapNotNull null
                val url = tab.url ?: return@mapNotNull null
                // Skip chrome://, the stramus new-tab page itself, and other non-web pages.
                if (!url.startsWith("http")) return@mapNotNull null
                CapturedTab(
                    id = id,
                    windowId = tab.windowId,
                    index = tab.index,
                    title = tab.title ?: url,
                    url = url,
                    favicon = tab.favIconUrl,
                    active = tab.active == true,
                )
            }

    override suspend fun currentWindowId(): Int = chrome.windows.getCurrent().await().id ?: -1

    override suspend fun closeTab(id: Int) {
        chrome.tabs.remove(id).await()
    }

    override suspend fun activateTab(id: Int, windowId: Int) {
        // Selecting the tab is not enough when it lives in another window: that window also has to be
        // brought to the front, or the tab would be selected out of sight.
        chrome.tabs.update(id, json("active" to true)).await()
        chrome.windows.update(windowId, json("focused" to true)).await()
    }

    override suspend fun moveTab(id: Int, windowId: Int, index: Int) {
        chrome.tabs.move(id, json("windowId" to windowId, "index" to index)).await()
    }

    override fun onTabsChanged(listener: () -> Unit): () -> Unit {
        val callback: (Any?) -> Unit = { listener() }
        val events = with(chrome.tabs) {
            listOf(onCreated, onRemoved, onUpdated, onMoved, onActivated, onAttached, onDetached)
        }
        events.forEach { it.addListener(callback) }
        return { events.forEach { it.removeListener(callback) } }
    }
}
