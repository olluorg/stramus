package stramus.ui

import react.ChildrenBuilder
import react.Key
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import stramus.core.platform.HistoryEntry
import stramus.core.url.hostOf
import web.cssom.ClassName

/** How many visited pages the history pane asks the browser for — the last few days of browsing. */
internal const val HISTORY_LIMIT = 300

/** Which list the right sidebar shows: the live open tabs, or the browsing history. */
internal enum class RightPane(val id: String) {
    TABS("tabs"),
    HISTORY("history"),
    ;

    companion object {
        fun from(id: String?): RightPane = entries.firstOrNull { it.id == id } ?: TABS
    }
}

/** `Date`, enough of it to bucket a visit into a local day and to label it in the UI language. */
@JsName("Date")
private external class JsDate(millis: Double) {
    fun getFullYear(): Int
    fun getMonth(): Int
    fun getDate(): Int
    fun toLocaleDateString(locales: String, options: dynamic): String
    fun toLocaleTimeString(locales: String, options: dynamic): String

    companion object {
        fun now(): Double
    }
}

private const val DAY_MS = 86_400_000.0

/**
 * The local calendar day of [millis], as a number that both keys a day group and orders it: 20260712.
 * Local, not UTC — a visit belongs to the day the user had on their clock, not the one in Greenwich.
 */
private fun dayCode(millis: Double): Int {
    val date = JsDate(millis)
    return date.getFullYear() * 10000 + (date.getMonth() + 1) * 100 + date.getDate()
}

private fun dayLabel(millis: Double, strings: Strings, locale: String): String {
    val now = JsDate.now()
    return when (dayCode(millis)) {
        dayCode(now) -> strings.today
        dayCode(now - DAY_MS) -> strings.yesterday
        else -> JsDate(millis)
            .toLocaleDateString(locale, js("({ day: 'numeric', month: 'long', year: 'numeric' })"))
    }
}

private fun timeLabel(millis: Double, locale: String): String =
    JsDate(millis).toLocaleTimeString(locale, js("({ hour: '2-digit', minute: '2-digit' })"))

/**
 * The history side of the right sidebar: the visited pages, newest first, grouped by the day they
 * were last opened — the day is to history what the window is to the open tabs, which is why the two
 * panes share their markup and styling.
 *
 * A row opens the page in a new tab; its × forgets the page in the browser's own history; and it is a
 * drag source, so a page can be dropped on a collection or a card section to be saved as a card. It
 * is no drop *target*, though — nothing can be filed into a history that only records what happened.
 *
 * The search is not done here: the query goes to the browser, which matches it against every visit it
 * kept — far more than the pane holds — and [entries] is already the result.
 */
internal fun ChildrenBuilder.historyPane(
    strings: Strings,
    locale: String,
    entries: List<HistoryEntry>,
    draggingUrl: String?,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onStartDrag: (HistoryEntry) -> Unit,
    onEndDrag: () -> Unit,
) {
    div {
        className = ClassName("tabs-body")
        // Sorted before grouping, so the days come out newest first and so do the pages within a day.
        entries.sortedByDescending { it.lastVisit }
            .groupBy { dayCode(it.lastVisit) }
            .forEach { (code, visits) ->
                div {
                    key = code.toString().unsafeCast<Key>()
                    className = ClassName("tab-window")
                    div {
                        className = ClassName("tab-window-head")
                        span { +dayLabel(visits.first().lastVisit, strings, locale) }
                        span { className = ClassName("count"); +visits.size.toString() }
                    }
                    ul {
                        className = ClassName("tab-list")
                        visits.forEach { entry ->
                            li {
                                key = entry.url.unsafeCast<Key>()
                                className = ClassName(
                                    if (draggingUrl == entry.url) "tab history dragging" else "tab history",
                                )
                                hint(entry.title.ifBlank { entry.url })
                                draggable = true
                                tabIndex = 0
                                onClick = { onOpen(entry) }
                                onKeyDown = { e ->
                                    if (e.key == "Enter" || e.key == " ") { e.preventDefault(); onOpen(entry) }
                                }
                                onDragStart = { e ->
                                    // Some browsers require drag data to be set or they reject drops.
                                    e.dataTransfer.setData("text/plain", entry.url)
                                    onStartDrag(entry)
                                }
                                onDragEnd = { onEndDrag() }
                                Favicon {
                                    url = entry.url
                                    favicon = null // a visit carries no icon URL; the cache resolves it
                                }
                                span {
                                    className = ClassName("tab-title")
                                    +entry.title.ifBlank { hostOf(entry.url) }
                                }
                                span {
                                    className = ClassName("history-time")
                                    +timeLabel(entry.lastVisit, locale)
                                }
                                button {
                                    className = ClassName("icon del")
                                    hint(strings.removeFromHistory)
                                    onClick = { e ->
                                        e.stopPropagation() // forgetting the page is not opening it
                                        onDelete(entry)
                                    }
                                    +"×"
                                }
                            }
                        }
                    }
                }
            }
    }
}
