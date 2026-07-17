@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import stramus.protocol.SyncRow

/**
 * The sweep for files no card holds any more.
 *
 * The client cannot do this: the same bytes may be held by another card on another device, so "delete this
 * card" is not "delete this file". The server is the only thing that can see every card of every device at
 * once, and so the only thing in a position to know what is landfill.
 */
class BlobGcTest {

    private val user = Uuid.random()
    private val device = Uuid.random()

    @Test
    fun `a file no card names any more is swept`() = runTest {
        val (blobs, sync) = newServer()
        val bytes = "a diagram".encodeToByteArray()
        val sha = sha256Hex(bytes)

        sync.sync(user, device, since = 0, pushed = listOf(fileCard("c1", sha)))
        blobs.put(user, sha, bytes)

        // The card goes. The bytes do not, because nothing on the client can prove no other card wants them.
        sync.sync(user, device, since = 1, pushed = listOf(tombstone("c1")))
        assertNotNull(blobs.get(user, sha), "the bytes should still be there until the sweep")

        assertEquals(1, blobs.collectGarbage(grace = 0.seconds))
        assertNull(blobs.get(user, sha), "the file should have been swept once no card named it")
    }

    @Test
    fun `a file a card still names survives`() = runTest {
        val (blobs, sync) = newServer()
        val bytes = "still wanted".encodeToByteArray()
        val sha = sha256Hex(bytes)

        sync.sync(user, device, since = 0, pushed = listOf(fileCard("c1", sha)))
        blobs.put(user, sha, bytes)

        assertEquals(0, blobs.collectGarbage(grace = 0.seconds))
        assertNotNull(blobs.get(user, sha))
    }

    @Test
    fun `a file held by a second card survives the first card's deletion`() = runTest {
        val (blobs, sync) = newServer()
        val bytes = "the same PDF, twice".encodeToByteArray()
        val sha = sha256Hex(bytes)

        // Content-addressed storage means two cards, one file. Deleting one of them must not take the file
        // out from under the other — which is exactly the mistake a client-side "delete the blob with the
        // card" would make, because the client cannot see the other card.
        sync.sync(user, device, since = 0, pushed = listOf(fileCard("c1", sha), fileCard("c2", sha)))
        blobs.put(user, sha, bytes)

        sync.sync(user, device, since = 1, pushed = listOf(tombstone("c1")))

        assertEquals(0, blobs.collectGarbage(grace = 0.seconds))
        assertNotNull(blobs.get(user, sha), "the second card still holds these bytes")
    }

    @Test
    fun `a file just uploaded is left alone even if no card names it yet`() = runTest {
        val (blobs, _) = newServer()
        val bytes = "uploaded first, pushed second".encodeToByteArray()
        val sha = sha256Hex(bytes)
        blobs.put(user, sha, bytes)

        // The card row is still in flight — or the device died between the two. Sweeping now would take the
        // bytes out from under a card that is about to arrive, and the user would open an empty file.
        assertEquals(0, blobs.collectGarbage(grace = 24.hours))
        assertNotNull(blobs.get(user, sha))
    }
}

// ---- helpers ---------------------------------------------------------------------------------------

private fun newServer(): Pair<BlobStore, SyncService> {
    val dir = createTempDirectory("stramus-gc")
    val config = ServerConfig(
        databasePath = dir.resolve("server.db").toString(),
        blobDir = dir.resolve("blobs").toString(),
    )
    val db = openServerDatabase(config)
    return BlobStore(db, config) to SyncService(db)
}

private fun fileCard(id: String, sha: String) = SyncRow(
    tbl = "cards",
    id = id,
    updatedAt = Clock.System.now().toString(),
    payload = JsonObject(
        mapOf(
            "kind" to JsonPrimitive("file"),
            "title" to JsonPrimitive("diagram.png"),
            "blobSha" to JsonPrimitive(sha),
        ),
    ),
)

private fun tombstone(id: String) = SyncRow(
    tbl = "cards",
    id = id,
    updatedAt = Clock.System.now().toString(),
    deletedAt = Clock.System.now().toString(),
    payload = null,
)
