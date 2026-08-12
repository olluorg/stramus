package stramus.ui

import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/**
 * How long the pointer has to rest on a card before its picture is shown.
 *
 * Shorter than a tooltip's [HINT_DELAY_MS]: a preview is what the user came to the card for, not an
 * explanation of a glyph they could not read, and a pointer that lands on a video card is usually asking
 * "which video is this". Long enough, still, that crossing the grid on the way to the sidebar shows
 * nothing at all.
 */
private const val PREVIEW_DELAY_MS = 260

/** How far the preview stands off the card it belongs to. */
private const val PREVIEW_GAP = 8.0

/**
 * The picture's own width, and roughly how tall the whole popup gets — the 16:9 frame plus a couple of
 * lines of caption plus the padding around them. As with [HINT_HEIGHT_GUESS] the height is a guess made
 * before anything is measured: being a little out means the popup flips above the card a little early,
 * which nobody notices, and measuring for real would cost a second render.
 */
private const val PREVIEW_WIDTH = 288.0
private const val PREVIEW_HEIGHT_GUESS = 230.0

/** The same guess for a popup that has lost its picture and is two lines of text in a box. */
private const val CAPTION_HEIGHT_GUESS = 60.0

/**
 * The picture shown when the pointer rests on a card that has one — a saved video's still frame, kept
 * with the card since the day it was saved (see `Thumbs.kt`).
 *
 * A sibling of [HintLayer] in every respect, and for the same reason: one element at the root of the
 * page, pinned to the viewport, because anything drawn inside a card is drawn inside the content area
 * that scrolls it, and a scroll box clips what leaves it however high the z-index. The cards themselves
 * only carry the image (`data-preview`); this watches for one being hovered and draws it.
 *
 * The two never appear together — a card carrying a preview is passed over by the tooltip watch, and its
 * `data-hint` words are drawn here, under the picture, instead.
 *
 * Which is why a picture that will not load does not simply close this: the tooltip cannot step back in
 * for it, the card carrying a `data-preview` at all being what turns the tooltip off. So the popup drops
 * the frame and keeps the words, and a card whose video has been deleted — or whose every frame is
 * unreachable because YouTube is down — reads exactly as a card with no picture ever did.
 */
val PreviewLayer = FC<Props> {
    var target by useState<HintTarget?>(null)

    // The pictures that would not load. Remembered so the frame is not attempted a second time, which
    // would flash an empty rectangle over the card on every hover for as long as the outage lasts.
    val broken = useRef(mutableSetOf<String>())

    useEffectOnce {
        val stopWatching = onHoverTarget(PREVIEW_DELAY_MS, PREVIEW_ATTR) { target = it }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    val shown = target ?: return@FC
    val hasFrame = shown.text !in broken.current!!
    // Neither a picture nor anything to say. There is no popup to be made out of that.
    if (!hasFrame && shown.caption == null) return@FC
    val viewW = viewportWidth()
    val viewH = viewportHeight()

    div {
        className = ClassName("preview-pop")
        // Placement is the one thing about a popup that cannot be said in a stylesheet: it is wherever
        // the card happens to be. Everything it *looks* like is `.preview-pop` in index.html.
        val css = js("({})")
        // Hugging the near edge, as a tooltip does — but a preview has a width of its own, so the far
        // edge has to be checked against that width rather than against a box that shrinks to its text.
        if (shown.left + PREVIEW_WIDTH > viewW) {
            css.right = "${(viewW - shown.right).coerceAtLeast(PREVIEW_GAP)}px"
        } else {
            css.left = "${shown.left.coerceAtLeast(PREVIEW_GAP)}px"
        }
        // Without the frame there is only a line or two of text left, so the popup no longer needs the
        // room a picture needs — and should not flip above the card pretending that it does.
        val heightGuess = if (hasFrame) PREVIEW_HEIGHT_GUESS else CAPTION_HEIGHT_GUESS
        if (shown.bottom + PREVIEW_GAP + heightGuess < viewH) {
            css.top = "${shown.bottom + PREVIEW_GAP}px"
        } else {
            css.bottom = "${viewH - shown.top + PREVIEW_GAP}px"
        }
        asDynamic().style = css

        if (hasFrame) {
            img {
                className = ClassName("preview-img")
                src = shown.text
                alt = ""
                draggable = false
                // Redrawn without the picture rather than closed — see the note on this component. The
                // frame is remembered as broken first, so the redraw does not simply try it again.
                onError = {
                    broken.current!! += shown.text
                    target = shown.copy()
                }
            }
        }
        shown.caption?.let { caption ->
            div {
                className = ClassName("preview-caption")
                +caption
            }
        }
    }
}
