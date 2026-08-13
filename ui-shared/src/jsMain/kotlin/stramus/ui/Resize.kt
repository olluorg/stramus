package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useRef
import react.useState
import web.cssom.ClassName

/**
 * How much one press of an arrow key moves the seam. Coarse enough that the width is reached in a few
 * presses rather than a hundred, fine enough that the last press lands where it was aimed.
 */
private const val RESIZE_STEP = 16

/** Where a drag was picked up: the pointer's own x, and how wide the panel was at that moment. */
private class ResizeStart(val pointerX: Double, val width: Int)

external interface ResizeHandleProps : Props {
    /** The panel's width as it stands, in px — the number a drag or a key press starts from. */
    var width: Int

    /** Whether dragging to the right widens the panel, which is what a left-anchored one does. */
    var growsRight: Boolean

    var min: Int
    var max: Int

    /** The width a double-click puts back: what the panel opens at before anyone has dragged it. */
    var reset: Int

    /** The tooltip, which is also the handle's accessible name — see [hint]. */
    var label: String

    /** Handed the new width once the drag is let go, or once a key has moved it. */
    var onCommit: (Int) -> Unit
}

/**
 * The seam between a sidebar and the content pane, dragged to make the sidebar wider or narrower.
 * Drawn inside the panel it resizes, against the edge that faces the middle of the window (see
 * `.resizer` in index.html), so it follows the panel around when the two sidebars trade sides.
 *
 * The drag itself writes the width straight onto the panel's own element and only tells the app about
 * it on release. That is deliberate: the panel is a child of the whole app component, and re-rendering
 * that on every pointer move — a tree with every collection, every open tab and every card in it —
 * is the difference between a seam that follows the pointer and one that lags behind it. React sets
 * the same width from state on the render after the drag, so the DOM never disagrees with it for long,
 * and a render that happens mid-drag for some other reason leaves the inline width alone, its own prop
 * not having changed.
 *
 * The pointer is captured for the length of the drag, so the seam keeps following a pointer that has
 * run off the panel, over the content pane, or out of the window entirely.
 */
val ResizeHandle = FC<ResizeHandleProps> { props ->
    val start = useRef<ResizeStart>(null)
    var dragging by useState(false)

    // The width the pointer is asking for, clamped to what the panel is allowed to be.
    fun widthAt(from: ResizeStart, pointerX: Double): Int {
        val delta = pointerX - from.pointerX
        val raw = from.width + (if (props.growsRight) delta else -delta)
        return raw.toInt().coerceIn(props.min, props.max)
    }

    div {
        className = ClassName(if (dragging) "resizer dragging" else "resizer")
        hint(props.label)
        tabIndex = 0
        onPointerDown = { e ->
            // Without this the drag starts a text selection across everything it crosses.
            e.preventDefault()
            e.currentTarget.asDynamic().setPointerCapture(e.asDynamic().pointerId)
            start.current = ResizeStart(e.clientX, props.width)
            dragging = true
        }
        onPointerMove = { e ->
            val from = start.current
            if (from != null) setPanelWidth(e.currentTarget.asDynamic(), widthAt(from, e.clientX))
        }
        onPointerUp = { e ->
            val from = start.current
            if (from != null) {
                start.current = null
                dragging = false
                props.onCommit(widthAt(from, e.clientX))
            }
        }
        // A drag the system takes away — a touch turned into a scroll, a window that lost the pointer —
        // keeps the width the panel had reached rather than the one it started at: the panel is already
        // wearing it, and springing back would undo a drag the user thought was finished.
        onPointerCancel = { e ->
            val from = start.current
            if (from != null) {
                start.current = null
                dragging = false
                props.onCommit(widthAt(from, e.clientX))
            }
        }
        // The way back to the width the panel opens at, without hunting for it by hand.
        onDoubleClick = { props.onCommit(props.reset) }
        onKeyDown = { e ->
            val step = when (e.key) {
                "ArrowLeft" -> -RESIZE_STEP
                "ArrowRight" -> RESIZE_STEP
                else -> 0
            }
            if (step != 0) {
                e.preventDefault()
                val towards = if (props.growsRight) step else -step
                props.onCommit((props.width + towards).coerceIn(props.min, props.max))
            }
        }
    }
}

/**
 * Puts [width] on the panel the handle sits in. Both properties are set because the panels are flex
 * items with a basis of their own (`flex: 0 0 260px` and the like): a width alone would be overruled
 * by the basis, and a basis alone leaves `width` saying something else.
 */
private fun setPanelWidth(handle: dynamic, width: Int) {
    val panel = handle.parentElement ?: return
    panel.style.width = "${width}px"
    panel.style.flexBasis = "${width}px"
}
