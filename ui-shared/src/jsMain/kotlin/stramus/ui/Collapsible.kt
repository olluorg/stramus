package stramus.ui

import kotlinx.coroutines.delay
import react.FC
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useState
import web.cssom.ClassName

/**
 * How long a section takes to fold away or unfold. Long enough not to blink, short enough that a
 * section opened by mistake is closed again without waiting for it. Kept in step with the
 * `.collapsible` transition in `index.html`.
 */
private const val COLLAPSE_MS = 180L

external interface CollapsibleProps : PropsWithChildren {
    /** Whether the content is shown. Changing it plays the fold, in either direction. */
    var open: Boolean
}

/**
 * Folds a section's content away instead of dropping it out of the page. The wrapper is a one-row
 * grid whose row goes from `1fr` to `0fr`, so the height is animated without anything having to
 * measure it first — the content keeps its natural size, and the row it sits in shrinks around it.
 *
 * Two things happen off the clock, both timed to the fold rather than to a `transitionend` that a
 * hidden or motion-free section would never fire:
 *
 * - the children are dropped only once the section has finished closing, so the fold has something
 *   to shrink — but a section that is already closed builds nothing at all, which is what makes a
 *   collapsed section cheap to keep;
 * - the clipping that makes the fold look like one is lifted once the section is open, or it would
 *   go on cutting the lift off a hovered card at the edge of the grid.
 */
val Collapsible = FC<CollapsibleProps> { props ->
    val open = props.open
    var mounted by useState(open)
    var settled by useState(open)

    // Both waits are cancelled if the section is toggled again before the fold is over, so a section
    // clicked twice in a row never drops its children out from under the animation that is opening it.
    useEffect(open) {
        if (open) {
            mounted = true
            delay(COLLAPSE_MS)
            settled = true
        } else {
            settled = false
            delay(COLLAPSE_MS)
            mounted = false
        }
    }

    div {
        className = ClassName(
            buildString {
                append("collapsible")
                if (open) append(" open")
                if (open && settled) append(" settled")
            },
        )
        div {
            className = ClassName("collapsible-inner")
            if (open || mounted) +props.children
        }
    }
}
