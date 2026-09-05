package stramus.ui

import kotlinx.coroutines.awaitCancellation
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.model.Collection
import web.cssom.ClassName
import web.html.HTMLInputElement
import stramus.core.url.hostOf
import stramus.core.repo.CardRepository
import stramus.core.model.CardKind

/**
 * How an emoji mark is told from a glyph mark in the one field both are stored in: `e:1f600` is the
 * emoji of that name, anything else is a glyph. A prefix rather than a second column because the two
 * are one choice — a collection has *a* mark — and a pair of columns that must never both be set is a
 * pair of columns that one day both will be.
 */
internal const val EMOJI_MARK = "e:"

/**
 * And the third kind: `f:github.com` is the icon of that site, as the favicon cache already holds it.
 *
 * The host is stored, never the bytes. They are already cached per host for the cards themselves, so
 * the mark costs the row nothing and the sync a dozen characters — where a `data:` URI in the field
 * would be kilobytes of base64 in every delta, and would go stale the day the site changed its icon.
 * The other device may not have the bytes yet; it draws the same letter tile a card of that host
 * draws until it does, which is a stand-in and not a failure.
 */
internal const val FAVICON_MARK = "f:"

/** The host a favicon mark names, or null if [icon] is not one. */
internal fun faviconMarkHost(icon: String?): String? =
    icon?.takeIf { it.startsWith(FAVICON_MARK) }?.removePrefix(FAVICON_MARK)?.takeIf { it.isNotBlank() }

/**
 * Whether a mark takes the collection's colour. A glyph is drawn in it; an emoji and a site's icon
 * bring their own, and painting over them would only make them wrong.
 */
internal fun markTakesColor(icon: String): Boolean =
    !icon.startsWith(EMOJI_MARK) && !icon.startsWith(FAVICON_MARK)

/**
 * The colours a glyph mark can be drawn in: the app's own accents (see [AccentColor]), which the
 * stylesheet already gives a value per lighting — `--ci-<id>` in index.html. Named rather than picked
 * freely, so a mark chosen under the light theme is still legible under the dark one.
 *
 * [AccentColor.AUTO] is not among them: it means "whatever the palette brought", which is the *absence*
 * of a choice — and the absence of a choice here is a collection with no colour at all.
 */
internal val COLLECTION_COLORS: List<String> =
    AccentColor.entries.filter { it != AccentColor.AUTO }.map { it.id }

/** Whether [icon] is a mark this build can draw — an emoji it ships, or a glyph it knows. */
internal fun knownMark(icon: String?): Boolean = when {
    icon == null -> false
    icon.startsWith(EMOJI_MARK) -> icon.removePrefix(EMOJI_MARK) in EMOJI_SVG
    // A host is a host: there is no library to be missing from. Whether its icon can be *reached* is
    // the favicon cache's question, and it answers it with a letter tile rather than with nothing.
    icon.startsWith(FAVICON_MARK) -> faviconMarkHost(icon) != null
    else -> hasIcon(icon)
}

/** Draws a mark — glyph or emoji — wherever one is wanted without a collection in hand. */
internal fun ChildrenBuilder.markGlyph(icon: String, extraClassName: String? = null) {
    val host = faviconMarkHost(icon)
    when {
        host != null -> Favicon {
            // The same component every card draws its icon with, so the mark gets the cache, the
            // background refresh and the letter-tile fallback without any of it written twice.
            url = "https://$host"
            favicon = null
            className = if (extraClassName != null) "fav mark-fav $extraClassName" else "fav mark-fav"
        }
        icon.startsWith(EMOJI_MARK) -> emojiGlyph(icon.removePrefix(EMOJI_MARK), extraClassName)
        else -> icon(icon, extraClassName)
    }
}

/**
 * The mark of a collection — its glyph in its colour, or its emoji — or nothing at all, which is what
 * an unmarked collection draws: a placeholder in every row would be a column of grey furniture down a
 * sidebar whose whole point is that the marked rows stand out.
 *
 * A mark this build does not know is treated as no mark rather than as an error. It can only get here
 * from the database, which means from another device running a build whose library has something this
 * one's has not, and a collection that fails to draw is a worse answer than a collection drawn plainly.
 */
internal fun ChildrenBuilder.collectionIcon(collection: Collection, extraClassName: String? = null) {
    val mark = collection.icon ?: return
    if (!knownMark(mark)) return
    // A span, not a div: this is drawn inside the open collection's `h2`, which takes phrasing content
    // only.
    span {
        className = ClassName(if (extraClassName != null) "col-icon $extraClassName" else "col-icon")
        // An emoji and a site's icon carry their own colours; only a glyph has one to give it.
        if (markTakesColor(mark)) {
            collection.color?.takeIf { it in COLLECTION_COLORS }?.let { asDynamic()["data-color"] = it }
        }
        markGlyph(mark)
    }
}

/** Which of the three the picker is showing. */
private enum class MarkTab { GLYPHS, EMOJI, FAVICONS }

