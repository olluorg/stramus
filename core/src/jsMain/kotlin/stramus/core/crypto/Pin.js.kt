package stramus.core.crypto

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.Promise

// The browser's SHA-256 and CSPRNG. See the expectations in commonMain for what these are for, and for
// what the lock they back does and does not promise.

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

actual fun randomSalt(): String = webCrypto().getRandomValues(Uint8Array(16)).toHex()

actual suspend fun sha256Hex(input: String): String {
    val digest = webCrypto().subtle.digest("SHA-256", TextEncoder().encode(input)).await()
    return Uint8Array(digest).toHex()
}
