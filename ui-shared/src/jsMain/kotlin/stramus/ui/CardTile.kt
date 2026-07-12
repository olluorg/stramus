@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import stramus.core.model.Card
import stramus.core.model.CardKind
import web.cssom.ClassName
import web.data.AllowedEffect
import web.data.DropEffect
import web.data.move
import kotlin.uuid.ExperimentalUuidApi

/**
 * One card. Its look depends on [Card.kind]: a link shows its favicon + URL, a note shows a snippet
 * of its markdown, a file shows an image thumbnail (or a file glyph). Its text comes from [strings],
 * the active translations handed down by `App`. Hovering reveals [onRename] and [onDelete].
 *
 * When [isDraggable] it joins HTML5 drag-and-drop: [onStartDrag] / [onEndDrag] track it, and it is
 * dimmed while [isDragging]. It takes a drop only while [acceptsDrop] — i.e. while another card is
 * in flight — so that a dragged tab or collection falls through to the zone behind it instead. A
 * drop fires [onDropHere], which inserts the dragged card into *this* card's section, before it.
 *
 * In a [readOnly] collection the card is still opened and read; what it loses is the rename and
 * delete buttons — there is nothing here to reach for by accident.
 */
internal fun ChildrenBuilder.cardTile(
    strings: Strings,
    card: Card,
    isDraggable: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    readOnly: Boolean = false,
    isDragging: Boolean = false,
    acceptsDrop: Boolean = false,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    onDropHere: () -> Unit = {},
) {
    div {
        key = key(card.id)
        className = ClassName(
            buildString {
                append("card kind-${card.kind.id}")
                if (isDragging) append(" dragging")
            },
        )
        draggable = isDraggable
        onClick = { onOpen() }
        if (isDraggable) {
            onDragStart = { e ->
                // Firefox refuses to start a drag whose dataTransfer carries nothing.
                e.dataTransfer.setData("text/plain", card.id.toString())
                e.dataTransfer.effectAllowed = AllowedEffect.move
                onStartDrag()
            }
            onDragEnd = { onEndDrag() }
        }
        if (acceptsDrop) {
            onDragOver = { e ->
                e.preventDefault()
                e.dataTransfer.dropEffect = DropEffect.move
            }
            onDrop = { e ->
                e.preventDefault()
                e.stopPropagation() // this card decides the drop position, not the section behind it
                onDropHere()
            }
        }

        // Leading glyph / thumbnail.
        when (card.kind) {
            CardKind.LINK -> Favicon {
                url = card.url
                favicon = card.favicon
            }
            CardKind.NOTE -> span { className = ClassName("glyph"); +"📝" }
            CardKind.FILE ->
                if ((card.mime ?: "").startsWith("image/") && card.content != null) {
                    img {
                        className = ClassName("fav thumb")
                        src = card.content!!
                        alt = ""
                        draggable = false
                    }
                } else {
                    span { className = ClassName("glyph"); +fileGlyph(card.mime) }
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
        if (!readOnly) {
            div {
                className = ClassName("card-tools")
                button {
                    className = ClassName("icon edit")
                    title = strings.renameCard
                    onClick = { e ->
                        e.stopPropagation()
                        val name = browserPrompt(strings.cardNamePrompt, card.title)
                        if (!name.isNullOrBlank() && name.trim() != card.title) onRename(name.trim())
                    }
                    +"✎"
                }
                button {
                    className = ClassName("icon del")
                    onClick = { e ->
                        e.stopPropagation()
                        onDelete()
                    }
                    +"×"
                }
            }
        }
    }
}

private fun fileGlyph(mime: String?): String = when {
    mime == null -> "📄"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    mime == "application/pdf" -> "📕"
    else -> "📄"
}
