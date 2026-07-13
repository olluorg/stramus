package stramus.ui

import react.dom.html.HTMLAttributes

// Minimal typed view of the global `window` — the wrappers' web.window.Window is strict about
// URL/WindowTarget types, and kotlinx-browser has no js-target artifact, so a tiny external keeps
// these calls simple. Works in a plain page and in an extension page alike.
private external interface BrowserWindow {
    fun prompt(message: String, default: String): String?
    fun confirm(message: String): Boolean
    fun open(url: String, target: String)
    fun getSelection(): JsSelection?
    val localStorage: JsStorage
    val document: JsDocument
    val navigator: JsNavigator
    val location: JsLocation
    val innerWidth: Int
    val innerHeight: Int
}

private external interface JsLocation {
    fun assign(url: String)
}

private external interface JsNavigator {
    val language: String?
    val clipboard: JsClipboard?
}

private external interface JsClipboard {
    fun writeText(text: String): dynamic
}

/** Put [text] on the clipboard. Best-effort: a page without clipboard permission simply copies nothing. */
internal fun copyToClipboard(text: String) {
    runCatching { browserWindow().navigator.clipboard?.writeText(text) }
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
    fun addEventListener(type: String, listener: (KeyStroke) -> Unit)
    fun removeEventListener(type: String, listener: (KeyStroke) -> Unit)
    val body: JsElement
    val documentElement: JsElement
}

/** A keystroke anywhere on the page — enough of one for the search box's global shortcuts. */
internal external interface KeyStroke {
    val key: String
    val ctrlKey: Boolean
    val metaKey: Boolean
    val altKey: Boolean

    /** What had the focus when the key was pressed; see [isTyping]. */
    val target: dynamic

    fun preventDefault()
}

/**
 * Watch every keystroke on the page; the returned function stops watching. This is how the search box
 * is reachable from anywhere (Ctrl/Cmd+K, or "/" as in every other page with a search) rather than
 * only by clicking it.
 */
internal fun onKeyStroke(handler: (KeyStroke) -> Unit): () -> Unit {
    val doc = browserWindow().document
    doc.addEventListener("keydown", handler)
    return { doc.removeEventListener("keydown", handler) }
}

/** Whether the keystroke landed in a field the user is writing in — where "/" is a slash, not a shortcut. */
internal fun isTyping(event: KeyStroke): Boolean {
    val target = event.target ?: return false
    val tag = (target.tagName as? String)?.lowercase()
    return tag == "input" || tag == "textarea" || target.isContentEditable == true
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

/**
 * The one question asked before a deletion that would take content with it. It is a stop, not a
 * safeguard — what actually protects the user is that every such deletion can be undone (see the undo
 * toast in `App.kt`), which is why an empty section or collection is deleted without asking at all.
 */
internal fun browserConfirm(message: String): Boolean = browserWindow().confirm(message)

internal fun openUrl(url: String) {
    browserWindow().open(url, "_blank")
}

/**
 * The app's own tooltip, in place of the browser's `title`: the same words, but on the screen a
 * fraction of a second after the pointer arrives rather than a second later, and in the app's own
 * colours. The text is handed to the CSS through `data-hint` (see `.hint` in index.html), which draws
 * it as the element's `::after`; the element itself only has to carry the `hint` class.
 *
 * It is also the accessible name of what it hangs on — a good half of these are one-glyph controls,
 * and a `⤓` alone tells a screen reader nothing. An empty [text] draws nothing and names nothing,
 * which is what a control whose tooltip depends on the state it is in ("" = say nothing here) needs.
 */
internal fun HTMLAttributes<*>.hint(text: String) {
    if (text.isBlank()) return
    asDynamic()["data-hint"] = text
    asDynamic()["aria-label"] = text
}

/** The box a tooltip belongs to, in viewport coordinates, and what it should say. */
internal data class HintTarget(
    val text: String,
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
)

/** The viewport, which is what a tooltip has to stay inside of. */
internal fun viewportWidth(): Double = browserWindow().innerWidth.toDouble()
internal fun viewportHeight(): Double = browserWindow().innerHeight.toDouble()

/**
 * Watch the page for the pointer coming to rest on anything carrying a `data-hint` (see [hint]), and
 * hand that element's box and text to [onTarget] — null when the pointer leaves it, when the page
 * scrolls under it, or when a click makes the question moot. The returned function stops the watch.
 *
 * It listens once, at the document, rather than at each of the several dozen elements involved: the
 * tooltip is drawn by one component at the root of the app (see `HintLayer`), and that is the point.
 * A tooltip drawn *inside* the control it belongs to is drawn inside whatever scrolls that control —
 * the tabs list, the sidebar, the content area — and a scroll box clips what leaves it, however high
 * the z-index. Only an element outside all of them, positioned against the viewport, escapes.
 */
internal fun onHintTarget(delayMs: Int, onTarget: (HintTarget?) -> Unit): () -> Unit {
    val doc = js("document")
    var pending: Int? = null

    fun cancelPending() {
        pending?.let { cancelDelay(it) }
        pending = null
    }

    fun hide() {
        cancelPending()
        onTarget(null)
    }

    // `closest` walks up from whatever was actually under the pointer (the glyph inside a button, the
    // title inside a row) to the element that carries the hint.
    val over: (dynamic) -> Unit = { event ->
        val target = event.target
        val el = if (target != null && target.closest != undefined) target.closest("[data-hint]") else null
        val text = el?.getAttribute("data-hint") as? String
        cancelPending()
        if (el == null || text.isNullOrBlank()) {
            onTarget(null)
        } else {
            // Held back a moment: a pointer crossing a list of tabs on its way elsewhere is not asking
            // about every row it passes over.
            pending = delay(delayMs) {
                pending = null
                val box = el.getBoundingClientRect()
                onTarget(
                    HintTarget(
                        text = text,
                        left = box.left as Double,
                        right = box.right as Double,
                        top = box.top as Double,
                        bottom = box.bottom as Double,
                    ),
                )
            }
        }
    }
    val away: (dynamic) -> Unit = { hide() }

    doc.addEventListener("pointerover", over)
    doc.addEventListener("pointerdown", away)
    // A scroll moves the control out from under its own tooltip, so the tooltip goes. Captured, or a
    // scroll inside the tabs list — which does not bubble — would never be heard.
    doc.addEventListener("scroll", away, true)
    return {
        cancelPending()
        doc.removeEventListener("pointerover", over)
        doc.removeEventListener("pointerdown", away)
        doc.removeEventListener("scroll", away, true)
    }
}

/**
 * Go to [url] in *this* tab, leaving stramus behind. This is what a search does: the box sits on the
 * new tab page, and a new tab page that opens a second tab to answer a search is not what searching
 * means. A saved card, by contrast, is opened beside the app ([openUrl]) — the app is where the user
 * still is.
 */
internal fun navigateTo(url: String) {
    browserWindow().location.assign(url)
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
