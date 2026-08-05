@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import stramus.protocol.CodeRequest
import stramus.protocol.CodeVerifyRequest
import stramus.protocol.LoginRequest
import stramus.protocol.Me
import stramus.protocol.RefreshRequest
import stramus.protocol.RegisterRequest
import stramus.protocol.TokenPair

/** The two doors in, and what happens to a session afterwards. */
class AuthTest {

    @Test
    fun `sign up with a password, then sign in with it`() = testServer { client, mailer ->
        val device = Uuid.random().toString()

        val registered = client.postJson("/v1/auth/register", RegisterRequest("Ada@Example.org", "correct horse battery", device))
        assertEquals(HttpStatusCode.Created, registered.status)

        val signedIn = client.postJson("/v1/auth/login", LoginRequest("ada@example.org", "correct horse battery", device))
        assertEquals(HttpStatusCode.OK, signedIn.status)
        val tokens: TokenPair = signedIn.body()

        // The address is the account, whatever case it was typed in.
        val me: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}") }.body()
        assertEquals("ada@example.org", me.email)
    }

    @Test
    fun `the wrong password is refused, and so is an address with no account`() = testServer { client, _ ->
        val device = Uuid.random().toString()
        client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device))

        val wrong = client.postJson("/v1/auth/login", LoginRequest("ada@example.org", "not the password", device))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val unknown = client.postJson("/v1/auth/login", LoginRequest("nobody@example.org", "not the password", device))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        // And the two say the same thing: which addresses have accounts here is not a question a
        // stranger gets to ask.
        assertEquals(wrong.bodyAsTextSafe(), unknown.bodyAsTextSafe())
    }

    @Test
    fun `an unknown address cannot be signed up twice`() = testServer { client, _ ->
        val device = Uuid.random().toString()
        client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device))
        val again = client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device))
        assertEquals(HttpStatusCode.Conflict, again.status)
    }

    @Test
    fun `a mailed code signs a new user up and in`() = testServer { client, mailer ->
        val device = Uuid.random().toString()

        val asked = client.postJson("/v1/auth/code/request", CodeRequest("grace@example.org"))
        assertEquals(HttpStatusCode.Accepted, asked.status)

        val code = mailer.lastCodeFor("grace@example.org")!!
        val verified = client.postJson("/v1/auth/code/verify", CodeVerifyRequest("grace@example.org", code, device))
        assertEquals(HttpStatusCode.OK, verified.status)

        val tokens: TokenPair = verified.body()
        val me: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}") }.body()
        assertEquals("grace@example.org", me.email)

        // Good once: the same code, typed again, is nothing.
        val reused = client.postJson("/v1/auth/code/verify", CodeVerifyRequest("grace@example.org", code, device))
        assertEquals(HttpStatusCode.Unauthorized, reused.status)
    }

    @Test
    fun `the wrong code is refused, and enough wrong guesses kill the code`() = testServer { client, mailer ->
        val device = Uuid.random().toString()
        client.postJson("/v1/auth/code/request", CodeRequest("grace@example.org"))
        val code = mailer.lastCodeFor("grace@example.org")!!

        repeat(5) {
            val wrong = client.postJson("/v1/auth/code/verify", CodeVerifyRequest("grace@example.org", "000000", device))
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        }

        // Five wrong guesses spend the code, even though the sixth attempt is the right one: six digits
        // are a million, and a caller allowed to keep guessing would get there.
        val right = client.postJson("/v1/auth/code/verify", CodeVerifyRequest("grace@example.org", code, device))
        assertEquals(HttpStatusCode.Unauthorized, right.status)
    }

    @Test
    fun `refreshing rotates the token, and the old one is dead`() = testServer { client, _ ->
        val device = Uuid.random().toString()
        val first: TokenPair =
            client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device)).body()

        val second: TokenPair = client.postJson("/v1/auth/refresh", RefreshRequest(first.refreshToken)).body()
        assertNotEquals(first.refreshToken, second.refreshToken)

        val newTokenWorks: Me =
            client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${second.accessToken}") }.body()
        assertEquals("ada@example.org", newTokenWorks.email)

        // Presenting the rotated-away token means two parties hold what should be one secret. The device
        // is signed out entirely rather than left half-trusted — including the token just issued.
        val reused = client.postJson("/v1/auth/refresh", RefreshRequest(first.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, reused.status)

        val afterTheft = client.postJson("/v1/auth/refresh", RefreshRequest(second.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, afterTheft.status, "the whole device should have been cut off")
    }

    @Test
    fun `signing out kills the session`() = testServer { client, _ ->
        val device = Uuid.random().toString()
        val tokens: TokenPair =
            client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device)).body()

        assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/auth/logout", stramus.protocol.LogoutRequest(tokens.refreshToken)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", RefreshRequest(tokens.refreshToken)).status)
    }

    @Test
    fun `a request with no token, or a made-up one, is refused`() = testServer { client, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer not.a.token") }.status,
        )
    }

    @Test
    fun `a server with the email doors shut refuses all four of them, and says why`() = testApplication {
        // The default: no STRAMUS_EMAIL_AUTH, so only Google gets anybody in.
        val config = ServerConfig(databasePath = createTempDirectory("stramus-noemail").resolve("s.db").toString())
        application { stramusModule(config, openServerDatabase(config)) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val device = Uuid.random().toString()

        val refused = listOf(
            client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device)),
            client.postJson("/v1/auth/login", LoginRequest("ada@example.org", "correct horse battery", device)),
            client.postJson("/v1/auth/code/request", CodeRequest("ada@example.org")),
            client.postJson("/v1/auth/code/verify", CodeVerifyRequest("ada@example.org", "123456", device)),
        )
        refused.forEach { assertEquals(HttpStatusCode.NotImplemented, it.status) }
        // A client that still has the form gets a sentence to show, not a bare status.
        assertTrue(refused.first().bodyAsTextSafe().contains("Google"))

        // And nothing was made on the way past: the address that was refused has no account.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
    }

    @Test
    fun `a device id already claimed by another account is refused`() = testServer { client, _ ->
        val device = Uuid.random().toString()
        client.postJson("/v1/auth/register", RegisterRequest("ada@example.org", "correct horse battery", device))

        // The client invents its own device id, so it can name someone else's — by accident or not.
        val stolen = client.postJson("/v1/auth/register", RegisterRequest("grace@example.org", "correct horse battery", device))
        assertEquals(HttpStatusCode.Unauthorized, stolen.status)
    }
}

// ---- harness ---------------------------------------------------------------------------------------

/**
 * A server of its own for each test: its own database file (SQLite's `:memory:` is shared across the
 * JVM, so tests would otherwise read each other's rows) and a mailer that keeps the codes it was asked
 * to send instead of sending them.
 */
private fun testServer(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient, RecordingMailer) -> Unit) = testApplication {
    val mailer = RecordingMailer()
    val config = ServerConfig(
        databasePath = createTempDirectory("stramus-server-test").resolve("server.db").toString(),
        // Turned on here on purpose: the doors are shut on a default server (the clients offer Google
        // alone for now), and switched-off machinery that nothing exercises is machinery that quietly
        // rots until the day somebody switches it back on.
        emailAuthEnabled = true,
    )
    application { stramusModule(config, openServerDatabase(config), mailer) }
    // The test client has to be told about JSON too, or `body<TokenPair>()` has nothing to parse with.
    val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    block(client, mailer)
}

private suspend inline fun <reified T> io.ktor.client.HttpClient.postJson(url: String, body: T) =
    post(url) {
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(body))
    }

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsTextSafe(): String = bodyAsText()
