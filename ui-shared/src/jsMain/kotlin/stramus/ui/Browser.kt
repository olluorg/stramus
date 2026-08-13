package stramus.ui

import react.dom.html.HTMLAttributes
import kotlin.math.abs
import stramus.core.url.hostOf

// Minimal typed view of the global `window` — the wrappers' web.window.Window is strict about
// URL/WindowTarget types, and kotlinx-browser has no js-target artifact, so a tiny external keeps
// these calls simple. Works in a plain page and in an extension page alike.
private external interface BrowserWindow {
    fun addEventListener(type: String, listener: () -> Unit)
    fun removeEventListener(type: String, listener: () -> Unit)
    fun prompt(message: String, default: String): String?
    fun confirm(message: String): Boolean
    fun alert(message: String)
    fun open(url: String, target: String)
    fun getSelection(): JsSelection?
    fun matchMedia(query: String): JsMediaQuery
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

/** A media query being watched — "is the OS in dark mode", and nothing else so far. */
private external interface JsMediaQuery {
    val matches: Boolean
}

private external interface JsStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
    fun key(index: Int): String?
    val length: Int
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

private val ARROW_KEYS = arrayOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")

/**
 * The page-wide fallback for an arrow key pressed while no card has the focus to move from — right
 * after the page loads, after closing a note, or with a sidebar row focused instead of a card — which
 * puts the focus on the very first card instead of doing nothing. A card already focused has its own
 * meaning for arrow keys (see [moveCardFocus]) and is left alone; so is a field the user is typing in
 * ([isTyping]) and a modal (its own content, not the page behind it, is what the keyboard should
 * reach while one is open).
 *
 * Returns whether it acted, so the caller knows whether to also prevent the key's default action
 * (page scroll, mainly).
 */
internal fun focusFirstCardOnArrow(event: KeyStroke): Boolean {
    if (event.key !in ARROW_KEYS) return false
    if (isTyping(event)) return false
    val doc = js("document")
    if (doc.querySelector(".modal-backdrop") != null) return false
    val active = doc.activeElement
    if (active != null && active.classList?.contains("card") == true) return false
    val first = doc.querySelector(".card") ?: return false
    first.focus()
    return true
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

private external fun setInterval(handler: () -> Unit, timeout: Int): Int

private external fun clearInterval(id: Int)

/** Run [action] every [everyMs] until [cancelRepeat] is called with the handle. */
internal fun repeatEvery(everyMs: Int, action: () -> Unit): Int = setInterval(action, everyMs)

internal fun cancelRepeat(handle: Int) {
    clearInterval(handle)
}

private fun browserWindow(): BrowserWindow = js("window")

/**
 * Run [action] whenever this window is brought back to the front, and stop when the returned function is
 * called. What makes a tab left open overnight already in step by the time the user has read the first
 * line, rather than a minute later.
 */
internal fun onWindowFocus(action: () -> Unit): () -> Unit {
    val w = browserWindow()
    w.addEventListener("focus", action)
    return { w.removeEventListener("focus", action) }
}

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

/**
 * Said, not asked: what a drop did *not* take in — a file too big for the database, most often — and
 * why. There is nothing here to decide, and the rest of the drop has landed already.
 */
internal fun browserAlert(message: String) {
    browserWindow().alert(message)
}

/** Give an element the keyboard focus, by the id [focusElementById] looks it up by. Missing is silent —
 *  the row it belonged to has left the page (a section collapsed under it, most often). */
internal fun focusElementById(id: String) {
    val el = js("document").getElementById(id)
    if (el != null) el.focus()
}

/** The number of columns [grid] (a `.grid`) is *currently* laid out in, read live from its computed
 *  style rather than assumed, since `repeat(auto-fill, …)` (see `.grid` in index.html) picks the
 *  count from the viewport width, not from anything this code decides. */
private fun gridColumnCount(grid: dynamic): Int =
    (browserWindow().asDynamic().getComputedStyle(grid).gridTemplateColumns as String)
        .trim().split(" ").count { it.isNotBlank() }.coerceAtLeast(1)

/**
 * Moves focus from [current] (a `.card`, identified by its own [currentId]) to the sibling implied
 * by an arrow key. Left/Right step through DOM order within the card's own `.grid`, which is also
 * reading order. Up/Down move by a row within that grid — but a collection is drawn as one `.grid`
 * per group (the ungrouped area, then each named section; see `cardGroup` in `App.kt`) and the open
 * tabs sidebar as one per window (`.tab-window`; see `tabWindow`), each stacked one under another —
 * a card grid does not know what is above or below its own borders. So Up/Down that would fall off
 * the top or bottom edge instead cross into the next/previous group sibling that actually holds
 * cards (an empty one is skipped, not landed in) — the top row of the
 * one below, or the bottom row of the one above, at the same column where that lands inside it.
 *
 * Matched by `id` rather than by comparing the elements themselves: a `NodeList` freshly queried
 * from the document and the event's own `currentTarget` are two separate host-object references to
 * (what should be) the same node, and nothing here needs to lean on that holding — an id round-trips
 * through a plain string instead.
 *
 * Returns false at the very top or bottom of the collection (no further group to cross into) or off
 * either side of a row, which leaves the key to do whatever it otherwise would (scroll the page,
 * mainly) — an edge is not a wall except where there truly is nothing past it.
 */
internal fun moveCardFocus(current: dynamic, currentId: String, key: String): Boolean {
    val grid = current.closest(".grid")
    if (grid == null) return false
    val cards: dynamic = grid.querySelectorAll(":scope > .card")
    val n = cards.length as Int
    var idx = -1
    for (i in 0 until n) {
        if ((cards.item(i).id as String) == currentId) {
            idx = i
            break
        }
    }
    if (idx < 0) return false

    // A group laid out in columns (see `.grid.covers`) is not filled row by row: cards run top to
    // bottom and how many fit in a column is whatever the browser's balancing decided. No arithmetic on
    // the index can name the card below, so that layout is navigated by where the cards actually are.
    if ((grid.classList?.contains("covers") as? Boolean) == true) {
        if (focusNearestCard(cards, idx, key)) return true
        // Nothing further this way inside the group. Only up and down carry on into the next one.
        if (key != "ArrowDown" && key != "ArrowUp") return false
        val group = current.closest(".card-group, .tab-window") ?: return false
        return focusAdjacentGroup(group, 0, forward = key == "ArrowDown")
    }

    val columns = gridColumnCount(grid)
    when (key) {
        "ArrowRight" -> if (idx + 1 < n) { cards.item(idx + 1).focus(); return true }
        "ArrowLeft" -> if (idx - 1 >= 0) { cards.item(idx - 1).focus(); return true }
        "ArrowDown" -> {
            val target = idx + columns
            if (target < n) { cards.item(target).focus(); return true }
            val group = current.closest(".card-group, .tab-window") ?: return false
            return focusAdjacentGroup(group, idx % columns, forward = true)
        }
        "ArrowUp" -> {
            val target = idx - columns
            if (target >= 0) { cards.item(target).focus(); return true }
            val group = current.closest(".card-group, .tab-window") ?: return false
            return focusAdjacentGroup(group, idx % columns, forward = false)
        }
    }
    return false
}

/**
 * Focus the card nearest to the one at [idx] in the direction [key] points, by where the cards are drawn.
 *
 * The arithmetic in [moveCardFocus] assumes a grid filled row by row, where "down" is a fixed number of
 * cards along. This makes no assumption about the layout at all, which is what a column-balanced group
 * needs. Returns false when there is nothing that way, leaving the caller to decide what that means.
 */
private fun focusNearestCard(cards: dynamic, idx: Int, key: String): Boolean {
    val from = cards.item(idx).getBoundingClientRect()
    val fromX = ((from.left as Double) + (from.right as Double)) / 2
    val fromY = ((from.top as Double) + (from.bottom as Double)) / 2
    var best: dynamic = null
    var bestScore = Double.MAX_VALUE
    for (i in 0 until (cards.length as Int)) {
        if (i == idx) continue
        val box = cards.item(i).getBoundingClientRect()
        val dx = ((box.left as Double) + (box.right as Double)) / 2 - fromX
        val dy = ((box.top as Double) + (box.bottom as Double)) / 2 - fromY
        // `along` is how far the candidate lies the way the key points, `across` how far it drifts off
        // that line. A candidate not ahead at all is not a candidate, and the threshold rather than zero
        // keeps two cards sharing a row from counting as being above one another.
        val (along, across) = when (key) {
            "ArrowRight" -> dx to dy
            "ArrowLeft" -> -dx to dy
            "ArrowDown" -> dy to dx
            "ArrowUp" -> -dy to dx
            else -> return false
        }
        if (along <= 1.0) continue
        // Of two cards equally far ahead, the one more nearly straight ahead is the one meant — hence
        // the drift counting for more than the distance does.
        val score = along + 2.0 * abs(across)
        if (score < bestScore) {
            bestScore = score
            best = cards.item(i)
        }
    }
    if (best == null) return false
    best.focus()
    return true
}

/**
 * Walks from [fromGroup] to the next (`forward`) or previous `.card-group` sibling that has any
 * cards in it, and focuses the one at column [col] in its near edge — the top row going forward, the
 * bottom row going backward, which is the row [moveCardFocus] is arriving from in either direction.
 * A group with no cards at all (nothing dropped into it yet) is passed straight through.
 */
private fun focusAdjacentGroup(fromGroup: dynamic, col: Int, forward: Boolean): Boolean {
    var sib = if (forward) fromGroup.nextElementSibling else fromGroup.previousElementSibling
    while (sib != null) {
        val grid = sib.querySelector(".grid")
        if (grid != null) {
            val cards: dynamic = grid.querySelectorAll(":scope > .card")
            val n = cards.length as Int
            if (n > 0) {
                val columns = gridColumnCount(grid)
                val targetIdx = if (forward) {
                    col.coerceAtMost(n - 1)
                } else {
                    val lastRowStart = ((n - 1) / columns) * columns
                    (lastRowStart + col).coerceAtMost(n - 1)
                }
                cards.item(targetIdx).focus()
                return true
            }
        }
        sib = if (forward) sib.nextElementSibling else sib.previousElementSibling
    }
    return false
}

/** Open a page beside the app: a saved card is followed, but the collection it came from stays up. */
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

/**
 * The box a hovered element occupies, in viewport coordinates, and what the thing drawn over it says.
 *
 * [text] is whichever attribute was watched for — the words for a tooltip, the picture for a preview —
 * and [caption] is always the element's `data-hint`, so a preview can put the tooltip's own words under
 * the picture instead of leaving the two to fight over the same corner of the screen.
 */
internal data class HintTarget(
    val text: String,
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    val caption: String? = null,
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
 *
 * An element that also carries a `data-preview` is passed over: it has a picture to show (see
 * `PreviewLayer`), and the words go under that picture rather than into a second popup beside it.
 */
internal fun onHintTarget(delayMs: Int, onTarget: (HintTarget?) -> Unit): () -> Unit =
    onHoverTarget(delayMs, HINT_ATTR, skipAttr = PREVIEW_ATTR, onTarget = onTarget)

/** The attribute [hint] writes the tooltip's words into, and the one a hover preview's picture goes in. */
internal const val HINT_ATTR = "data-hint"
internal const val PREVIEW_ATTR = "data-preview"

/**
 * As [onHintTarget], but for any attribute: [attr] is what makes an element worth watching and what its
 * [HintTarget.text] is read from, and an element carrying [skipAttr] is ignored as though it carried
 * nothing at all. The one watch behind both the tooltips and the hover previews — same delay-then-measure
 * shape, same reasons for living at the document rather than on the elements.
 */
internal fun onHoverTarget(
    delayMs: Int,
    attr: String,
    skipAttr: String? = null,
    onTarget: (HintTarget?) -> Unit,
): () -> Unit {
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
        val found = if (target != null && target.closest != undefined) target.closest("[$attr]") else null
        // Ignored rather than merely unmatched: `closest` would otherwise walk past it to an ancestor
        // that does carry the attribute, and answer with a box the pointer is nowhere near.
        val skipped = found != null && skipAttr != null && found.hasAttribute(skipAttr) as Boolean
        val el = if (skipped) null else found
        val text = el?.getAttribute(attr) as? String
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
                        caption = (el.getAttribute(HINT_ATTR) as? String)?.takeIf { it != text },
                    ),
                )
            }
        }
    }
    val away: (dynamic) -> Unit = { hide() }

    doc.addEventListener("pointerover", over)
    // Keyboard focus asks the same question a pointer resting on the control does — without it, a
    // control identified only by a glyph (⤓, ✎, ×…) says nothing to anyone tabbing through it who
    // is not also running a screen reader (which reads the `aria-label` [hint] sets regardless).
    doc.addEventListener("focusin", over)
    doc.addEventListener("pointerdown", away)
    doc.addEventListener("focusout", away)
    // A scroll moves the control out from under its own tooltip, so the tooltip goes. Captured, or a
    // scroll inside the tabs list — which does not bubble — would never be heard.
    doc.addEventListener("scroll", away, true)
    return {
        cancelPending()
        doc.removeEventListener("pointerover", over)
        doc.removeEventListener("focusin", over)
        doc.removeEventListener("pointerdown", away)
        doc.removeEventListener("focusout", away)
        doc.removeEventListener("scroll", away, true)
    }
}

