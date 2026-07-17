@file:OptIn(ExperimentalEncodingApi::class)

package stramus.core.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A file card's bytes are held — and drawn, and downloaded — as a `data:` URI, because that is the one form
 * the browser will hand straight to an `<img>` or an `<a download>`. On the wire they are raw bytes.
 *
 * The conversion is here rather than at the call site because getting it wrong is silent: base64 with the
 * padding stripped, or with the `data:image/png;base64,` prefix left on, still *looks* like a file, and the
 * hash it produces is stable — so the wrong bytes would be uploaded, stored, fetched back, and only fail on
 * the day someone opens the card.
 *
 * Raw bytes and not the `data:` string itself, incidentally, because base64 is a third larger: sending the
 * string would put a permanent 33% tax on every file this product ever syncs.
 */
object DataUri {

    /** The bytes of a `data:…;base64,…` URI, or null if it is not one. */
    fun bytesOf(dataUri: String): ByteArray? {
        val comma = dataUri.indexOf(',')
        if (!dataUri.startsWith("data:") || comma < 0) return null
        val header = dataUri.substring(5, comma)
        if (!header.contains("base64")) return null
        return runCatching { Base64.decode(dataUri.substring(comma + 1)) }.getOrNull()
    }

    /** The `data:` URI for [bytes] of type [mime] — what the card is written back as. */
    fun of(bytes: ByteArray, mime: String?): String =
        "data:${mime ?: "application/octet-stream"};base64,${Base64.encode(bytes)}"
}
