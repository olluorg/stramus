package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.memo
import stramus.core.platform.CapturedTab
import stramus.core.url.hostOf
import web.cssom.ClassName
import web.data.DropEffect
import web.data.move

external interface TabCardProps : Props {
    var strings: Strings
    var tab: CapturedTab

    /** Whether the card spells the tab's address out in full under the title, or just its host. See
     *  the `cardUrls` setting — the same choice a saved link card makes. */
    var showUrl: Boolean
    var isDragging: Boolean
    var acceptsDrop: Boolean
    var isDropTarget: Boolean

    // Each hands back the tab it happened to, for the same reason [TabRowProps] does: one callback
    // serves every card, so a memoized card's props stay the ones it already has.
    var onGoTo: (CapturedTab) -> Unit
    var onClose: (CapturedTab) -> Unit
    var onStartDrag: (CapturedTab) -> Unit
    var onEndDrag: () -> Unit
    var onOver: (CapturedTab) -> Unit
    var onDropHere: (CapturedTab) -> Unit
}

/**
 * One open browser tab, drawn as a [CardTile]-shaped tile instead of [TabRow]'s list row — the shape
 * the tabs sidebar switches to under the `tabsCardView` setting, so an open tab and a saved card read
 * as the same kind of object side by side.
 *
 * Unlike [CardTile] this is always a plain `<div>`, never an `<a href>`: the tab is already open, so a
 * click has to focus it ([onGoTo]) rather than navigate anywhere or hand the browser a link to open in
 * a new tab, which would only duplicate a tab that already exists.
 *
 * Drag-and-drop and the × mirror [TabRow] exactly; only the layout around them is a card's.
 */
val TabCard = memo(
    FC<TabCardProps> { props ->
        val tab = props.tab
        val strings = props.strings

        div {
            className = ClassName(
                buildString {
                    append("card kind-tab")
                    if (tab.active) append(" current")
                    if (props.isDragging) append(" dragging")
                    if (props.isDropTarget) append(" drop-target")
                },
            )
            hint(tab.title.ifBlank { tab.url })
            draggable = true
            onClick = { props.onGoTo(tab) }
            onDragStart = { e ->
                e.dataTransfer.setData("text/plain", tab.id.toString())
                props.onStartDrag(tab)
            }
            onDragEnd = { props.onEndDrag() }
            if (props.acceptsDrop) {
                onDragOver = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    e.dataTransfer.dropEffect = DropEffect.move
                    props.onOver(tab)
                }
                onDrop = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    props.onDropHere(tab)
                }
            }

            Favicon {
                url = tab.url
                favicon = tab.favicon
            }
            div {
                className = ClassName("card-body")
                div {
                    className = ClassName("card-title")
                    +tab.title.ifBlank { hostOf(tab.url) }
                }
                div {
                    className = ClassName("card-url")
                    +(if (props.showUrl) tab.url else hostOf(tab.url))
                }
            }
            div {
                className = ClassName("card-tools")
                button {
                    className = ClassName("icon del")
                    hint(strings.closeTab)
                    onClick = { e ->
                        e.stopPropagation() // closing the tab is not jumping to it
                        props.onClose(tab)
                    }
                    +"×"
                }
            }
        }
    },
)
