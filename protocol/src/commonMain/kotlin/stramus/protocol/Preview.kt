package stramus.protocol

import kotlinx.serialization.Serializable

/**
 * What a page says about itself — the three Open Graph tags a link card can be drawn from, fetched by
 * the server on a signed-in caller's behalf (see the server's `Previews.kt`).
 *
 * Every field is nullable and at least one of them is set: a page with a title and nothing else is a
 * perfectly good preview, and one with nothing at all is not a [LinkPreview] but a 204.
 *
 * [image] is an **address**, not bytes. The card points an `<img>` at it, exactly as the YouTube frame
 * is pointed at `i.ytimg.com` (see `Thumbs.kt`): the picture belongs to whoever published it, and
 * keeping a copy of it here — re-encoded, served from our machines, carried between devices by sync —
 * is the thing that was deliberately not done there and is deliberately not done here.
 */
@Serializable
data class LinkPreview(
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
)
