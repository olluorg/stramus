package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.CapturedTab
import stramus.core.platform.TabCapture
import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.json

// Minimal externals over the MV3 chrome.tabs API (query/remove return Promises in MV3; the events
// call listeners with tab-specific args we ignore).
private external interface ChromeTab {
    val id: Int?
    val title: String?
    val url: String?
    val favIconUrl: String?
}

private external interface ChromeEvent {
    fun addListener(callback: (Any?) -> Unit)
    fun removeListener(callback: (Any?) -> Unit)
}

private external interface ChromeTabs {
    fun query(queryInfo: Json): Promise<Array<ChromeTab>>
    fun remove(tabId: Int): Promise<Unit>
    val onCreated: ChromeEvent
    val onRemoved: ChromeEvent
    val onUpdated: ChromeEvent
}

private external interface Chrome {
    val tabs: ChromeTabs
}

private external val chrome: Chrome

/** chrome.tabs-backed [TabCapture]: reads/closes tabs and keeps the UI list live via tab events. */
object ChromeTabCapture : TabCapture {
    override suspend fun currentTabs(): List<CapturedTab> =
        chrome.tabs.query(json("currentWindow" to true)).await()
            .toList()
            .mapNotNull { tab ->
                val id = tab.id ?: return@mapNotNull null
                val url = tab.url ?: return@mapNotNull null
                // Skip chrome://, the stramus new-tab page itself, and other non-web pages.
                if (!url.startsWith("http")) return@mapNotNull null
                CapturedTab(id = id, title = tab.title ?: url, url = url, favicon = tab.favIconUrl)
            }

    override suspend fun closeTab(id: Int) {
        chrome.tabs.remove(id).await()
    }

    override fun onTabsChanged(listener: () -> Unit): () -> Unit {
        val callback: (Any?) -> Unit = { listener() }
        chrome.tabs.onCreated.addListener(callback)
        chrome.tabs.onRemoved.addListener(callback)
        chrome.tabs.onUpdated.addListener(callback)
        return {
            chrome.tabs.onCreated.removeListener(callback)
            chrome.tabs.onRemoved.removeListener(callback)
            chrome.tabs.onUpdated.removeListener(callback)
        }
    }
}
