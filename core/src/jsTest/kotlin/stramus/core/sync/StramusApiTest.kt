package stramus.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The api's own half: what it does about a token that has run out in the middle of a sync.
 *
 * An access token lives fifteen minutes and a sync runs every minute, so most calls hand the server one
 * that is still good — which is exactly why the branch that deals with the other case is the one nobody
 * ever exercises. It is also the branch that decides whether a device that has been asleep for an hour
 * comes back or quietly stops syncing until somebody notices.
 *
 * Scripted rather than timed: waiting fifteen minutes for a real token to expire is not a test, and a
 * server configured to expire them in a second is a race with the machine it runs on.
 */
class StramusApiTest {

    @Test
    fun `a call that comes back unauthorised is retried once, with a fresh token`() = runTest {
        installLocalStorageFor("refresh-once")
        val seen = mutableListOf<String>()
        var syncCalls = 0

        val api = StramusApi(BASE, scripted { path, body ->
            seen += path
            when {
                path.endsWith("/v1/auth/register") -> ok(TOKENS)
                path.endsWith("/v1/me") -> ok(ME)
                path.endsWith("/v1/auth/refresh") -> ok("""{"accessToken":"fresh","refreshToken":"r2","expiresIn":900}""")
                path.endsWith("/v1/sync") -> {
                    syncCalls++
                    // The first attempt is turned away; the second, with the refreshed token, is not.
                    if (syncCalls == 1) unauthorized() else ok(EMPTY_DELTA)
                }
                else -> ok("{}")
            }
        })

        api.register("ada@example.org", "correct horse battery")
        val response = api.sync(stramus.protocol.SyncRequest("device", 0))

        assertEquals(0L, response.rev)
        assertEquals(2, syncCalls, "asked again rather than giving up")
        assertTrue(seen.any { it.endsWith("/v1/auth/refresh") }, "and refreshed in between")
    }

    @Test
    fun `a refresh the server refuses signs the browser out rather than retrying for ever`() = runTest {
        installLocalStorageFor("refresh-refused")
        var refreshes = 0

        val api = StramusApi(BASE, scripted { path, _ ->
            when {
                path.endsWith("/v1/auth/register") -> ok(TOKENS)
                path.endsWith("/v1/me") -> ok(ME)
                path.endsWith("/v1/auth/refresh") -> { refreshes++; unauthorized() }
                else -> unauthorized()
            }
        })

        api.register("ada@example.org", "correct horse battery")
        val failed = runCatching { api.sync(stramus.protocol.SyncRequest("device", 0)) }

        assertTrue(failed.isFailure, "the caller is told, rather than left believing it synced")
        assertEquals(1, refreshes, "asked once — a device whose session is gone does not hammer the door")
        assertTrue(!api.hasSession(), "and the session it could not renew is not kept around pretending")
    }

    @Test
    fun `a session that was never there is not a crash`() = runTest {
        installLocalStorageFor("no-session")
        val api = StramusApi(BASE, scripted { _, _ -> ok(EMPTY_DELTA) })

        assertTrue(!api.hasSession())
        assertTrue(runCatching { api.sync(stramus.protocol.SyncRequest("device", 0)) }.isFailure)
    }

    @Test
    fun `the health knock answers rather than throwing when nobody is home`() = runTest {
        installLocalStorageFor("health")
        val api = StramusApi(BASE, scripted { _, _ -> error("the server is not there") })

        // The one call that never throws: a dead gateway is the answer being asked for.
        assertEquals(false, api.health())
    }
}

private const val BASE = "https://api.example.org"
private const val TOKENS = """{"accessToken":"a1","refreshToken":"r1","expiresIn":900}"""
private const val EMPTY_DELTA = """{"rev":0}"""
private const val ME = """{"userId":"11111111-1111-1111-1111-111111111111","email":"ada@example.org"}"""

private fun ok(body: String) = ResponseSpec(HttpStatusCode.OK, body)
private fun unauthorized() = ResponseSpec(HttpStatusCode.Unauthorized, """{"error":"auth","message":"no"}""")

private class ResponseSpec(val status: HttpStatusCode, val body: String)

/** An HTTP client that answers from a script — the path in, the response out. */
private fun scripted(answer: (path: String, body: String) -> ResponseSpec): HttpClient =
    HttpClient(
        MockEngine { request ->
            val spec = answer(request.url.encodedPath, "")
            respond(
                content = spec.body,
                status = spec.status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

/**
 * A `localStorage` per test, since Node has none and the api keeps the device id and the refresh token
 * there. Named, so one test's session cannot be another's.
 */
private fun installLocalStorageFor(name: String) {
    js(
        """
        (function (ns) {
            var store = {};
            globalThis.localStorage = {
                _ns: ns,
                getItem: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
                setItem: function (k, v) { store[k] = String(v); },
                removeItem: function (k) { delete store[k]; },
                clear: function () { store = {}; }
            };
        })(name);
        """,
    )
}
