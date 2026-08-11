package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.CapturedPage
import stramus.core.platform.QuickCaptureAccess

private const val PENDING_KEY = "stramus.pendingCaptures"

/**
 * chrome.storage.local-backed [QuickCaptureAccess]: reads what background.js staged for a keyboard
 * shortcut, right-click, or toolbar-button capture, and clears it once the app has turned each into
 * a card. See background.js for the writer's side of this queue.
 */
object ChromeQuickCapture : QuickCaptureAccess {
    override suspend fun pending(): List<CapturedPage> {
        val result = chrome.storage.local.get(PENDING_KEY).await()
        val raw = result[PENDING_KEY] as? Array<dynamic> ?: return emptyList()
        return raw.map { item ->
            CapturedPage(
                title = (item.title as? String).orEmpty(),
                url = item.url as String,
                favicon = item.favicon as? String,
            )
        }
    }

    override suspend fun clear() {
        chrome.storage.local.remove(PENDING_KEY).await()
    }

    override fun onCaptured(listener: () -> Unit): () -> Unit {
        val callback: (Any?) -> Unit = { listener() }
        chrome.runtime.onMessage.addListener(callback)
        return { chrome.runtime.onMessage.removeListener(callback) }
    }
}
