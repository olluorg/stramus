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
    "check" to svg("""<path d="M5 12.5l4.5 4.5L19 7"/>"""),
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

    // The rest of this map is what a collection can be marked with — see [COLLECTION_ICONS]. They are
    // drawn the same way as everything above, and a few of the glyphs already there (folder, star's
    // neighbours, film, music…) serve both purposes rather than being drawn twice.
    "star" to svg("""<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.2 5.9-.8z"/>"""),
    "heart" to svg("""<path d="M12 20.2S4.5 15.5 4.5 10.4A4 4 0 0 1 12 8.2a4 4 0 0 1 7.5 2.2c0 5.1-7.5 9.8-7.5 9.8z"/>"""),
    "home" to svg("""<path d="M3.5 11L12 4.2l8.5 6.8"/><path d="M6 9.6V20h12V9.6"/><path d="M10 20v-5h4v5"/>"""),
    "briefcase" to svg(
        """<rect x="3.5" y="7.5" width="17" height="12" rx="2"/>""" +
            """<path d="M9 7.5V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1.5"/><path d="M3.5 13h17"/>""",
    ),
    "book" to svg("""<path d="M5 5a2 2 0 0 1 2-2h12v16H7a2 2 0 0 0-2 2z"/><path d="M5 19a2 2 0 0 1 2-2h12"/>"""),
    "graduation" to svg(
        """<path d="M12 4.5l9 4.3-9 4.3-9-4.3z"/><path d="M7 11.2V16c0 1.4 2.2 2.5 5 2.5s5-1.1 5-2.5v-4.8"/>""",
    ),
    "code" to svg("""<path d="M9 6.5L3.5 12 9 17.5M15 6.5L20.5 12 15 17.5"/>"""),
    "terminal" to svg("""<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7 9.5l3 2.5-3 2.5M12.5 15h4.5"/>"""),
    "cart" to svg(
        """<circle cx="10" cy="19" r="1.4"/><circle cx="17" cy="19" r="1.4"/>""" +
            """<path d="M3 4.5h2.3l2.5 10.2h9.7l1.9-7.2H7"/>""",
    ),
    "wallet" to svg(
        """<rect x="3.5" y="6" width="17" height="13" rx="2.5"/><path d="M3.5 10.5h17"/>""" +
            """<circle cx="16.5" cy="14.8" r="1.1" fill="currentColor" stroke="none"/>""",
    ),
    "plane" to svg(
        """<path d="M12 2.8l1.3 7.4 7.2 2.5v1.7l-7.2-1.4-.5 4.2 2.5 1.8v1.3L12 19.4l-3.3 1.1v-1.3l2.5-1.8-.5-4.2-7.2 1.4v-1.7l7.2-2.5z"/>""",
    ),
    "camera" to svg(
        """<path d="M3.5 9a1.5 1.5 0 0 1 1.5-1.5h2.6l1.4-2h6l1.4 2H19A1.5 1.5 0 0 1 20.5 9v9a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18z"/>""" +
            """<circle cx="12" cy="13.2" r="3.4"/>""",
    ),
    "gamepad" to svg(
        """<rect x="2.5" y="7.5" width="19" height="10" rx="4.5"/><path d="M7 10.5v4M5 12.5h4"/>""" +
            """<circle cx="16" cy="11.3" r="1" fill="currentColor" stroke="none"/>""" +
            """<circle cx="18.4" cy="14" r="1" fill="currentColor" stroke="none"/>""",
    ),
    "flask" to svg(
        """<path d="M10 3.5v6L4.9 18a2 2 0 0 0 1.7 3h10.8a2 2 0 0 0 1.7-3L14 9.5v-6"/>""" +
            """<path d="M9 3.5h6M7.6 14.5h8.8"/>""",
    ),
    "bulb" to svg("""<path d="M9 16.5a6 6 0 1 1 6 0V19H9z"/><path d="M10 21.5h4"/>"""),
    "flag" to svg("""<path d="M6 21V4"/><path d="M6 5h11l-2 3.4 2 3.4H6"/>"""),
    "bookmark" to svg("""<path d="M7 4h10v16.5l-5-4-5 4z"/>"""),
    "tag" to svg(
        """<path d="M3.5 11.4V4h7.4l9.1 9.1-7.4 7.4z"/>""" +
            """<circle cx="7.6" cy="8" r="1.3" fill="currentColor" stroke="none"/>""",
    ),
    "calendar" to svg("""<rect x="3.5" y="5.5" width="17" height="15" rx="2"/><path d="M3.5 10.5h17M8 3.5v4M16 3.5v4"/>"""),
    "chat" to svg("""<path d="M20.5 12c0 3.9-3.8 7-8.5 7-1 0-1.9-.1-2.8-.4L4 21l1.5-3.5A6.6 6.6 0 0 1 3.5 12c0-3.9 3.8-7 8.5-7s8.5 3.1 8.5 7z"/>"""),
    "mail" to svg("""<rect x="3" y="5.5" width="18" height="13" rx="2"/><path d="M3.5 7.5l8.5 6 8.5-6"/>"""),
    "map-pin" to svg("""<path d="M12 21.2S19 15 19 10a7 7 0 1 0-14 0c0 5 7 11.2 7 11.2z"/><circle cx="12" cy="10" r="2.6"/>"""),
    "coffee" to svg(
        """<path d="M4 7h13v6.5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z"/>""" +
            """<path d="M17 9h1.6a2.8 2.8 0 0 1 0 5.5H17"/><path d="M3 21.5h15"/>""",
    ),
    "cloud" to svg("""<path d="M7.5 18.5h9.6a3.7 3.7 0 0 0 .4-7.4 5.5 5.5 0 0 0-10.7-1.4 4 4 0 0 0 .7 8z"/>"""),
    "leaf" to svg("""<path d="M20 4c-9.3 0-15 3.2-15 10a5 5 0 0 0 5 5c7 0 10-6.2 10-15z"/><path d="M5.5 20.5c1.8-5.6 5.5-8.8 10.5-10.8"/>"""),
    "dumbbell" to svg("""<path d="M4.5 9v6M7.5 6.5v11M16.5 6.5v11M19.5 9v6M7.5 12h9"/>"""),
    "users" to svg(
        """<circle cx="9.5" cy="8.5" r="3.3"/><path d="M3.5 19.5a6 6 0 0 1 12 0"/>""" +
            """<path d="M15.8 5.6a3.3 3.3 0 0 1 0 5.8M17 19.5a6.3 6.3 0 0 0-1.8-4.2"/>""",
    ),
    "globe" to svg(
        """<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/>""" +
            """<path d="M12 3.5c2.2 2.4 3.4 5.3 3.4 8.5S14.2 18.1 12 20.5c-2.2-2.4-3.4-5.3-3.4-8.5S9.8 5.9 12 3.5z"/>""",
    ),
    "shield" to svg("""<path d="M12 3.5l7 2.4v5.4c0 4.3-2.9 7.8-7 9.2-4.1-1.4-7-4.9-7-9.2V5.9z"/>"""),
    "zap" to svg("""<path d="M13.5 3L5.5 13.5h5.2L10 21l8-10.5h-5.2z"/>"""),
    "inbox" to svg(
        """<path d="M3.5 9h17v9.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5z"/>""" +
            """<path d="M3.5 9L5.8 4h12.4L20.5 9"/><path d="M9 13h6"/>""",
    ),
    "pin" to svg("""<path d="M9 3.5h6l-1 5 3.5 3.5H6.5L10 8.5z"/><path d="M12 12v8.5"/>"""),
)

