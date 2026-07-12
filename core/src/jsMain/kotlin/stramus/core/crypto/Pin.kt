package stramus.core.crypto

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.Promise

/**
 * The hashing behind the section PIN lock.
 *
 * A PIN is never stored. Each locked section gets a random [randomSalt], and what is written next to
 * it is [hashPin] — the SHA-256 of salt + PIN. Unlocking re-derives the hash from what was typed and
 * compares, so the database holds nothing that reads back as the PIN.
 *
 * What this is *not*: encryption. The rows themselves stay in plain text in the local SQLite file,
 * and that file lives in the browser's IndexedDB, where anyone at this machine can open it with the
 * devtools. The lock keeps a section off the screen — its collections unnamed in the sidebar, its
 * cards out of search and out of export — which is what a PIN on a local bookmark manager can
 * honestly promise.
 */

private external interface WebCrypto {
    val subtle: SubtleCrypto
    fun getRandomValues(array: Uint8Array): Uint8Array
}

private external interface SubtleCrypto {
    fun digest(algorithm: String, data: Uint8Array): Promise<ArrayBuffer>
}

private external class TextEncoder {
    fun encode(input: String): Uint8Array
}

private fun webCrypto(): WebCrypto = js("crypto").unsafeCast<WebCrypto>()

private fun Uint8Array.toHex(): String = buildString {
    for (i in 0 until length) {
        append((this@toHex[i].toInt() and 0xff).toString(16).padStart(2, '0'))
    }
}

/** A fresh 16-byte salt, hex-encoded — one per locked collection, so equal PINs hash differently. */
fun randomSalt(): String = webCrypto().getRandomValues(Uint8Array(16)).toHex()

/** The stored form of [pin] under [salt]: hex SHA-256 of the two. */
suspend fun hashPin(pin: String, salt: String): String {
    val digest = webCrypto().subtle.digest("SHA-256", TextEncoder().encode("$salt:$pin")).await()
    return Uint8Array(digest).toHex()
}
