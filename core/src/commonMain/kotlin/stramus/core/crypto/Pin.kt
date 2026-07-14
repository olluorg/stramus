package stramus.core.crypto

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
 *
 * Two implementations, because the two platforms hand out SHA-256 differently: the browser's
 * Web Crypto in the app, `java.security` under the tests. Neither is a choice about the algorithm —
 * the stored hashes have to come out identical either way, or a database written by one would be
 * unreadable to the other.
 */

/** A fresh 16-byte salt, hex-encoded — one per locked section, so equal PINs hash differently. */
expect fun randomSalt(): String

/** The stored form of [pin] under [salt]: hex SHA-256 of the two, joined by a colon. */
expect suspend fun hashPin(pin: String, salt: String): String