/**
 * The glyphs a collection can be marked with, in the order the picker lays them out (see
 * `CollectionIcon.kt`). Everything here is a key of [ICONS]; the reverse does not hold — the app's
 * own furniture (chevrons, the plus, the padlock) says nothing about what a collection is *for*, and
 * marking one with it would only make the sidebar harder to read.
 */
internal val COLLECTION_ICONS: List<String> = buildList {
    addAll(
        listOf(
            "folder", "star", "heart", "bookmark", "flag", "tag", "pin", "inbox",
            "briefcase", "home", "users", "chat", "mail", "calendar", "clock", "list",
            "book", "graduation", "bulb", "flask", "code", "terminal", "file-text", "search",
            "cart", "wallet", "plane", "map-pin", "globe", "coffee", "leaf", "dumbbell",
            "camera", "film", "music", "gamepad", "sparkles", "rocket", "zap", "shield",
            "cloud", "download", "link", "paperclip", "palette", "layout", "sun", "moon",
        ),
    )
    // Then the library (see `IconLibrary.kt`), minus whatever this file already draws a version of:
    // two folders side by side in the picker is a choice between things that are not different.
    addAll(LIBRARY_ICON_ORDER.filter { it !in ICONS })
}

/**
 * Whether this build knows how to draw [name] — what a glyph name read back out of the database is
 * checked by, since a collection marked on a newer build can name one that is not here yet.
 */
internal fun hasIcon(name: String): Boolean = name in ICONS || name in LIBRARY_ICONS

/**
 * Draws [name] — from the hand-drawn set above or from the library beside it — sized to whatever
 * `font-size` the surrounding button or label already carries (`.icon-glyph` in index.html is 1em
 * square), so every size rule written for the character it replaced keeps working unchanged.
 *
 * The hand-drawn set wins where both have a name: those are the ones the app's own furniture is made
 * of, and they were drawn to sit at the sizes it uses them at.
 */
internal fun ChildrenBuilder.icon(name: String, extraClassName: String? = null) {
    val body = ICONS[name] ?: LIBRARY_ICONS[name]?.let { svg(it) } ?: ICONS.getValue(name)
    span {
        className = ClassName(if (extraClassName != null) "icon-glyph $extraClassName" else "icon-glyph")
        dangerouslySetInnerHTML = innerHtml(body)
    }
}

/**
 * Draws one of the emoji from `EmojiLibrary.kt`, by the codepoints it is named after.
 *
 * Not `icon()`: these are pictures rather than line art — their own colours, their own 36×36 viewBox —
 * and nothing about them follows the text they sit in. That is the price of an emoji that looks the
 * same on every machine, and it is why the colour picker has nothing to say about one.
 */
internal fun ChildrenBuilder.emojiGlyph(code: String, extraClassName: String? = null) {
    val body = EMOJI_SVG[code] ?: return
    span {
        className = ClassName(if (extraClassName != null) "icon-glyph $extraClassName" else "icon-glyph")
        dangerouslySetInnerHTML = innerHtml("""<svg viewBox="0 0 36 36">$body</svg>""")
    }
}
