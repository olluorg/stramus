package stramus.ui

import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.model.CardKind
import web.cssom.ClassName
import web.html.HTMLInputElement

/** The glyph a row is drawn with when it stands for no page of its own (so there is no favicon). */
private fun glyphOf(hit: Hit): String = when (hit) {
    is CollectionHit -> "📁"
    is WebSearchHit -> "🔍"
    is AiHit -> "✨"
    is OpenUrlHit -> "↗"
    // A card with no URL is a note or a file — the same two glyphs the card itself wears.
    is CardHit -> if (hit.card.kind == CardKind.FILE) "📎" else "📝"
    else -> "🔗"
}

/** The badge on the right of a row: what activating it will actually do. */
private fun actionLabelOf(hit: Hit, strings: Strings): String = when (hit) {
    is TabHit -> strings.hitSwitchToTab
    is CollectionHit -> strings.hitOpenCollection
    is AiHit -> strings.hitAskAi
    else -> ""
}

external interface SearchBoxProps : Props {
    var strings: Strings
    var query: String

    /** What the box offers for [query], already ranked and grouped — see `buildHits`. */
    var groups: List<HitGroup>

    /** The box is a question to the model, not a search: the chip is up and the dropdown stays down. */
    var aiMode: Boolean

    var onQueryChange: (String) -> Unit
    var onActivate: (Hit) -> Unit

    /** Ctrl/Cmd+Enter: show every matching card in the content area, rather than the best few here. */
    var onShowAll: () -> Unit

    /** Escape while the box is a question to the model. */
    var onExitAi: () -> Unit

    /** The × on a top site: stop offering this page. */
    var onForget: (SiteHit) -> Unit
}

/**
 * The search box: one field for everything the user might mean — a tab they left open, a card they
 * saved, a page they visited, an address, a question for the web, a question for the model.
 *
 * It is the page's default focus, because in the extension this *is* the new tab page and the first
 * thing anyone does on a new tab is type. From anywhere else on the page Ctrl/Cmd+K or "/" bring the
 * focus back.
 *
 * The keyboard is the whole point:
 *  - ↑ / ↓ walk the rows (wrapping), and the first row is always preselected, so Enter with no
 *    keystrokes at all takes the best answer — which is what a search box is for;
 *  - Enter activates the selected row, Alt+Enter always searches the web (whatever is selected), and
 *    Ctrl/Cmd+Enter opens the full grid of matching cards;
 *  - Escape steps back: it closes the list, then clears the field, then gives up the focus.
 *
 * The rows are drawn from [SearchBoxProps.groups] in the order they come; the flat list underneath
 * them is the one the selection walks, so the headings are never landed on.
 */
