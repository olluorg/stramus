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
    val navigator: JsNavigator
}

private external interface JsNavigator {
    val language: String?
}

private external interface JsSelection

private external interface JsStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
}

private external interface JsDocument {
    fun createElement(tag: String): JsElement
    fun execCommand(command: String, showUI: Boolean, value: String?): Boolean
    fun addEventListener(type: String, listener: () -> Unit)
    fun removeEventListener(type: String, listener: () -> Unit)
    val body: JsElement
    val documentElement: JsElement
}

private external interface JsElement {
    fun setAttribute(name: String, value: String)
    fun click()
    fun appendChild(child: JsElement)
    fun removeChild(child: JsElement)
}

/** The global `encodeURIComponent`, used to build download and placeholder data-URIs safely. */
internal external fun encodeURIComponent(s: String): String

private external fun setTimeout(handler: () -> Unit, timeout: Int): Int

private external fun clearTimeout(id: Int)

/** Run [action] after [delayMs]; the returned handle cancels it via [cancelDelay]. */
internal fun delay(delayMs: Int, action: () -> Unit): Int = setTimeout(action, delayMs)

internal fun cancelDelay(handle: Int) {
    clearTimeout(handle)
}

private fun browserWindow(): BrowserWindow = js("window")

/** What counts as the user still being here: anything they do with a pointer, a key or a wheel. */
private val ACTIVITY_EVENTS = listOf("mousedown", "mousemove", "keydown", "wheel", "touchstart")

/**
 * Run [action] once [idleMs] have passed with no sign of the user — every event in [ACTIVITY_EVENTS]
 * starts the wait over. This is what re-locks an unlocked section on an unattended machine, so the
 * countdown must be about the person, not about the app: a background tab refresh is not presence.
 *
 * The returned function stops the watch (and cancels a pending [action]); the caller owns it.
 */
internal fun onIdle(idleMs: Int, action: () -> Unit): () -> Unit {
    val doc = browserWindow().document
    var handle = delay(idleMs, action)
    val restart: () -> Unit = {
        cancelDelay(handle)
        handle = delay(idleMs, action)
    }
    ACTIVITY_EVENTS.forEach { doc.addEventListener(it, restart) }
    return {
        cancelDelay(handle)
        ACTIVITY_EVENTS.forEach { doc.removeEventListener(it, restart) }
    }
}

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

/** The browser's preferred language tag ("ru-RU", "en-US", …), lowercased; "" if unavailable. */
internal fun browserLanguage(): String =
    runCatching { browserWindow().navigator.language }.getOrNull()?.lowercase() ?: ""

/** Stamp the chosen UI language on <html lang> so screen readers and spellcheck follow it. */
internal fun applyLang(lang: String) {
    browserWindow().document.documentElement.setAttribute("lang", lang)
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
