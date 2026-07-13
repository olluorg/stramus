package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.WebSearchAccess
import kotlin.js.json

/**
 * chrome.search-backed [WebSearchAccess]: the query goes to whichever engine the user has set as their
 * default — Google, DuckDuckGo, Yandex, whatever the address bar would use — and the answer replaces
 * this page rather than opening another tab beside it.
 */
object ChromeWebSearch : WebSearchAccess {
    override suspend fun search(query: String) {
        chrome.search.query(json("text" to query, "disposition" to "CURRENT_TAB")).await()
    }
}