external interface CollectionIconProps : Props {
    var strings: Strings

    /** The UI language, which is what the emoji search matches in — see [EMOJI_KEYWORDS]. */
    var lang: Lang
    var collection: Collection

    /** Where the picker hangs: the viewport rectangle of the control that opened it. */
    var anchorX: Double
    var anchorY: Double

    /** A mark was chosen, or cleared with null — which closes the popup. */
    var onPick: (icon: String?) -> Unit

    /** A colour was chosen, or cleared with null. The popup stays: a colour is judged against a glyph. */
    var onColor: (color: String?) -> Unit

    /**
     * Read to find the sites this collection is made of — the third thing it can be marked with.
     *
     * The picker asks for them itself rather than being handed them: it opens over any row of the
     * sidebar, and the app holds the cards of the open collection only.
     */
    var cards: CardRepository
    var onClose: () -> Unit
}

/** How wide the popup is, and how much room it wants under its anchor. Kept with the CSS that draws it. */
private const val POPUP_W = 336.0
private const val POPUP_H = 396.0

/**
 * Choosing what a collection is marked with, in a popup hanging off the thing that was clicked: a
 * search box, a grid of glyphs or emoji, and a row of colours for a glyph.
 *
 * A pick applies at once and closes, the way every picker of this shape does — there is nothing here
 * that a person could get half-right and want to cancel, and the way back is to open it again. A
 * colour is the exception: it applies but leaves the popup open, since the point of a colour is to be
 * seen against the glyph it is on.
 */
