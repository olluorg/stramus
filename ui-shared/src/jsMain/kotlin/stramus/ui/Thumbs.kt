@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import stramus.core.db.StramusStore
import stramus.core.url.youtubeVideoId
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
 * The widest a wallpaper is kept. It stands behind the app under a translucent panel, so nothing is
 * read off it — and the picture the user picked is very likely a 12-megapixel photo that no `localStorage`
 * (a few megabytes for the whole origin) would take as a `data:` URI. Downscaled to this and re-encoded
 * as JPEG, a photo lands in a few hundred kilobytes and still fills a 4K screen without looking soft.
 */
private const val WALLPAPER_MAX_PX = 2048
private const val WALLPAPER_QUALITY = 0.78

/**
 * A card-sized preview of [dataUri], or null when there is none to make: the file is not an image, or
 * it is one the browser cannot decode. A card with no preview shows a glyph instead — no reason to
 * make the caller handle a failure that is a perfectly ordinary outcome.
 */
internal suspend fun makeThumb(dataUri: String, mime: String): String? {
    if (!mime.startsWith("image/")) return null
    if (mime == "image/svg+xml") return dataUri.takeIf { it.length <= SVG_INLINE_MAX }
    return downscale(dataUri, THUMB_MAX_PX, "image/webp", 0.75)
}

/**
 * The picture behind the app, cut down to something a browser will hold as a preference: see
 * [WALLPAPER_MAX_PX]. Null for a file that is not an image, or one this browser cannot decode — the
 * caller says so and keeps the wallpaper it already had.
 *
 * JPEG rather than WebP, unlike a card's thumbnail: a photograph at this size is the one case where the
 * format the whole world encodes photographs in is also the one that lands smallest, and a wallpaper is
 * never a screenshot of text or a logo with a transparent corner.
 */
internal suspend fun makeWallpaper(dataUri: String, mime: String): String? {
    if (!mime.startsWith("image/")) return null
    return downscale(dataUri, WALLPAPER_MAX_PX, "image/jpeg", WALLPAPER_QUALITY)
}

/**
 * [dataUri] redrawn no larger than [maxPx] on its longest side and re-encoded as [encodeMime]. Null
 * when the browser will not decode the source or will not produce that format — both of which are
 * ordinary outcomes here, not failures worth an exception.
 */
private suspend fun downscale(dataUri: String, maxPx: Int, encodeMime: String, quality: Double): String? {
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
                val scale = min(1.0, maxPx / max(w, h))
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
                    val encoded = runCatching { canvas.toDataURL(encodeMime, quality) as? String }.getOrNull()
                    done(encoded?.takeIf { it.startsWith("data:image/") })
                }
            }
        }
        image.onerror = { done(null) }
        image.src = dataUri
    }
}

/**
 * Where YouTube publishes the still frame of the video [pageUrl] points at, or null when it points at no
 * video. An `<img>` is pointed straight at this and nothing is ever kept: see [CardPreviews], the setting
 * that decides whether a card asks for it at all.
 *
 * `mqdefault` and not `hqdefault`: the former is a true 16:9 crop at 320x180, the latter a 4:3 frame with
 * black bars baked down its sides.
 *
 * The bytes are deliberately *not* fetched, cached or re-encoded, which an earlier version of this did.
 * Storing them meant keeping a copy of somebody else's picture indefinitely, re-encoding it, and — through
 * sync — serving it from our own machines, all of which the terms covering those frames disallow. Pointing
 * an `<img>` at the address the site itself publishes is what an embed does, and it is where this stops.
 */
internal fun videoThumbUrl(pageUrl: String): String? =
    youtubeVideoId(pageUrl)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }


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
