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
    /** Pinned tabs are held at the head of the strip by the browser; a sort has to leave them there. */
    val pinned: Boolean?
    val title: String?
    val url: String?
    val favIconUrl: String?
}

internal external interface ChromeWindow {
    val id: Int?
}

/**
 * One visited page. `lastVisitTime` is epoch millis, as a JS number; `visitCount` and `typedCount`
 * are the browser's own tally of how much the page is used — how often it was opened, and how often
 * its address was typed out rather than clicked.
 */
internal external interface ChromeHistoryItem {
    val url: String?
    val title: String?
    val lastVisitTime: Double?
    val visitCount: Int?
    val typedCount: Int?
}

internal external interface ChromeEvent {
    fun addListener(callback: (Any?) -> Unit)
    fun removeListener(callback: (Any?) -> Unit)
}

internal external interface ChromeTabs {
    fun query(queryInfo: Json): Promise<Array<ChromeTab>>
    fun create(createProperties: Json): Promise<ChromeTab>
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

/**
 * `chrome.search` — a query put to the browser's *default* search engine, the one the user set. The
 * disposition says where the answer lands; "CURRENT_TAB" replaces this page, as the address bar does.
 */
internal external interface ChromeSearch {
    fun query(queryInfo: Json): Promise<Unit>
}

/**
 * `chrome.runtime` — here, only for [getURL]: the address of a file inside the extension, on the
 * extension's own origin (`chrome-extension://<id>/…`), which is not known until it is installed.
 */
internal external interface ChromeRuntime {
    fun getURL(path: String): String
}

/**
 * `chrome.identity` — the extension's own way through an OAuth flow. Chrome opens the window, follows the
 * redirects, and hands back the URL the provider finally landed on; [getRedirectURL] is the address it will
 * accept as that landing, and it exists only inside this extension.
 */
internal external interface ChromeIdentity {
    fun getRedirectURL(): String
    fun launchWebAuthFlow(options: dynamic): Promise<String>

    /**
     * The quieter door: an OAuth2 access token for the account already signed into Chrome itself, using
     * the `client_id` and `scopes` registered under `oauth2` in the manifest — nothing is passed in here.
     * `interactive: false` answers only if it can without drawing anything at all; with no session or no
     * prior consent it resolves to nothing rather than opening a window.
     */
    fun getAuthToken(details: dynamic): Promise<dynamic>
}

internal external interface Chrome {
    val tabs: ChromeTabs
    val windows: ChromeWindows
    val history: ChromeHistory
    val search: ChromeSearch
    val runtime: ChromeRuntime
    val identity: ChromeIdentity
}

internal external val chrome: Chrome
