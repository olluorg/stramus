@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import stramus.protocol.GoogleSignInRequest
import stramus.protocol.Me
import stramus.protocol.RegisterRequest
import stramus.protocol.TokenPair

/**
 * The Google door.
 *
 * Google's signature is checked by [GoogleIdTokenVerifier], and that is a piece of arithmetic; what is
 * checked here is everything the signature does *not* settle — whose account a verified Google identity
 * lands in, and what happens when it names an address that already belongs to somebody.
 */
class GoogleAuthTest {

    @Test
    fun `a new Google user gets an account`() = googleServer { client, _ ->
        val tokens: TokenPair = client.signInWithGoogle("ada-at-google", "ada@example.org")
        val me: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}") }.body()
        assertEquals("ada@example.org", me.email)
    }

    @Test
    fun `signing in twice is the same account, not two`() = googleServer { client, _ ->
        val first: TokenPair = client.signInWithGoogle("ada-at-google", "ada@example.org")
        val second: TokenPair = client.signInWithGoogle("ada-at-google", "ada@example.org")

        val me1: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${first.accessToken}") }.body()
        val me2: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${second.accessToken}") }.body()
        assertEquals(me1.userId, me2.userId)
    }

    @Test
    fun `Google signs into the account the address already has, rather than making a second one`() =
        googleServer { client, _ ->
            // Ada signed up with a password months ago. Today she clicks "sign in with Google". It is the
            // same person and the same address, and she must land in the account with her collections in it
            // — not in a fresh, empty one that happens to share her email.
            val withPassword: TokenPair = client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("ada@example.org", "correct horse battery", Uuid.random().toString()))
            }.body()
            val before: Me =
                client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${withPassword.accessToken}") }.body()

            val viaGoogle: TokenPair = client.signInWithGoogle("ada-at-google", "ada@example.org")
            val after: Me =
                client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${viaGoogle.accessToken}") }.body()

            assertEquals(before.userId, after.userId, "the two doors should open the same house")
        }

    @Test
    fun `an unverified Google address cannot walk into an existing account`() = googleServer { client, _ ->
        client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("ada@example.org", "correct horse battery", Uuid.random().toString()))
        }

        // Google will issue a token for an address its owner never proved they can read. If that were enough
        // to sign in, anyone able to get such a token for Ada's address would be handed Ada's account — with
        // her collections, her notes, her files. It is not enough.
        val refused = client.post("/v1/auth/oauth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest("someone-else", Uuid.random().toString()))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
    }

    @Test
    fun `a token this server does not believe is refused`() = googleServer { client, _ ->
        val refused = client.post("/v1/auth/oauth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest("not-a-token-we-issued", Uuid.random().toString()))
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
    }

    @Test
    fun `two different Google users are two different accounts`() = googleServer { client, _ ->
        val ada: TokenPair = client.signInWithGoogle("ada-at-google", "ada@example.org")
        val grace: TokenPair = client.signInWithGoogle("grace-at-google", "grace@example.org")

        val one: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${ada.accessToken}") }.body()
        val two: Me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${grace.accessToken}") }.body()
        assertNotEquals(one.userId, two.userId)
    }

    @Test
    fun `a server with no Google client id says so, rather than letting anything through`() = testApplication {
        val config = ServerConfig(databasePath = createTempDirectory("stramus-nogoogle").resolve("s.db").toString())
        application { stramusModule(config, openServerDatabase(config)) } // no verifier: the door is not there
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/v1/auth/oauth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest("anything at all", Uuid.random().toString()))
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
    }
}

// ---- harness ---------------------------------------------------------------------------------------

/**
 * Google, as far as this test is concerned: a table of tokens to identities.
 *
 * The real verifier goes to Google for its public keys, so a test that used it would be a test that needs
 * the internet — and would still not exercise the part that can go wrong, which is what we do with an
 * identity once we have one. `"someone-else"` is the token of a Google account whose address is *not*
 * verified, and it is the reason this fake exists.
 */
private val fakeGoogle = GoogleVerifier { token ->
    when (token) {
        "ada-at-google" -> GoogleIdentity("google-sub-ada", "ada@example.org", emailVerified = true)
        "grace-at-google" -> GoogleIdentity("google-sub-grace", "grace@example.org", emailVerified = true)
        "someone-else" -> GoogleIdentity("google-sub-impostor", "ada@example.org", emailVerified = false)
        else -> null
    }
}

private fun googleServer(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient, RecordingMailer) -> Unit) =
    testApplication {
        val mailer = RecordingMailer()
        val config = ServerConfig(
            databasePath = createTempDirectory("stramus-google").resolve("server.db").toString(),
        )
        application { stramusModule(config, openServerDatabase(config), mailer, fakeGoogle) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        block(client, mailer)
    }

private suspend fun io.ktor.client.HttpClient.signInWithGoogle(idToken: String, email: String): TokenPair =
    post("/v1/auth/oauth/google") {
        contentType(ContentType.Application.Json)
        setBody(GoogleSignInRequest(idToken, Uuid.random().toString(), deviceName = email))
    }.body()
