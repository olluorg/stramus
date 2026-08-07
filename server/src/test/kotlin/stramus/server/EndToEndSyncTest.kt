@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import stramus.protocol.BlobCheckRequest
import stramus.protocol.BlobCheckResponse
import stramus.protocol.LoginRequest
import stramus.protocol.RegisterRequest
import stramus.protocol.RowKey
import stramus.protocol.SyncRequest
import stramus.protocol.SyncResponse
import stramus.protocol.SyncRow
import stramus.protocol.TokenPair

/**
 * The server's half of synchronisation: what it decides is a conflict, who it decides wins, and how it
 * merges a counter — exercised over real HTTP, through the real protocol, against a real database.
 *
 * This used to also drive the real client store (`core`'s `SyncEngine`, on the JVM via a SQLite engine)
 * as both "devices", so the same code that ran as Kotlin/JS in the browser proved itself against the
 * server in one process. That client is now kidx-typed — browser-only, no JVM target — so this file
 * talks the protocol directly instead: [FakeDevice] is a hand-rolled stand-in that pushes and pulls
 * [SyncRow]s without any store behind it. What is proven here is narrower — the server's merge and
 * conflict rules, not the real client's translation of a card into one — but it is proven without
 * duplicating `core`'s repository logic a second time just to give this file something to drive.
 *
 * The client-side half of these behaviours (a losing note becoming a copy, `withUsage` deciding whether
 * counters are pushed at all, not re-pushing a row whose hash has not changed) lives in `core`'s
 * `SyncEngine`/`Codec` and is exercised by `core`'s own `jsTest` suite instead.
 */
class EndToEndSyncTest {

    @Test
    fun `a row pushed by one device appears in another device's delta`() = twoDevices { laptop, phone ->
        val card = Uuid.random()
        val collection = Uuid.random()
        laptop.sync(cardRow(card, collection, "Kotlin", Clock.System.now()))

        val toPhone = phone.sync()
        val row = toPhone.rows.singleOrNull { it.tbl == "cards" && it.id == card.toString() }
        assertEquals("Kotlin", row?.payload?.str("title"))
    }

    @Test
    fun `a deletion travels, and a later no-op sync does not resurrect it`() = twoDevices { laptop, phone ->
        val card = Uuid.random()
        val collection = Uuid.random()
        val t0 = Clock.System.now()
        laptop.sync(cardRow(card, collection, "Kotlin", t0))
        phone.sync()

        val t1 = t0 + 1.seconds
        laptop.sync(cardRow(card, collection, "Kotlin", t1, deletedAt = t1))
        val tombstone = phone.sync().rows.single { it.id == card.toString() }
        assertTrue(tombstone.deletedAt != null, "the delta should carry the tombstone, not silence")

        // Without a base version, a device that still had the row would push it straight back on its
        // next round and undo the deletion. A protocol-level device has no "next round" to get wrong —
        // this checks the server does not hand the row out again once both sides are caught up.
        assertTrue(phone.sync().rows.none { it.id == card.toString() })
        assertTrue(laptop.sync().rows.none { it.id == card.toString() })
    }

    @Test
    fun `two devices editing different rows do not conflict`() = twoDevices { laptop, phone ->
        val a = Uuid.random()
        val b = Uuid.random()
        val collection = Uuid.random()
        val t0 = Clock.System.now()
        laptop.sync(cardRow(a, collection, "first", t0), cardRow(b, collection, "second", t0))
        phone.sync()

        val fromLaptop = laptop.sync(cardRow(a, collection, "renamed on the laptop", t0 + 1.seconds))
        val fromPhone = phone.sync(cardRow(b, collection, "renamed on the phone", t0 + 1.seconds))
        assertTrue(fromLaptop.conflicts.isEmpty())
        assertTrue(fromPhone.conflicts.isEmpty())
    }

    @Test
    fun `two devices editing the same row without seeing each other's edit conflict, and the later write wins`() =
        twoDevices { laptop, phone ->
            val card = Uuid.random()
            val collection = Uuid.random()
            val t0 = Clock.System.now()
            laptop.sync(cardRow(card, collection, "Shopping", t0))
            phone.sync()

            // Both devices still hold the cursor from before either wrote — neither has seen the other's
            // edit, which is exactly what makes this a real conflict rather than an ordinary update.
            laptop.sync(cardRow(card, collection, "laptop version", t0 + 1.seconds))
            val fromPhone = phone.sync(cardRow(card, collection, "phone version", t0 + 2.seconds))

            assertTrue(fromPhone.conflicts.any { it.id == card.toString() }, "the server should have flagged this as a conflict")
            assertTrue(fromPhone.accepted.any { it.id == card.toString() }, "the later write (the phone's) should have won")

            val onLaptop = laptop.sync().rows.single { it.id == card.toString() }
            assertEquals("phone version", onLaptop.payload?.str("title"))
        }

