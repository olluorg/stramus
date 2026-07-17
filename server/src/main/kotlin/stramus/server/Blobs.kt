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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * Sweep the files no card holds any more.
     *
     * A card deleted on Tuesday leaves its bytes behind: the same file may be held by another card, so the
     * client cannot say "delete this blob", and nothing else is in a position to know. The server is —
     * every card of every device is here, and the set of hashes they name is exactly the set of files worth
     * keeping. Anything else is landfill, and for a file store landfill is measured in gigabytes.
     *
     * [grace] is why this is not simply "delete what nothing names". A file is uploaded *after* the card
     * that names it is pushed, but only just: a device that dies between the two — or one that uploads
     * while another device's card row is still in flight — would have its bytes swept from under it. A
     * blob younger than [grace] is left alone, whoever names it.
     */
    suspend fun collectGarbage(grace: Duration = 24.hours): Int {
        val now = Clock.System.now()
        val all = db.suspendAutocommit { Blobs.all() }
        if (all.isEmpty()) return 0

        var swept = 0
        all.groupBy { it.userId }.forEach { (userId, blobs) ->
            val held = heldShas(userId)
            blobs.filter { it.sha !in held && it.createdAt < now - grace }.forEach { orphan ->
                db.suspendTransaction {
                    Blobs.deleteWhere { where { (Blobs.sha eq orphan.sha) and (Blobs.userId eq userId) } }
                }
                // The bytes may be another account's as well: two people who saved the same file each have
                // their own row over one file on disk. The file goes only when the last row naming it does.
                val stillReferenced = db.suspendAutocommit { Blobs.findOne { where { Blobs.sha eq orphan.sha } } }
                if (stillReferenced == null) Files.deleteIfExists(pathOf(orphan.sha))
                swept++
            }
        }
        return swept
    }

    /** Every file hash named by a card this user still has. A tombstone names nothing — that is the point. */
    private suspend fun heldShas(userId: Uuid): Set<String> = db.suspendAutocommit {
        SyncRows.find { where { (SyncRows.userId eq userId) and (SyncRows.tbl eq "cards") } }
    }.mapNotNullTo(mutableSetOf()) { row ->
        if (row.deletedAt != null) return@mapNotNullTo null
        val payload = row.payload?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        payload?.get("blobSha")?.jsonPrimitive?.contentOrNull
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
