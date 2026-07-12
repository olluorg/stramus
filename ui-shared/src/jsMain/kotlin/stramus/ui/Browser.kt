package stramus.ui

// Minimal typed view of the global `window` — the wrappers' web.window.Window is strict about
// URL/WindowTarget types, and kotlinx-browser has no js-target artifact, so a tiny external keeps
// these calls simple. Works in a plain page and in an extension page alike.
private external interface BrowserWindow {
    fun prompt(message: String, default: String): String?
    fun open(url: String, target: String)
    fun getSelection(): JsSelection?
    val localStorage: JsStorage
    val document: JsDocument
}

private external interface JsSelection

private external interface JsStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
}

private external interface JsDocument {
    fun createElement(tag: String): JsElement
    fun execCommand(command: String, showUI: Boolean, value: String?): Boolean
    val body: JsElement
    val documentElement: JsElement
}

private external interface JsElement {
    fun setAttribute(name: String, value: String)
    fun click()
    fun appendChild(child: JsElement)
    fun removeChild(child: JsElement)
}

/** The global `encodeURIComponent`, used to build download data-URIs safely. */
private external fun encodeURIComponent(s: String): String

private fun browserWindow(): BrowserWindow = js("window")

internal fun browserPrompt(message: String, default: String = ""): String? =
    browserWindow().prompt(message, default)

internal fun openUrl(url: String) {
    browserWindow().open(url, "_blank")
}

/** Read a persisted UI preference (theme, sort, sidebar state) from localStorage. */
internal fun prefGet(key: String): String? = runCatching { browserWindow().localStorage.getItem(key) }.getOrNull()

/** Persist a UI preference to localStorage. */
internal fun prefSet(key: String, value: String) {
    runCatching { browserWindow().localStorage.setItem(key, value) }
}

/** Apply an explicit theme by stamping `data-theme` on <html>; "auto" clears it (OS decides). */
internal fun applyTheme(theme: String) {
    browserWindow().document.documentElement.setAttribute("data-theme", theme)
}

/** Run a `document.execCommand` — the WYSIWYG note editor's formatting (bold, links, lists, …). */
internal fun execCommand(command: String, value: String? = null) {
    runCatching { browserWindow().document.execCommand(command, false, value) }
}

/** The plain text currently selected in the page (used to wrap a selection in a highlight). */
internal fun selectionText(): String = browserWindow().getSelection()?.toString() ?: ""

/**
 * Trigger a client-side file download via a temporary anchor + data-URI (no Blob/URL APIs, which
 * differ across wrapper versions). Fine for the modest sizes of a link export.
 */
internal fun downloadFile(filename: String, mime: String, content: String) {
    val doc = browserWindow().document
    val a = doc.createElement("a")
    a.setAttribute("href", "data:$mime;charset=utf-8,${encodeURIComponent(content)}")
    a.setAttribute("download", filename)
    doc.body.appendChild(a)
    a.click()
    doc.body.removeChild(a)
}

/** Best-effort host extraction from a raw URL string, without constructing a JS URL. */
internal fun hostOf(url: String): String {
    val afterProto = if ("://" in url) url.substringAfter("://") else url
    return afterProto.substringBefore('/').substringBefore('?').removePrefix("www.").ifBlank { url }
}

internal fun faviconFor(url: String): String =
    "https://www.google.com/s2/favicons?domain=${hostOf(url)}&sz=64"
