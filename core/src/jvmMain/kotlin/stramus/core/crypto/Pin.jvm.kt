package stramus.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

// The same two primitives the browser gives the app, from the JVM's own library. No product code runs
// here — this is what lets the store, the migration of a user's database and the sync engine be
// exercised by a test.

private val random = SecureRandom()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

actual fun randomSalt(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

actual suspend fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray()).toHex()

actual suspend fun sha256HexBytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
