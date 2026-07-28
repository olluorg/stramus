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

    override suspend fun reorderTabs(windowId: Int, ids: List<Int>) {
        // The window is read again rather than sorted from the caller's own indices: what a move needs
        // is the slots these tabs hold *now*, and the browser is the one that knows.
        val windowTabs = chrome.tabs.query(json("windowId" to windowId)).await().toList()
        // A pinned tab cannot be moved out of the pinned run at the head of the strip — the browser
        // would only clamp the index back — so it is not sorted, it is left exactly where it is.
        val movable = ids.filter { id -> windowTabs.any { it.id == id && it.pinned != true } }
        // The slots the sorted tabs land in are the ones they already occupy between them. Whatever
        // sits in the gaps — a pinned tab, a chrome:// page, this page — is not in the list and so is
        // never assigned a slot: it keeps its place while the tabs around it are rearranged.
        val slots = windowTabs
            .mapNotNull { tab -> tab.id?.let { id -> tab.index.takeIf { id in movable } } }
            .sorted()
        // Left to right, one slot at a time. `index` in chrome.tabs.move is where the tab ends up, so
        // each call plants one tab for good; the ones still to be placed are all further right and
        // simply shift along, which is why an earlier move cannot disturb a later one.
        movable.forEachIndexed { i, id ->
            chrome.tabs.move(id, json("windowId" to windowId, "index" to slots[i])).await()
        }
    }

    override suspend fun createTab(url: String) {
        // Background, not active: reopening a group is not meant to march the user's focus through
        // every tab it creates — they land behind, the way "open all bookmarks" leaves the folder up.
        chrome.tabs.create(json("url" to url, "active" to false)).await()
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
