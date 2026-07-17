package stramus.core.crypto

/**
 * The hashing behind the section PIN lock, and the hashing the sync engine uses to tell a row that
 * changed from one that did not.
 *
 * A PIN is never stored. Each locked section gets a random [randomSalt], and what is written next to
 * it is [hashPin] — the SHA-256 of salt + PIN. Unlocking re-derives the hash from what was typed and
 * compares, so the database holds nothing that reads back as the PIN.
 *
 * What this is *not*: encryption. The rows themselves stay in plain text in the local SQLite file,
 * and that file lives in the browser's IndexedDB, where anyone at this machine can open it with the
 * devtools. The lock keeps a section off the screen — its collections unnamed in the sidebar, its
 * cards out of search and out of export — which is what a PIN on a local bookmark manager can
 * honestly promise. (And once an account is signed in, the section syncs like any other: the server
 * sees it. The PIN is a lock on the screen, not on the data.)
 *
 * Two implementations, because the two platforms hand out SHA-256 differently: the browser's Web Crypto
 * in the app, `java.security` under the tests. Neither is a choice about the algorithm — the hashes have
 * to come out identical either way, or a database written by one would be unreadable to the other.
 */

/** A fresh 16-byte salt, hex-encoded — one per locked section, so equal PINs hash differently. */
expect fun randomSalt(): String

/** Hex SHA-256 of [input]. What the sync engine hashes a row's canonical form with. */
expect suspend fun sha256Hex(input: String): String

/** Hex SHA-256 of [bytes] — the name a file is stored and fetched under, on both sides. */
expect suspend fun sha256HexBytes(bytes: ByteArray): String

/** The stored form of [pin] under [salt]: hex SHA-256 of the two, joined by a colon. */
suspend fun hashPin(pin: String, salt: String): String = sha256Hex("$salt:$pin")