val CollectionIconPicker = FC<CollectionIconProps> { props ->
    val s = props.strings
    val mark = props.collection.icon?.takeIf { knownMark(it) }
    var tab by useState(
        when {
            mark?.startsWith(EMOJI_MARK) == true -> MarkTab.EMOJI
            mark?.startsWith(FAVICON_MARK) == true -> MarkTab.FAVICONS
            else -> MarkTab.GLYPHS
        },
    )
    var query by useState("")
    val searchRef = useRef<HTMLInputElement>(null)

    /**
     * The sites this collection holds links to, the most-saved first.
     *
     * Ordered by how many links point at each because that is the question being answered: a collection
     * of thirty GitHub repositories and one stray blog post is a GitHub collection, and the icon that
     * says so should not be somewhere down the list. Read once, when the popup opens.
     */
    var hosts by useState(emptyList<String>())
    useEffectOnce {
        val links = props.cards.byCollection(props.collection.id).filter { it.kind == CardKind.LINK }
        hosts = links.map { hostOf(it.url) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    // Escape closes it, as it does every window in this app. The listener is registered once and reads
    // the current onClose through a ref, the way `modalShell` does and for the same reason.
    val close = useRef(props.onClose)
    close.current = props.onClose
    useEffectOnce {
        searchRef.current?.focus()
        val stopWatching = onKeyStroke { event ->
            if (event.key == "Escape") {
                event.preventDefault()
                close.current?.invoke()
            }
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    val q = query.trim().lowercase()
    val glyphs = if (q.isEmpty()) {
        COLLECTION_ICONS
    } else {
        // Glyph keywords are Lucide's own, and English only — a name typed in another language finds
        // nothing here, where an emoji typed in one of the nine languages CLDR covers does.
        COLLECTION_ICONS.filter { name -> name.contains(q) || LIBRARY_ICON_KEYWORDS[name]?.contains(q) == true }
    }
    val emojiWords = EMOJI_KEYWORDS[props.lang.id] ?: EMOJI_KEYWORDS["en"].orEmpty()
    val emoji = if (q.isEmpty()) EMOJI_ORDER else EMOJI_ORDER.filter { emojiWords[it]?.contains(q) == true }
    val sites = if (q.isEmpty()) hosts else hosts.filter { it.contains(q) }

    fun pick(icon: String?) = props.onPick(icon)

    // Held inside the viewport: a row near the bottom of a short window would otherwise open a popup
    // that runs off the screen, and a sidebar row is as often at the bottom as at the top.
    val vw = viewportWidth()
    val vh = viewportHeight()
    val left = props.anchorX.coerceIn(8.0, maxOf(8.0, vw - POPUP_W - 8.0))
    val top = props.anchorY.coerceIn(8.0, maxOf(8.0, vh - POPUP_H - 8.0))

    // A backdrop with nothing drawn on it: it is there to catch the click that means "somewhere else",
    // which is how a popup is dismissed everywhere, and to keep that click from also doing whatever it
    // would have done to the app underneath.
    div {
        className = ClassName("popover-backdrop")
        onClick = { e -> e.stopPropagation(); props.onClose() }
        div {
            className = ClassName("icon-popover")
            onClick = { e -> e.stopPropagation() }
            val style = js("({})")
            style.left = "${left}px"
            style.top = "${top}px"
            asDynamic()["style"] = style
            asDynamic()["role"] = "dialog"
            asDynamic()["aria-label"] = s.collectionIconHeading

            div {
                className = ClassName("icon-pop-head")
                button {
                    className = ClassName(if (tab == MarkTab.GLYPHS) "pop-tab selected" else "pop-tab")
                    onClick = { tab = MarkTab.GLYPHS }
                    +s.collectionIconTabGlyphs
                }
                button {
                    className = ClassName(if (tab == MarkTab.EMOJI) "pop-tab selected" else "pop-tab")
                    onClick = { tab = MarkTab.EMOJI }
                    +s.collectionIconTabEmoji
                }
                button {
                    className = ClassName(if (tab == MarkTab.FAVICONS) "pop-tab selected" else "pop-tab")
                    onClick = { tab = MarkTab.FAVICONS }
                    +s.collectionIconTabFavicons
                }
                span { className = ClassName("pop-spacer") }
                button {
                    className = ClassName("pop-action")
                    hint(s.collectionIconRandom)
                    // Random picks from what is on screen, not from the whole library: with a search
                    // typed, "surprise me" should still respect what was asked for.
                    onClick = {
                        val pool = when (tab) {
                            MarkTab.EMOJI -> emoji.map { EMOJI_MARK + it }
                            MarkTab.FAVICONS -> sites.map { FAVICON_MARK + it }
                            MarkTab.GLYPHS -> glyphs
                        }
                        pool.randomOrNull()?.let { pick(it) }
                    }
                    icon("sparkles")
                }
            }

            input {
                ref = searchRef
                className = ClassName("pop-search")
                placeholder = s.collectionIconSearch
                value = query
                onChange = { e -> query = e.target.value }
            }

            div {
                className = ClassName("icon-grid")
                if (tab == MarkTab.GLYPHS) {
                    glyphs.forEach { name ->
                        button {
                            key = name.unsafeCast<Key>()
                            className = ClassName(if (name == mark) "icon-cell selected" else "icon-cell")
                            hint(name.replace('-', ' '))
                            onClick = { pick(name) }
                            // In the colour it would be worn in, so the grid answers the question that
                            // is actually being asked: what will this look like on the row?
                            props.collection.color
                                ?.takeIf { it in COLLECTION_COLORS }
                                ?.let { asDynamic()["data-color"] = it }
                            icon(name)
                        }
                    }
                } else if (tab == MarkTab.EMOJI) {
                    emoji.forEach { code ->
                        button {
                            key = code.unsafeCast<Key>()
                            className = ClassName(if (EMOJI_MARK + code == mark) "icon-cell selected" else "icon-cell")
                            hint(emojiWords[code].orEmpty())
                            onClick = { pick(EMOJI_MARK + code) }
                            emojiGlyph(code)
                        }
                    }
                } else {
                    sites.forEach { host ->
                        button {
                            key = host.unsafeCast<Key>()
                            className = ClassName(
                                if (FAVICON_MARK + host == mark) "icon-cell selected" else "icon-cell",
                            )
                            // The host itself is the label: it is what the user recognises the icon by,
                            // and the one thing about a site the app can be sure it knows.
                            hint(host)
                            onClick = { pick(FAVICON_MARK + host) }
                            markGlyph(FAVICON_MARK + host)
                        }
                    }
                }
                val nothing = when (tab) {
                    MarkTab.GLYPHS -> glyphs.isEmpty()
                    MarkTab.EMOJI -> emoji.isEmpty()
                    // Told apart on purpose: a collection with no links has nothing to offer here at all,
                    // which is not the same as a search that matched none of what it has.
                    MarkTab.FAVICONS -> sites.isEmpty()
                }
                if (nothing) {
                    div {
                        className = ClassName("pop-empty")
                        +if (tab == MarkTab.FAVICONS && hosts.isEmpty()) s.collectionIconNoSites else s.collectionIconNothing
                    }
                }
            }

            div {
                className = ClassName("icon-pop-foot")
                // Colours belong to a glyph: an emoji brings its own, and a row of swatches that did
                // nothing would only invite the click that proves it.
                if (tab == MarkTab.GLYPHS) {
                    div {
                        className = ClassName("icon-colors")
                        button {
                            className = ClassName(
                                if (props.collection.color == null) "color-cell none selected" else "color-cell none",
                            )
                            hint(s.collectionColorNone)
                            onClick = { props.onColor(null) }
                            icon("x")
                        }
                        COLLECTION_COLORS.forEach { id ->
                            button {
                                className = ClassName(
                                    if (id == props.collection.color) "color-cell selected" else "color-cell",
                                )
                                asDynamic()["data-color"] = id
                                hint(AccentColor.from(id).label(s))
                                onClick = { props.onColor(id) }
                            }
                        }
                    }
                }
                // Taking the mark off, said in words rather than left to a glyph among the glyphs:
                // it is the one control here that removes something, and a bare × in a grid of
                // pictures reads as one more picture to choose.
                button {
                    className = ClassName("pop-remove")
                    disabled = mark == null && props.collection.color == null
                    onClick = { props.onPick(null) }
                    icon("x")
                    +" ${s.collectionIconNone}"
                }
            }
        }
    }
}
