package stramus.ext

import kotlin.js.Json
import kotlin.js.Promise

// Minimal externals over the MV3 chrome APIs the extension uses — chrome.tabs, chrome.windows and
// chrome.history. The methods return Promises in MV3; the events call listeners with arguments we
// ignore — every one of them only means "the list changed, read it again". Promise<Unit> stands in
// for results we never look at.

internal external interface ChromeTab {
    val id: Int?
    val windowId: Int
    val index: Int
    val active: Boolean?
    val title: String?
    val url: String?
    val favIconUrl: String?
}

internal external interface ChromeWindow {
    val id: Int?
}

/** One visited page. `lastVisitTime` is epoch millis, as a JS number. */
internal external interface ChromeHistoryItem {
    val url: String?
    val title: String?
    val lastVisitTime: Double?
}

internal external interface ChromeEvent {
    fun addListener(callback: (Any?) -> Unit)
    fun removeListener(callback: (Any?) -> Unit)
}

internal external interface ChromeTabs {
    fun query(queryInfo: Json): Promise<Array<ChromeTab>>
    fun remove(tabId: Int): Promise<Unit>
    fun update(tabId: Int, updateProperties: Json): Promise<Unit>
    fun move(tabId: Int, moveProperties: Json): Promise<Unit>
    val onCreated: ChromeEvent
    val onRemoved: ChromeEvent
    val onUpdated: ChromeEvent
    val onMoved: ChromeEvent
    val onActivated: ChromeEvent
    val onAttached: ChromeEvent
    val onDetached: ChromeEvent
}

internal external interface ChromeWindows {
    fun getCurrent(): Promise<ChromeWindow>
    fun update(windowId: Int, updateInfo: Json): Promise<Unit>
}

internal external interface ChromeHistory {
    fun search(query: Json): Promise<Array<ChromeHistoryItem>>
    fun deleteUrl(details: Json): Promise<Unit>
    val onVisited: ChromeEvent
    val onVisitRemoved: ChromeEvent
}

internal external interface Chrome {
    val tabs: ChromeTabs
    val windows: ChromeWindows
    val history: ChromeHistory
}

internal external val chrome: Chrome
