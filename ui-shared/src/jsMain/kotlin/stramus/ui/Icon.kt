package stramus.ui

import react.ChildrenBuilder
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Every glyph in the app that is not text is one of these: a small inline-SVG line icon, drawn in
 * `currentColor` so it always matches the button or label it sits in, in both themes, on every
 * platform — which a pictographic character (🔒, ✎, ⚙…) never quite does, since each OS ships its own
 * drawing of it and some of them are in full colour regardless of what the surrounding text says.
 *
 * 24×24 viewBox, a single stroke width, round caps and joins throughout: one small, consistent set
 * rather than a grab bag of borrowed icon fonts.
 */
private const val STROKE = "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" " +
    "stroke-linecap=\"round\" stroke-linejoin=\"round\""

private fun svg(inner: String): String = """<svg viewBox="0 0 24 24" $STROKE>$inner</svg>"""

private val ICONS: Map<String, String> = mapOf(
    "lock" to svg(
        """<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>""",
    ),
    "unlock" to svg(
        """<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V8a4 4 0 0 1 7.4-2"/>""",
    ),
    "edit" to svg(
        """<path d="M4 20l1-4L16 5l3 3L8 19l-4 1z"/><path d="M14 6l3 3"/>""",
    ),
    "x" to svg("""<path d="M6 6l12 12M18 6L6 18"/>"""),
    "settings" to svg(
        """<polygon points="12,3 19,7.5 19,16.5 12,21 5,16.5 5,7.5"/><circle cx="12" cy="12" r="3"/>""",
    ),
    "sparkles" to svg(
        """<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>""" +
            """<path d="M18.5 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>""",
    ),
    "sun" to svg(
        """<circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2.5M12 19v2.5M3.8 12h2.5M17.7 12h2.5""" +
            """M5.8 5.8l1.8 1.8M16.4 16.4l1.8 1.8M18.2 5.8l-1.8 1.8M7.6 16.4l-1.8 1.8"/>""",
    ),
    "moon" to svg("""<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>"""),
    "circle-half" to svg(
        """<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor" stroke="none"/>""",
    ),
    "search" to svg("""<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.3-4.3"/>"""),
    "folder" to svg("""<path d="M4 6h6l2 2h8v10H4z"/>"""),
    "layout" to svg(
        """<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/>""" +
            """<rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>""",
    ),
    "link" to svg(
        """<g transform="rotate(45 12 12)"><rect x="4" y="9.5" width="7" height="5" rx="2.5"/>""" +
            """<rect x="13" y="9.5" width="7" height="5" rx="2.5"/><line x1="11" y1="12" x2="13" y2="12"/></g>""",
    ),
    "paperclip" to svg("""<path d="M7 11v6a3 3 0 0 0 6 0V8a2 2 0 0 0-4 0v8"/>"""),
    "file-text" to svg(
        """<path d="M6 3h9l5 5v13H6z"/><path d="M15 3v5h5"/><path d="M9 13h6M9 17h6"/>""",
    ),
    "arrow-up-right" to svg("""<path d="M7 17L17 7M9 7h8v8"/>"""),
    "palette" to svg(
        """<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="15" cy="6" r="2"/>""" +
            """<circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>""",
    ),
    "user" to svg("""<circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 4 0 0 1 14 0"/>"""),
    "rocket" to svg(
        """<path d="M12 2l4 8h-3v7h-2v-7H8z"/><path d="M9 17l-2 4M15 17l2 4"/>""",
    ),
    "save" to svg(
        """<path d="M5 4h11l3 3v13H5z"/><path d="M8 4v5h7V4M7 14h10v6H7z"/>""",
    ),
    "file" to svg("""<path d="M6 3h9l5 5v13H6z"/><path d="M15 3v5h5"/>"""),
    "film" to svg(
        """<rect x="3.5" y="5" width="17" height="14" rx="1.5"/><path d="M7.5 5v14M16.5 5v14""" +
            """M3.5 9.5h4M16.5 9.5h4M3.5 14.5h4M16.5 14.5h4"/>""",
    ),
    "music" to svg("""<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>"""),
    "list" to svg(
        """<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r="1" fill="currentColor" stroke="none"/>""" +
            """<circle cx="4.5" cy="12" r="1" fill="currentColor" stroke="none"/>""" +
            """<circle cx="4.5" cy="18" r="1" fill="currentColor" stroke="none"/>""",
    ),
    "download" to svg("""<path d="M12 4v11M7.5 11.5l4.5 4.5 4.5-4.5"/><path d="M5 19h14"/>"""),
    "upload" to svg("""<path d="M12 20V9M7.5 13.5L12 9l4.5 4.5"/><path d="M5 4h14"/>"""),
    "chevron-down" to svg("""<path d="M6 9l6 6 6-6"/>"""),
    "chevron-left" to svg("""<path d="M15 6l-6 6 6 6"/>"""),
    "chevron-right" to svg("""<path d="M9 6l6 6-6 6"/>"""),
    "arrows-sort" to svg("""<path d="M8 4v14M5 15l3 3 3-3"/><path d="M16 20V6M13 9l3-3 3 3"/>"""),
    "plus" to svg("""<path d="M12 5v14M5 12h14"/>"""),
    "copy" to svg(
        """<rect x="8" y="8" width="12" height="12" rx="1.5"/>""" +
            """<path d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8"/>""",
    ),
    "clock" to svg("""<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3.2 2"/>"""),
    "info" to svg(
        """<circle cx="12" cy="12" r="9"/><path d="M12 11v6"/>""" +
            """<circle cx="12" cy="7.5" r="1" fill="currentColor" stroke="none"/>""",
    ),
)

/**
 * Draws [name] from the set above, sized to whatever `font-size` the surrounding button or label
 * already carries (`.icon-glyph` in index.html is 1em square) — every existing size rule written for
 * the character it replaces keeps working unchanged.
 */
internal fun ChildrenBuilder.icon(name: String, extraClassName: String? = null) {
    span {
        className = ClassName(if (extraClassName != null) "icon-glyph $extraClassName" else "icon-glyph")
        dangerouslySetInnerHTML = innerHtml(ICONS.getValue(name))
    }
}