/**
 * Go to [url] in *this* tab, leaving stramus behind. This is what opening anything from the app means:
 * the app sits on the new tab page, and a new tab page that opens a *second* tab to answer a click is
 * not what opening a link means — the tab the user is in is the tab they meant. It holds for a search
 * from the box and for a saved card alike; the app is one back-button away either way.
 */
internal fun navigateTo(url: String) {
    browserWindow().location.assign(url)
}

/** Read a persisted UI preference (theme, sort, sidebar state) from localStorage. */
internal fun prefGet(key: String): String? = runCatching { browserWindow().localStorage.getItem(key) }.getOrNull()

/**
 * Persist a UI preference to localStorage. Returns whether it was actually stored — which all but one
 * caller ignores, since a theme or a sidebar fold that failed to save is not worth a word to anyone.
 * The wallpaper is the exception: it is the one preference big enough to be turned away for want of
 * room, and the user has to be told that the picture they picked did not stay.
 */
internal fun prefSet(key: String, value: String): Boolean =
    runCatching { browserWindow().localStorage.setItem(key, value) }.isSuccess

/** Forget a persisted value — a note draft that has been saved or discarded (see `Drafts.kt`). */
internal fun prefRemove(key: String) {
    runCatching { browserWindow().localStorage.removeItem(key) }
}

