@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.FC
import react.Props
import react.dom.html.HTMLAttributes
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.memo
import react.useEffect
import react.useState
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.url.hostOf
import web.cssom.ClassName
import web.data.AllowedEffect
import web.data.DropEffect
import web.data.move
import web.html.HTMLElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Whether a saved video shows its still frame, and where.
 *
 * [OFF] by default, and deliberately: a frame is fetched from Google's own servers, so drawing one tells
 * Google which video the user saved and from what address. That is a thing to be asked for, not a thing
 * to be discovered afterwards — see the `cardPreviews` setting, whose wording says exactly this, and
 * `Thumbs.kt`, which explains why nothing is ever kept.
 */
enum class CardPreviews(val id: String) {
    /** No frames and no requests. Nothing about a saved video leaves the machine. */
    OFF("off"),

    /** The frame appears over the page while the pointer rests on a card: one video asked about, once. */
    HOVER("hover"),

    /** Every video card wears its frame — so every video on screen is asked about, on every open. */
    INLINE("inline"),
    ;

    fun label(s: Strings): String = when (this) {
        OFF -> s.cardPreviewsOff
        HOVER -> s.cardPreviewsHover
        INLINE -> s.cardPreviewsAlways
    }

    companion object {
        fun from(id: String?): CardPreviews = entries.firstOrNull { it.id == id } ?: OFF
    }
}

external interface CardTileProps : Props {
    var strings: Strings
    var card: Card

    /** Whether a link card spells its address out under the title. See the `cardUrls` setting. */
    var showUrl: Boolean

    /** Whether this card shows a video's still frame, and where. See [CardPreviews]. */
    var previews: CardPreviews
    var isDraggable: Boolean
    var readOnly: Boolean
    var isDragging: Boolean
    var acceptsDrop: Boolean

    // Each takes the card it happened to, so one callback serves every tile: a handler built per card
    // would be a new function on every render, and this component is memoized on its props being the
    // same ones as last time. See `App`, which holds these steady with `useCallback`.
    var onOpen: (Card) -> Unit

    /** Ask for this card to be renamed — the box it is renamed in is [RenameCardModal], which App opens. */
    var onRename: (Card) -> Unit
    var onDelete: (Card) -> Unit
    var onStartDrag: (Card) -> Unit
    var onEndDrag: () -> Unit
    var onDropHere: (Card) -> Unit
}

/**
 * One card. Its look depends on [Card.kind]: a link shows its favicon + URL, a note shows a snippet
 * of its markdown, a file shows an image thumbnail (or a file glyph). Its text comes from `strings`,
 * the active translations handed down by `App`. Hovering reveals rename and delete — and, for a link
 * that stands for a video, the still frame saved with it (see [PreviewLayer]).
 *
 * A link card is a real `<a href>`, so the browser opens it in a new tab on a middle-click or a
 * Ctrl/Cmd-click, and offers "open in new tab" in its context menu — exactly as any other link on
 * the page. A plain left-click is caught and routed through [CardTileProps.onOpen] instead, so that
 * a page already open in another tab is reused rather than duplicated (see `App.openPage`). Notes and
 * files have nowhere to open to but a modal, so they stay plain `<div>`s.
 *
 * When draggable it joins HTML5 drag-and-drop: `onStartDrag` / `onEndDrag` track it, and it is dimmed
 * while dragging. It takes a drop only while `acceptsDrop` — i.e. while another card is in flight —
 * so that a dragged tab or collection falls through to the zone behind it instead. A drop fires
 * `onDropHere`, which inserts the dragged card into *this* card's section, before it.
 *
 * In a read-only collection the card is still opened and read; what it loses is the rename and
 * delete buttons — there is nothing here to reach for by accident.
 *
 * It is [memo]ized, and that is not an optimization detail but the reason a drag is smooth at all:
 * `App` holds every drag in its state, so it re-renders on each dragover — several times a second,
 * with the whole grid below it. A tile whose props did not change sits that out.
 */
