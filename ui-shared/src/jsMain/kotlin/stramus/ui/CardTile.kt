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
import kotlin.uuid.ExperimentalUuidApi

/**
 * One card. Its look depends on [Card.kind]: a link shows its favicon + URL, a note shows a snippet
 * of its markdown, a file shows an image thumbnail (or a file glyph). When [isDraggable] it joins
 * HTML5 drag-and-drop — [onStartDrag] / [onEndDrag] track it, and dropping another card on it fires
 * [onDropHere] (reorder within the collection).
 */
internal fun ChildrenBuilder.cardTile(
    card: Card,
    isDraggable: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    onDropHere: () -> Unit = {},
) {
    div {
        key = key(card.id)
        className = ClassName("card kind-${card.kind.id}")
        draggable = isDraggable
        onClick = { onOpen() }
        if (isDraggable) {
            onDragStart = { onStartDrag() }
            onDragEnd = { onEndDrag() }
            onDragOver = { it.preventDefault() }
            onDrop = { e ->
                e.preventDefault()
                onDropHere()
            }
        }

        // Leading glyph / thumbnail.
        when (card.kind) {
            CardKind.LINK -> img {
                className = ClassName("fav")
                src = card.favicon ?: ""
                alt = ""
                draggable = false // let the card be the drag source, not the image
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
                    CardKind.NOTE -> (card.content ?: "").replace("\n", " ").ifBlank { "Empty note" }
                    CardKind.FILE -> card.mime ?: "file"
                }
            }
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

private fun fileGlyph(mime: String?): String = when {
    mime == null -> "📄"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    mime == "application/pdf" -> "📕"
    else -> "📄"
}