val SearchBox = FC<SearchBoxProps> { props ->
    val s = props.strings
    val inputRef = useRef<HTMLInputElement>(null)
    var focused by useState(false)
    var selected by useState(0)

    val hits = props.groups.flatMap { it.hits }
    val open = focused && !props.aiMode && hits.isNotEmpty()

    // A new query is a new list: the selection goes back to the best row, or it would sit on whatever
    // row happens to hold that index now.
    useEffect(props.query, props.aiMode) { selected = 0 }

    // The new tab page opens with the caret already in the box. Everywhere else on the page, Ctrl/Cmd+K
    // and "/" bring it back — "/" only when the user is not writing something, where it is a slash.
    useEffectOnce {
        inputRef.current?.focus()
    }

    useEffectOnce {
        val stopWatching = onKeyStroke { event ->
            val shortcut = (event.metaKey || event.ctrlKey) && event.key.lowercase() == "k"
            val slash = event.key == "/" && !isTyping(event)
            if (shortcut || slash) {
                event.preventDefault()
                inputRef.current?.focus()
                inputRef.current?.select()
            }
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    fun activate(hit: Hit) {
        props.onActivate(hit)
        // The answer is on its way; the list has done its job and the caret leaves with it.
        inputRef.current?.blur()
        focused = false
    }

    fun move(by: Int) {
        if (hits.isEmpty()) return
        selected = (selected + by + hits.size) % hits.size
    }

    div {
        className = ClassName(if (open) "searchbox open" else "searchbox")

        div {
            className = ClassName("search-field")
            if (props.aiMode) {
                span {
                    className = ClassName("ai-chip")
                    hint(s.aiChipHint)
                    +s.aiChip
                }
            }
            input {
                ref = inputRef
                className = ClassName("search")
                placeholder = if (props.aiMode) s.aiPlaceholder else s.searchPlaceholder
                value = props.query
                onChange = { e -> props.onQueryChange(e.target.value) }
                onFocus = { focused = true }
                // A click on a row blurs the field before the click lands; the row's own onMouseDown
                // holds the focus, so a blur that gets here really is the user leaving.
                onBlur = { focused = false }
                onKeyDown = { e ->
                    when {
                        e.key == "ArrowDown" -> { e.preventDefault(); focused = true; move(1) }
                        e.key == "ArrowUp" -> { e.preventDefault(); focused = true; move(-1) }

                        // A question to the model is its own conversation: Enter asks the next one.
                        props.aiMode && e.key == "Enter" -> {
                            e.preventDefault()
                            if (props.query.isNotBlank()) props.onActivate(AiHit(props.query.trim()))
                        }

                        e.key == "Enter" && (e.metaKey || e.ctrlKey) -> { e.preventDefault(); props.onShowAll() }
                        // Whatever is selected, this is the way to the web — the row for it may be
                        // several keystrokes down the list.
                        e.key == "Enter" && e.altKey -> {
                            e.preventDefault()
                            if (props.query.isNotBlank()) activate(WebSearchHit(props.query.trim()))
                        }
                        e.key == "Enter" -> {
                            e.preventDefault()
                            hits.getOrNull(selected)?.let { activate(it) }
                        }

                        e.key == "Escape" -> {
                            e.preventDefault()
                            when {
                                props.aiMode -> props.onExitAi()
                                open -> focused = false
                                props.query.isNotEmpty() -> props.onQueryChange("")
                                else -> inputRef.current?.blur()
                            }
                        }
                    }
                }
            }
        }

        if (open) {
            div {
                className = ClassName("search-drop")
                // Holding the focus through the click: without this the field blurs first, the list
                // unmounts, and the click lands on nothing.
                onMouseDown = { e -> e.preventDefault() }

                var index = 0
                props.groups.forEach { group ->
                    if (group.hits.isEmpty()) return@forEach
                    div {
                        key = group.label.ifBlank { group.source.name }.unsafeCast<Key>()
                        className = ClassName("hit-group")
                        if (group.label.isNotBlank()) {
                            div { className = ClassName("hit-group-label"); +group.label }
                        }
                        ul {
                            className = ClassName("hit-list")
                            group.hits.forEach { hit ->
                                val at = index++
                                li {
                                    key = hit.key.unsafeCast<Key>()
                                    className = ClassName(if (at == selected) "hit selected" else "hit")
                                    onMouseEnter = { selected = at }
                                    onClick = { activate(hit) }

                                    val url = hit.url
                                    if (url != null) {
                                        Favicon {
                                            this.url = url
                                            favicon = (hit as? CardHit)?.card?.favicon
                                                ?: (hit as? TabHit)?.tab?.favicon
                                            className = "fav hit-icon"
                                        }
                                    } else {
                                        span { className = ClassName("hit-glyph"); +glyphOf(hit) }
                                    }

                                    span {
                                        className = ClassName("hit-text")
                                        span {
                                            className = ClassName("hit-title")
                                            +when (hit) {
                                                is WebSearchHit -> s.hitWebSearch(hit.query)
                                                is AiHit -> s.hitAskAiRow(hit.query)
                                                is OpenUrlHit -> s.hitOpenUrl(hit.query)
                                                else -> hit.title
                                            }
                                        }
                                        if (hit.subtitle.isNotBlank()) {
                                            span { className = ClassName("hit-sub"); +hit.subtitle }
                                        }
                                    }

                                    val action = actionLabelOf(hit, s)
                                    if (action.isNotBlank()) {
                                        span { className = ClassName("hit-action"); +action }
                                    }
                                    // A page the user does not want offered again — the ranking is
                                    // built from their own habits, so they get to correct it.
                                    if (hit is SiteHit) {
                                        button {
                                            className = ClassName("icon del hit-forget")
                                            hint(s.forgetSite)
                                            onClick = { e ->
                                                e.stopPropagation()
                                                props.onForget(hit)
                                            }
                                            +"×"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                div {
                    className = ClassName("hit-hints")
                    span { +s.searchHints }
                }
            }
        }
    }
}