/**
 * Every key currently in localStorage, read out in one pass before anything is removed: deleting while
 * walking the store by index skips whatever slid into the gap.
 */
internal fun prefKeys(): List<String> = runCatching {
    val storage = browserWindow().localStorage
    (0 until storage.length).mapNotNull { storage.key(it) }
}.getOrDefault(emptyList())

/**
 * Stamp a CSS custom property on `<html>` — the one way a theme picked at runtime reaches a stylesheet
 * written months earlier. An inline property on the root beats every rule in the sheet, so index.html's
 * own palette stands only until this runs. See `Theme.kt`.
 */
internal fun setRootVar(name: String, value: String) {
    runCatching { js("document.documentElement").style.setProperty(name, value) }
}

/**
 * How tall a plain card actually comes out, measured from one on the page. This is what `--card-h` —
 * the number the covers grid divides its rows by — ought to be, and index.html's value is only a
 * considered guess at it: a card is padding, a border and two lines of text, and how tall those two
 * lines are depends on the font the machine actually had. Null where there is no card to measure.
 *
 * `scrollHeight`, not the card's box: in a covers grid the card is already being held to whatever
 * `--card-h` currently says, so measuring the box would only read the guess back. The content extent is
 * how tall the card would be if nothing held it, which is the question. The border is added back on
 * because `scrollHeight` leaves it out and the card is `border-box`.
 */
