@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import stramus.core.db.StramusStore
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.uuid.ExperimentalUuidApi

/**
 * The preview of an image file, as the card grid draws it. A file's bytes are not part of a card —
 * they are read only when the file is opened — so what the grid needs is this: an image small enough
 * to sit in a card and be carried around with it.
 */
private const val THUMB_MAX_PX = 96

/** An SVG this size is already smaller than any raster preview of it would be: keep it as it is. */
private const val SVG_INLINE_MAX = 32_768

/**
 * A card-sized preview of [dataUri], or null when there is none to make: the file is not an image, or
 * it is one the browser cannot decode. A card with no preview shows a glyph instead — no reason to
 * make the caller handle a failure that is a perfectly ordinary outcome.
 */
internal suspend fun makeThumb(dataUri: String, mime: String): String? {
    if (!mime.startsWith("image/")) return null
    if (mime == "image/svg+xml") return dataUri.takeIf { it.length <= SVG_INLINE_MAX }

    return suspendCoroutine { continuation ->
        var settled = false
        val done: (String?) -> Unit = { value ->
            if (!settled) {
                settled = true
                continuation.resume(value)
            }
        }
        val image = js("new Image()")
        image.onload = {
            val w = (image.width as? Number)?.toDouble() ?: 0.0
            val h = (image.height as? Number)?.toDouble() ?: 0.0
            if (w <= 0.0 || h <= 0.0) {
                done(null)
            } else {
                // Never scale up: a 16px icon stays 16px rather than becoming a blurry 96px one.
                val scale = min(1.0, THUMB_MAX_PX / max(w, h))
                val canvas = js("document.createElement('canvas')")
                canvas.width = round(w * scale)
                canvas.height = round(h * scale)
                val ctx = canvas.getContext("2d")
                if (ctx == null) {
                    done(null)
                } else {
                    ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
                    // A tainted canvas throws on read; a data-URI source cannot taint one, but the
                    // encoder may still refuse the format, and either way the answer is "no preview".
                    val encoded = runCatching { canvas.toDataURL("image/webp", 0.75) as? String }.getOrNull()
                    done(encoded?.takeIf { it.startsWith("data:image/") })
                }
            }
        }
        image.onerror = { done(null) }
        image.src = dataUri
    }
}

/**
 * Give a preview to the image files saved before previews existed — their bytes moved out of the
 * cards table, so without this the grid would have nothing to draw for them but a glyph.
 *
 * One card at a time, in the background: this is the only place that still loads whole files, and it
 * runs once per file in the database's lifetime. Returns whether anything was written, i.e. whether
 * what is on screen is now out of date.
 */
internal suspend fun backfillThumbs(store: StramusStore): Boolean {
    var written = false
    for (card in store.cards.imageFilesWithoutThumb()) {
        val data = store.cards.blob(card.id) ?: continue
        val thumb = makeThumb(data, card.mime ?: "") ?: continue
        store.cards.setThumb(card.id, thumb)
        written = true
    }
    return written
}
