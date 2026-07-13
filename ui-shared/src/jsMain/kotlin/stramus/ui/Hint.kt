package stramus.ui

import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * How long the pointer has to rest on a control before it is asking about it. Long enough that
 * crossing a list of tabs on the way somewhere else says nothing at all, and that the tooltips do not
 * chase a pointer moving between the sidebar's buttons.
 */
private const val HINT_DELAY_MS = 800

/** How far the tooltip stands off the control it belongs to. */
private const val HINT_GAP = 6.0

/**
 * Roughly how tall a tooltip gets, used only to decide whether it still fits below the control or has
 * to go above it. It is a guess made before the thing is measured — being a little out means a tooltip
 * flips a little early, which nobody notices; measuring for real would mean a second render.
 */
private const val HINT_HEIGHT_GUESS = 90.0

/**
 * Every tooltip in the app, drawn as one element at the root of the page.
 *
 * The controls themselves only carry the text (`data-hint`, set by [hint]); this watches the document
 * for the pointer resting on one of them ([onHintTarget]) and puts the tooltip over the page, pinned
 * to the viewport rather than to the control. That is the whole point of it being here: a tooltip
 * rendered inside its control is rendered inside whatever scrolls it — the tabs list, the sidebar,
 * the content area — and a scroll box clips what leaves it, no matter what z-index it is given. This
 * one is outside all of them.
 *
 * It hugs the near edge of the control (right edge on the right half of the screen, left on the left)
 * and sits below it, or above it where the bottom of the window is closer than the tooltip is tall.
 */
val HintLayer = FC<Props> {
    var target by useState<HintTarget?>(null)

    useEffectOnce {
        val stopWatching = onHintTarget(HINT_DELAY_MS) { target = it }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    val shown = target ?: return@FC
    val viewW = viewportWidth()
    val viewH = viewportHeight()

    div {
        className = ClassName("hint-pop")
        // The placement is the only thing about a tooltip that cannot be said in a stylesheet: it is
        // wherever the control happens to be. Everything else — the colours, the padding, the width it
        // may take — is `.hint-pop` in index.html.
        val css = js("({})")
        if (shown.right > viewW / 2) {
            css.right = "${viewW - shown.right}px"
        } else {
            css.left = "${shown.left}px"
        }
        if (shown.bottom + HINT_GAP + HINT_HEIGHT_GUESS < viewH) {
            css.top = "${shown.bottom + HINT_GAP}px"
        } else {
            css.bottom = "${viewH - shown.top + HINT_GAP}px"
        }
        asDynamic().style = css
        +shown.text
    }
}