val CardTile = memo(
    FC<CardTileProps> { props ->
        val card = props.card

        if (card.kind == CardKind.LINK) {
            a {
                href = card.url
                onClick = { e ->
                    // A modified click — Ctrl/Cmd/Shift/Alt, or the middle button, which never reaches
                    // `onClick` at all — is left to the browser: that is how it opens a link in a new
                    // tab or window, and that is the whole point of this being an <a>. A plain click is
                    // ours, and goes through the app so an already-open tab is reused.
                    if (!e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey) {
                        e.preventDefault()
                        props.onOpen(card)
                    }
                }
                cardTileBody(props)
            }
        } else {
            div {
                tabIndex = 0
                onClick = { props.onOpen(card) }
                cardTileBody(props)
            }
        }
    },
)

/**
 * The tile's insides and its shared behaviour — everything but the click, which differs between a
 * link's `<a>` and the `<div>` of a note or file (see [CardTile]). Generic over the element so it can
 * dress either one.
 */
private fun <T : HTMLElement> HTMLAttributes<T>.cardTileBody(props: CardTileProps) {
    val card = props.card
    val strings = props.strings

    // The second line of the tile: where a link keeps its address, a note its first words, a file the
    // kind of file it is. A link always shows one — the card is meant to be scanned by where it goes,
    // not by its title alone — the full URL when the user asked for it (the `cardUrls` setting), the
    // bare host otherwise.
    val subtitle = when (card.kind) {
        CardKind.LINK -> if (props.showUrl) card.url else hostOf(card.url)
        CardKind.NOTE -> (card.content ?: "").replace("\n", " ").ifBlank { strings.emptyNote }
        CardKind.FILE -> card.mime ?: strings.fileLabel
    }

    // Where this card's video publishes its still frame, if this card stands for a video at all. Only an
    // address: nothing is fetched or kept here, and the request happens — if it happens — when a browser
    // draws the `<img>` below. See [videoThumbUrl].
    val frame = if (card.kind == CardKind.LINK) videoThumbUrl(card.url) else null
    val offered = frame?.takeIf { props.previews == CardPreviews.INLINE }
    // A picture that will not load costs the card the whole cover, `has-cover` and all — the class is
    // what makes the tile two rows tall, and a card left tall around a picture that never arrived is a
    // hole in the grid. Kept here rather than inside the image so that the tile itself knows.
    var coverBroken by useState(false)
    // A picture *replaced* — the frame arriving to stand in for the address it was drawn from — is a
    // fresh chance, not a settled failure.
    useEffect(offered) { coverBroken = false }
    val cover = offered?.takeUnless { coverBroken }

    val elementId = "card-${card.id}"
    asDynamic()["id"] = elementId
    className = ClassName(
        buildString {
            append("card kind-${card.kind.id}")
            if (cover != null) append(" has-cover")
            if (props.isDragging) append(" dragging")
        },
    )
    // The title, whole — the tile cuts it to its width. The line under it (a URL, a note's
    // first words) is not repeated here: it is the title the user is trying to read. A link
    // whose address the tile does not show is the exception — then the tooltip is the only
    // place left to see where the card goes.
    hint(if (card.kind == CardKind.LINK && !props.showUrl) "${card.title} — ${card.url}" else card.title)
    // A link that stands for a video carries its still frame, shown over the page while the pointer
    // rests here — see [PreviewLayer], which reads this and draws the tooltip's words under it. It is
    // not drawn in the tile itself: a grid of 16:9 frames is a video site, and this is a page of links.
    //
    // The frame saved with the card by preference — it needs no network, so a saved video keeps its
    // preview offline — and the address it came from otherwise: a card whose bytes have not been
    // fetched yet, or one whose bytes cannot be fetched at all (an `<img>` needs no CORS headers where
    // `fetch` does), still has something to show.
    //
    // Only in [CardPreviews.HOVER]: with the frame already on the tile a popup of what is under the
    // pointer answers nothing, and it would cost the card its ordinary tooltip besides.
    if (props.previews == CardPreviews.HOVER) {
        frame?.let { asDynamic()[PREVIEW_ATTR] = it }
    }

    // First in the tile, so the grid places it across the top — see `.card.has-cover` in index.html.
    if (cover != null) {
        img {
            className = ClassName("card-cover")
            src = cover
            // The picture *is* the card here, so it is not decoration to be passed over in silence —
            // but the title is right under it, and repeating it would have a screen reader say it twice.
            alt = ""
            draggable = false
            // Nothing of ours travels with the request: the page this is drawn on is no business of the
            // server holding the picture.
            asDynamic()["referrerPolicy"] = "no-referrer"
            onError = { coverBroken = true }
        }
    }
    // Enter opens the tile — except a link, which already opens on Enter as any `<a>` does; handling
    // it again here would fire [CardTileProps.onOpen] twice. Space is not a link's default action
    // (only Enter is, on an `<a>`), so it is handled here for every kind alike. Arrow keys move focus
    // to the next card over in whichever direction, for both kinds alike too (see [moveCardFocus]).
    onKeyDown = { e ->
        when (e.key) {
            "Enter" -> if (card.kind != CardKind.LINK) { e.preventDefault(); props.onOpen(card) }
            " " -> { e.preventDefault(); props.onOpen(card) }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown" -> {
                if (moveCardFocus(e.currentTarget.asDynamic(), elementId, e.key)) e.preventDefault()
            }
        }
    }
    draggable = props.isDraggable
    if (props.isDraggable) {
        onDragStart = { e ->
            // Firefox refuses to start a drag whose dataTransfer carries nothing.
            e.dataTransfer.setData("text/plain", card.id.toString())
            e.dataTransfer.effectAllowed = AllowedEffect.move
            props.onStartDrag(card)
        }
        onDragEnd = { props.onEndDrag() }
    }
    if (props.acceptsDrop) {
        onDragOver = { e ->
            e.preventDefault()
            e.dataTransfer.dropEffect = DropEffect.move
        }
        onDrop = { e ->
            e.preventDefault()
            e.stopPropagation() // this card decides the drop position, not the section behind it
            props.onDropHere(card)
        }
    }

    // Leading glyph / thumbnail, wrapped so a card the tab triage placed can carry a small badge in
    // its corner — the same sparkles the ✨ triage button itself wears, small enough not to compete
    // with the title for the eye. Gone the first time the card is opened — see `App.onCardOpen`.
    div {
        className = ClassName("card-icon")
        when (card.kind) {
            CardKind.LINK -> Favicon {
                url = card.url
                favicon = card.favicon
            }
            CardKind.NOTE -> span { className = ClassName("glyph"); icon("file-text") }
            // The card carries a downscaled preview, never the file itself — the bytes stay in the
            // database until the file is opened. No preview (not an image, or one that would not
            // decode) means a glyph.
            CardKind.FILE -> {
                val thumb = card.thumb
                if (thumb != null) {
                    img {
                        className = ClassName("fav thumb")
                        src = thumb
                        alt = ""
                        draggable = false
                    }
                } else {
                    span { className = ClassName("glyph"); icon(fileIconName(card.mime)) }
                }
            }
        }
        if (card.aiCreated) {
            span { className = ClassName("card-ai-badge"); icon("sparkles") }
        }
    }

    div {
        className = ClassName("card-body")
        div {
            // Without a second line the title takes it: two lines of title in the space the
            // tile already had, so a long one is readable and the card is the same size.
            className = ClassName(if (subtitle == null) "card-title two-line" else "card-title")
            +card.title
        }
        if (subtitle != null) {
            div {
                className = ClassName("card-url")
                +subtitle
            }
        }
    }
    if (!props.readOnly) {
        div {
            className = ClassName("card-tools")
            button {
                className = ClassName("icon edit")
                hint(strings.renameCard)
                onClick = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    props.onRename(card)
                }
                icon("edit")
            }
            button {
                className = ClassName("icon del")
                hint(strings.deleteCardHint)
                onClick = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    props.onDelete(card)
                }
                icon("x")
            }
        }
    }
}

private fun fileIconName(mime: String?): String = when {
    mime == null -> "file"
    mime.startsWith("video/") -> "film"
    mime.startsWith("audio/") -> "music"
    else -> "file"
}
