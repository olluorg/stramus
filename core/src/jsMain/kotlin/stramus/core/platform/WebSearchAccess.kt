package stramus.core.platform

/**
 * The browser's own web search — the engine the *user* chose, whatever it is, rather than one this app
 * picked for them. In the extension this is `chrome.search`, which is the same search their address
 * bar performs; a plain web page has no way to ask, and falls back to a URL of its own.
 *
 * The search replaces the page it was typed on: the box sits on the new tab page, and a new tab page
 * that opens a *second* tab to answer a search is not what anyone means by searching.
 */
interface WebSearchAccess {
    suspend fun search(query: String)
}