    @Test
    fun `everything on the account turns up on a device that joins fresh`() = twoDevices { laptop, phone ->
        val section = Uuid.random()
        val collection = Uuid.random()
        val card = Uuid.random()
        val t0 = Clock.System.now()
        laptop.sync(
            sectionRow(section, "Work", t0),
            collectionRow(collection, section, "Reading", t0),
            cardRow(card, collection, "Kotlin", t0),
        )

        val pulled = phone.sync().rows.associateBy { it.tbl to it.id }
        assertTrue((("sections" to section.toString()) in pulled))
        assertTrue((("collections" to collection.toString()) in pulled))
        assertEquals("Kotlin", pulled.getValue("cards" to card.toString()).payload?.str("title"))
    }

    @Test
    fun `a file saved on one device can be opened on the other`() = twoDevices { laptop, phone ->
        val bytes = sampleFile(seed = 7)
        val sha = sha256Hex(bytes)

        assertEquals(listOf(sha), laptop.blobsMissing(listOf(sha)))
        laptop.blobUpload(sha, bytes)

        val downloaded = phone.blobDownload(sha)
        assertEquals(bytes.toList(), downloaded?.toList(), "the bytes should be fetchable by anyone on the account")
    }

    @Test
    fun `the same file referenced by two cards is uploaded once`() = twoDevices { laptop, _ ->
        val bytes = sampleFile(seed = 3)
        val sha = sha256Hex(bytes)

        assertEquals(listOf(sha), laptop.blobsMissing(listOf(sha)), "not uploaded yet")
        laptop.blobUpload(sha, bytes)
        // Content-addressed: asking again, for a second card that happens to hold the same bytes, finds
        // it already there — nothing about "which card" ever enters the question.
        assertEquals(emptyList(), laptop.blobsMissing(listOf(sha)))
    }

    @Test
    fun `two devices bumping the same counter merge by the larger of each field, not by last write`() =
        twoDevices { laptop, phone ->
            val t0 = Clock.System.now()
            laptop.sync(usageRow("kotlinlang.org/docs", hits = 3, lastUsedAt = t0))
            phone.sync()

            // The phone's write is later but counts *fewer* hits — last-write-wins would lose two of the
            // laptop's. A counter must not let that happen.
            val response = phone.sync(usageRow("kotlinlang.org/docs", hits = 1, lastUsedAt = t0 + 1.seconds))
            assertTrue(response.accepted.any { it.tbl == "usage" })
            assertTrue(response.conflicts.isEmpty(), "counters merge silently; they are never reported as conflicts")

            val merged = laptop.sync().rows.single { it.tbl == "usage" }
            assertEquals(3, merged.payload?.str("hits")?.toInt(), "the larger count must survive the merge")
        }

    @Test
    fun `forgetting a page beats a stale count, but a use made after the forget brings it back`() =
        twoDevices { laptop, phone ->
            val t0 = Clock.System.now()
            laptop.sync(usageRow("kotlinlang.org/docs", hits = 5, lastUsedAt = t0))
            phone.sync()

            val forgottenAt = t0 + 1.seconds
            laptop.sync(usageRow("kotlinlang.org/docs", hits = 5, lastUsedAt = t0, deletedAt = forgottenAt))

            // The phone made this push before it ever heard about the forget (it is still on the cursor
            // from before the forget happened). A stale count must not undo an instruction it never saw.
            val stale = phone.sync(usageRow("kotlinlang.org/docs", hits = 6, lastUsedAt = t0 + 500.milliseconds))
            assertTrue(stale.accepted.none { it.tbl == "usage" }, "a count made before the forget must not revive the page")

            // The user opens the page again, after the forget — which is the only thing that could mean
            // they want it back, and it counts from nothing, not from the five visits that were forgotten.
            val revived = phone.sync(usageRow("kotlinlang.org/docs", hits = 1, lastUsedAt = forgottenAt + 1.seconds))
            assertTrue(revived.accepted.any { it.tbl == "usage" })

            val onLaptop = laptop.sync().rows.single { it.tbl == "usage" }
            assertEquals(null, onLaptop.deletedAt)
            assertEquals(1, onLaptop.payload?.str("hits")?.toInt())
        }

