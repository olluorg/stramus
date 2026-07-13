@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.memo
import stramus.core.model.Card
import stramus.core.model.CardKind
import web.cssom.ClassName
import web.data.AllowedEffect
import web.data.DropEffect
import web.data.move
import kotlin.uuid.ExperimentalUuidApi

external interface CardTileProps : Props {
    var strings: Strings
    var card: Card
    var isDraggable: Boolean
    var readOnly: Boolean
    var isDragging: Boolean
    var acceptsDrop: Boolean

    // Each takes the card it happened to, so one callback serves every tile: a handler built per card
    // would be a new function on every render, and this component is memoized on its props being the
    // same ones as last time. See `App`, which holds these steady with `useCallback`.
    var onOpen: (Card) -> Unit
    var onRename: (Card, String) -> Unit
    var onDelete: (Card) -> Unit
    var onStartDrag: (Card) -> Unit
    var onEndDrag: () -> Unit
    var onDropHere: (Card) -> Unit
}

/**
 * One card. Its look depends on [Card.kind]: a link shows its favicon + URL, a note shows a snippet
 * of its markdown, a file shows an image thumbnail (or a file glyph). Its text comes from `strings`,
 * the active translations handed down by `App`. Hovering reveals rename and delete.
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
        val strings = props.strings

        div {
            className = ClassName(
                buildString {
                    append("card kind-${card.kind.id}")
                    if (props.isDragging) append(" dragging")
                },
            )
            draggable = props.isDraggable
            onClick = { props.onOpen(card) }
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

            // Leading glyph / thumbnail.
            when (card.kind) {
                CardKind.LINK -> Favicon {
                    url = card.url
                    favicon = card.favicon
                }
                CardKind.NOTE -> span { className = ClassName("glyph"); +"📝" }
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
                        span { className = ClassName("glyph"); +fileGlyph(card.mime) }
                    }
                }
            }

            div {
                className = ClassName("card-body")
                div {
                    className = ClassName("card-title")
                    +card.title
                }
                div {
                    className = ClassName("card-url")
                    +when (card.kind) {
                        CardKind.LINK -> card.url
                        CardKind.NOTE -> (card.content ?: "").replace("\n", " ").ifBlank { strings.emptyNote }
                        CardKind.FILE -> card.mime ?: strings.fileLabel
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
                            e.stopPropagation()
                            val name = browserPrompt(strings.cardNamePrompt, card.title)
                            if (!name.isNullOrBlank() && name.trim() != card.title) props.onRename(card, name.trim())
                        }
                        +"✎"
                    }
                    button {
                        className = ClassName("icon del")
                        hint(strings.deleteCardHint)
                        onClick = { e ->
                            e.stopPropagation()
                            props.onDelete(card)
                        }
                        +"×"
                    }
                }
            }
        }
    },
)

private fun fileGlyph(mime: String?): String = when {
    mime == null -> "📄"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    mime == "application/pdf" -> "📕"
    else -> "📄"
}