internal fun measureCardHeight(): Double? = runCatching {
    // Any plain card in the content area, not only one in a covers grid: a group made entirely of
    // videos has no plain card to measure, and the groups around it do — they are the same card either
    // way. The sidebar's own card grid is left out of it, being a different shape.
    val el = js("document.querySelector('.content .grid .card:not(.has-cover)')") ?: return null
    val content = (el.scrollHeight as? Number)?.toDouble() ?: return null
    val styles = js("window").getComputedStyle(el)
    val border = edge(styles.borderTopWidth) + edge(styles.borderBottomWidth)
    (content + border).takeIf { it > 0.0 }
}.getOrNull()

private fun edge(value: dynamic): Double = (js("parseFloat")(value) as? Number)?.toDouble() ?: 0.0

/** Stamp — or, with a null [value], clear — a `data-*` attribute on `<html>`. */
internal fun setRootAttribute(name: String, value: String?) {
    runCatching {
        val root = js("document.documentElement")
        if (value == null) root.removeAttribute(name) else root.setAttribute(name, value)
    }
}

/** The OS's own answer to light or dark — what the "auto" theme follows. See [Appearance.isDark]. */
internal fun systemPrefersDark(): Boolean =
    runCatching { browserWindow().matchMedia("(prefers-color-scheme: dark)").matches }.getOrDefault(false)

/**
 * Call [onChange] whenever the OS switches between light and dark, and hand back the way to stop
 * listening. Only "auto" subscribes: a theme the user picked outright does not care what the OS is
 * doing, and a listener left running would repaint for nothing.
 */
internal fun onSystemThemeChange(onChange: () -> Unit): () -> Unit {
    val media = runCatching { browserWindow().matchMedia("(prefers-color-scheme: dark)") }.getOrNull()
        ?: return {}
    val handler: (dynamic) -> Unit = { onChange() }
    media.asDynamic().addEventListener("change", handler)
    return { runCatching { media.asDynamic().removeEventListener("change", handler) } }
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

/**
 * The icon URL *stored* with a card whose page offered none of its own. It is a stored value, not a
 * source: what is actually fetched is decided by [IconSources], whose chain starts at the browser's own
 * store and then at our server, and reaches this service only as a last resort. The URL is kept all the
 * same, as a hint that travels with an exported collection and costs nothing when it is not used.
 */
internal fun faviconFor(url: String): String =
    "https://www.google.com/s2/favicons?domain=${hostOf(url)}&sz=64"