    @Test
    fun `a device that is already caught up gets an empty delta`() = twoDevices { laptop, _ ->
        laptop.sync(cardRow(Uuid.random(), Uuid.random(), "Kotlin", Clock.System.now()))

        val again = laptop.sync()
        assertTrue(again.rows.isEmpty())
        assertTrue(again.accepted.isEmpty())
    }
}

// ---- harness ---------------------------------------------------------------------------------------

private val json = Json { ignoreUnknownKeys = true }

/**
 * A hand-rolled stand-in for a client: it pushes and pulls [SyncRow]s and remembers its own cursor, but
 * holds no store and applies no merge logic of its own — the server's merge and conflict rules are what
 * these tests are about.
 */
private class FakeDevice(
    private val http: HttpClient,
    private val accessToken: String,
    val deviceId: Uuid = Uuid.random(),
) {
    var since: Long = 0

    suspend fun sync(vararg push: SyncRow): SyncResponse {
        val response: SyncResponse = http.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(SyncRequest(deviceId.toString(), since, push.toList()))
        }.body()
        since = response.rev
        return response
    }

    suspend fun blobsMissing(shas: List<String>): List<String> =
        http.post("/v1/blobs/check") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(BlobCheckRequest(shas))
        }.body<BlobCheckResponse>().missing

    suspend fun blobUpload(sha: String, bytes: ByteArray) {
        http.put("/v1/blobs/$sha") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
    }

    suspend fun blobDownload(sha: String): ByteArray? {
        val response = http.get("/v1/blobs/$sha") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
        return if (response.status == HttpStatusCode.OK) response.body() else null
    }
}

private fun sampleFile(seed: Byte): ByteArray = ByteArray(64) { (it + seed).toByte() }

private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun payload(vararg pairs: Pair<String, String?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to (v?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)) })

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

private fun sectionRow(id: Uuid, title: String, updatedAt: Instant, deletedAt: Instant? = null) = SyncRow(
    tbl = "sections",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else payload(
        "title" to title, "orderKey" to "a", "deletable" to "1", "collapsed" to "0",
        "pinSalt" to null, "pinHash" to null,
    ),
)

private fun collectionRow(id: Uuid, sectionId: Uuid, title: String, updatedAt: Instant, deletedAt: Instant? = null) = SyncRow(
    tbl = "collections",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else payload(
        "sectionId" to sectionId.toString(), "title" to title, "orderKey" to "a",
        "createdAt" to updatedAt.toString(), "readOnly" to "0",
    ),
)

private fun cardRow(id: Uuid, collectionId: Uuid, title: String, updatedAt: Instant, deletedAt: Instant? = null) = SyncRow(
    tbl = "cards",
    id = id.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else payload(
        "collectionId" to collectionId.toString(), "cardSectionId" to null, "kind" to "link",
        "title" to title, "url" to "https://example.org", "favicon" to null, "content" to null,
        "thumb" to null, "mime" to null, "blobSha" to null, "orderKey" to "a", "createdAt" to updatedAt.toString(),
    ),
)

private fun usageRow(url: String, hits: Int, lastUsedAt: Instant, deletedAt: Instant? = null) = SyncRow(
    tbl = "usage",
    id = url,
    updatedAt = (deletedAt ?: lastUsedAt).toString(),
    deletedAt = deletedAt?.toString(),
    payload = if (deletedAt != null) null else payload(
        "url" to url, "title" to url, "host" to url, "hits" to hits.toString(), "lastUsedAt" to lastUsedAt.toString(),
    ),
)

/** One account, two devices, one server — both devices are protocol-level [FakeDevice]s, see the class doc. */
private fun twoDevices(block: suspend (FakeDevice, FakeDevice) -> Unit) = testApplication {
    val config = ServerConfig(databasePath = tempPath("server"), emailAuthEnabled = true)
    application { stramusModule(config, openServerDatabase(config)) }

    val http = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    val laptopId = Uuid.random()
    val phoneId = Uuid.random()

    val registered: TokenPair = http.post("/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("ada@example.org", "correct horse battery", laptopId.toString()))
    }.body()

    val phoneTokens: TokenPair = http.post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest("ada@example.org", "correct horse battery", phoneId.toString()))
    }.body()

    val laptop = FakeDevice(http, registered.accessToken, laptopId)
    val phone = FakeDevice(http, phoneTokens.accessToken, phoneId)

    block(laptop, phone)
}

private fun tempPath(name: String): String =
    kotlin.io.path.createTempDirectory("stramus-e2e").resolve("$name.db").toString()
