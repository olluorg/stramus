@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.inList
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class BlobRow : Entity() {
    var sha by Blobs.sha
    var userId by Blobs.userId
    var size by Blobs.size
    var createdAt by Blobs.createdAt
}

/**
 * The files, by the hash of their contents.
 *
 * A blob belongs to a *user*, not to a card: the same PDF saved to two collections is one file, and a card
 * moved or renamed does not touch its bytes. Deleting a card therefore does not delete its blob — another
 * card may still hold the same bytes, and the client would have to prove otherwise. An unreferenced blob
 * costs disk and nothing else; sweeping them is a job for later, when there is something to sweep.
 *
 * Two users who happen to save the same file get two rows and two copies. Deduplicating *across* accounts
 * would be a way to ask whether someone else has a given file: upload it, see whether the server says it
 * already knew. That is a real leak, and the disk it would save is not worth it.
 */
object Blobs : Table<ServerDb, BlobRow>("blobs", ::BlobRow) {
    val sha by Column.Text().primaryKey()
    val userId by Column.UUID().primaryKey()
    val size by Column.Long()
    val createdAt by Column.Instant()

    init { sha; userId; size; createdAt }
}

/** The caller asked for more room than they have, or than anyone gets. Answered as 413. */
class QuotaException(message: String) : RuntimeException(message)

/**
 * The bytes themselves, on the disk — not in the database.
 *
 * SQLite would hold them perfectly well, and it would also turn a database that has to be copied for every
 * backup into one that is mostly other people's holiday photographs. The rows stay small and quick to copy;
 * the files sit under [root], named by their hash, and a lost one costs a re-upload rather than a restore.
 */
class BlobStore(private val db: SuspendDatabase<ServerDb>, private val config: ServerConfig) {

    private val root: Path = Path.of(config.blobDir)

    /** Which of [shas] this user has not uploaded. The only question the client asks before sending. */
    suspend fun missing(userId: Uuid, shas: List<String>): List<String> {
        if (shas.isEmpty()) return emptyList()
        val known = db.suspendAutocommit {
            Blobs.find { where { (Blobs.userId eq userId) and (Blobs.sha inList shas) } }
        }.map { it.sha }.toSet()
        return shas.filterNot { it in known }
    }

    /**
     * Take a file. The name is the hash of the bytes, so this checks them rather than trusting it: a caller
     * who could write bytes under someone else's hash could replace another user's file with their own.
     */
    suspend fun put(userId: Uuid, sha: String, bytes: ByteArray) {
        if (bytes.size > config.maxBlobBytes) {
            throw QuotaException("that file is larger than ${config.maxBlobBytes / (1024 * 1024)} MB")
        }
        val actual = sha256Hex(bytes)
        // Not a quota problem — a bad request, and possibly a hostile one: a caller who could store bytes
        // under a name of their choosing could put their own file where another card expects its own.
        if (actual != sha.lowercase()) throw AccountException(400, "the file does not match the hash it was sent under")

        // Already here, and identical — the hash says so. Nothing to write, and no quota to charge twice.
        if (db.suspendAutocommit { Blobs.findOne { where { (Blobs.sha eq actual) and (Blobs.userId eq userId) } } } != null) {
            return
        }

        val used = usedBytes(userId)
        if (used + bytes.size > config.quotaBytes) {
            throw QuotaException("that would take the account past its ${config.quotaBytes / (1024 * 1024)} MB")
        }

        val path = pathOf(actual)
        path.parent.createDirectories()
        if (!path.exists()) path.writeBytes(bytes)

        db.suspendTransaction {
            Blobs.insert(
                BlobRow().apply {
                    this.sha = actual
                    this.userId = userId
                    size = bytes.size.toLong()
                    createdAt = Clock.System.now()
                },
            )
        }
    }

    /** The bytes, or null if this user has no such file. Another user's file is "no such file". */
    suspend fun get(userId: Uuid, sha: String): ByteArray? {
        db.suspendAutocommit { Blobs.findOne { where { (Blobs.sha eq sha) and (Blobs.userId eq userId) } } }
            ?: return null
        val path = pathOf(sha)
        return if (path.exists()) path.readBytes() else null
    }

    /** What this account is using, in bytes. */
    suspend fun usedBytes(userId: Uuid): Long = db.suspendAutocommit {
        Blobs.find { where { Blobs.userId eq userId } }.sumOf { it.size }
    }

    /** Erase every file of an account. Part of "forget me", which has to mean the bytes too. */
    suspend fun deleteAll(userId: Uuid) {
        val rows = db.suspendAutocommit { Blobs.find { where { Blobs.userId eq userId } } }
        db.suspendTransaction { Blobs.deleteWhere { where { Blobs.userId eq userId } } }
        rows.forEach { row ->
            // The file on disk is shared by nobody else *of this user*, but another account may have
            // uploaded the same bytes. Only remove the file when no row anywhere still names it.
            val stillReferenced = db.suspendAutocommit {
                Blobs.findOne { where { Blobs.sha eq row.sha } }
            } != null
            if (!stillReferenced) Files.deleteIfExists(pathOf(row.sha))
        }
    }

    private fun pathOf(sha: String): Path = root.resolve(sha.take(2)).resolve(sha.drop(2))
}

/** SHA-256 of [bytes], hex. The server checks the name a file was sent under against its contents. */
fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
