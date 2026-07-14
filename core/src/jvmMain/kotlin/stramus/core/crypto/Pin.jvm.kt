package stramus.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

// The same two primitives the browser gives the app, from the JVM's own library. No product code runs
// here — this is what lets the store (and the migration of a user's database) be exercised by a test.

private val random = SecureRandom()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

actual fun randomSalt(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

actual suspend fun hashPin(pin: String, salt: String): String =
    MessageDigest.getInstance("SHA-256").digest("$salt:$pin".encodeToByteArray()).toHex()
