@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/** The file store: what it takes, what it refuses, and whose files it will hand over. */
class BlobTest {

    private val ada = Uuid.random()
    private val grace = Uuid.random()

    @Test
    fun `a file goes up and comes back`() = runTest {
        val blobs = newStore()
        val bytes = "a picture, honestly".encodeToByteArray()
        val sha = sha256Hex(bytes)

        assertEquals(listOf(sha), blobs.missing(ada, listOf(sha)))
        blobs.put(ada, sha, bytes)

        assertTrue(blobs.missing(ada, listOf(sha)).isEmpty(), "the server should not ask for it twice")
        assertEquals(bytes.toList(), blobs.get(ada, sha)!!.toList())
    }

    @Test
    fun `bytes that do not match the hash they were sent under are refused`() = runTest {
        val blobs = newStore()
        val honest = "the real file".encodeToByteArray()
        val sha = sha256Hex(honest)

        // The name is the hash, so a caller who could put *any* bytes under a chosen name could replace
        // another account's file — or their own card's — with something else entirely. The server checks
        // rather than trusts.
        assertFailsWith<AccountException> { blobs.put(ada, sha, "something else".encodeToByteArray()) }
        assertNull(blobs.get(ada, sha))
    }

    @Test
    fun `a file larger than the limit is refused`() = runTest {
        val blobs = newStore(maxBlobBytes = 1024)
        val big = ByteArray(2048)
        assertFailsWith<QuotaException> { blobs.put(ada, sha256Hex(big), big) }
    }

    @Test
    fun `an account cannot go past its quota`() = runTest {
        val blobs = newStore(quotaBytes = 3000)
        repeat(3) { i ->
            val bytes = ByteArray(1000) { i.toByte() }
            blobs.put(ada, sha256Hex(bytes), bytes)
        }
        val oneTooMany = ByteArray(1000) { 9 }
        assertFailsWith<QuotaException> { blobs.put(ada, sha256Hex(oneTooMany), oneTooMany) }
    }

    @Test
    fun `one account cannot read another's files`() = runTest {
        val blobs = newStore()
        val bytes = "ada's holiday photograph".encodeToByteArray()
        val sha = sha256Hex(bytes)
        blobs.put(ada, sha, bytes)

        // Grace knows the hash — she may even have the same file. That is not a claim on Ada's copy: to
        // this account, the file does not exist, and it has to answer the same way it would for a hash
        // nobody has ever uploaded.
        assertNull(blobs.get(grace, sha))
        assertEquals(listOf(sha), blobs.missing(grace, listOf(sha)))
    }

    @Test
    fun `deleting an account takes its files with it`() = runTest {
        val blobs = newStore()
        val bytes = "gone".encodeToByteArray()
        val sha = sha256Hex(bytes)
        blobs.put(ada, sha, bytes)

        blobs.deleteAll(ada)

        assertNull(blobs.get(ada, sha), "\"forget me\" has to mean the bytes too, not just the rows")
    }
}

private fun newStore(
    maxBlobBytes: Int = 10 * 1024 * 1024,
    quotaBytes: Long = 500L * 1024 * 1024,
): BlobStore {
    val dir = createTempDirectory("stramus-blobs")
    val config = ServerConfig(
        databasePath = dir.resolve("server.db").toString(),
        blobDir = dir.resolve("blobs").toString(),
        maxBlobBytes = maxBlobBytes,
        quotaBytes = quotaBytes,
    )
    return BlobStore(openServerDatabase(config), config)
}
