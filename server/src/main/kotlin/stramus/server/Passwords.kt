package stramus.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Password hashing (Argon2id) and the hashing of the secrets the server stores but must not know.
 *
 * Two different jobs, deliberately not one function:
 *
 *  - A **password** is chosen by a person, and so is guessable. It is hashed slowly and with memory to
 *    spend — [hashPassword] — so that a stolen database is worth little per guess.
 *  - A **token** is 32 random bytes from a CSPRNG. There is nothing to guess, so it is hashed with plain
 *    SHA-256 — [hashToken] — which is fast, and fast is right: the alternative is to make every refresh
 *    call pay Argon2's memory for protection it does not need.
 *
 * The parameters below are the OWASP baseline (64 MiB, 3 passes). They are a cost the *server* pays on
 * every sign-in, so they are not free money — but sign-ins are rare and guessing is not.
 */

private const val ARGON2_MEMORY_KIB = 64 * 1024
private const val ARGON2_ITERATIONS = 3
private const val ARGON2_PARALLELISM = 1
private const val ARGON2_HASH_BYTES = 32
private const val SALT_BYTES = 16

private val random = SecureRandom()

/** The stored form of [password]: Argon2id over a fresh salt, both encoded into one string. */
fun hashPassword(password: String): String {
    val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
    val hash = argon2(password, salt)
    val encoder = Base64.getEncoder().withoutPadding()
    return "argon2id\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
}

/** Whether [password] is the one [stored] was made from. False for anything it cannot parse. */
fun verifyPassword(password: String, stored: String): Boolean {
    val parts = stored.split('$')
    if (parts.size != 3 || parts[0] != "argon2id") return false
    val decoder = Base64.getDecoder()
    val salt = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return false
    val expected = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
    // Constant-time: a comparison that returns early leaks, byte by byte, how much of the guess was
    // right — which is a way of guessing a hash without guessing the password.
    return MessageDigest.isEqual(argon2(password, salt), expected)
}

private fun argon2(password: String, salt: ByteArray): ByteArray {
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(ARGON2_ITERATIONS)
        .withMemoryAsKB(ARGON2_MEMORY_KIB)
        .withParallelism(ARGON2_PARALLELISM)
        .withSalt(salt)
        .build()
    val generator = Argon2BytesGenerator().apply { init(parameters) }
    val hash = ByteArray(ARGON2_HASH_BYTES)
    generator.generateBytes(password.toCharArray(), hash)
    return hash
}

/** A fresh secret for the client to hold: 32 bytes of CSPRNG, URL-safe so it survives a header. */
fun newSecret(): String {
    val bytes = ByteArray(32).also { random.nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The stored form of a secret this server issued — a refresh token, a one-time code. SHA-256, hex.
 *
 * A one-time code is only six digits, so this is *not* enough to make a leaked `login_codes` table
 * harmless — a million hashes is nothing. What keeps a code safe is that it dies in ten minutes and
 * after five wrong guesses; the hash only means the database itself does not read back as the code.
 */
fun hashToken(token: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
