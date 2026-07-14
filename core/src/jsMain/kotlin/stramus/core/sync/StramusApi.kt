@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import stramus.protocol.ApiError
import stramus.protocol.CodeRequest
import stramus.protocol.CodeVerifyRequest
import stramus.protocol.LoginRequest
import stramus.protocol.LogoutRequest
import stramus.protocol.Me
import stramus.protocol.RefreshRequest
import stramus.protocol.RegisterRequest
import stramus.protocol.SyncRequest
import stramus.protocol.SyncResponse
import stramus.protocol.TokenPair

/** What the server said no to, in words the UI can put on the screen. */
class ApiException(val status: Int, override val message: String) : RuntimeException(message)

/**
 * The browser's side of the conversation with the server: signing in, and then [sync].
 *
 * ## Where the tokens live
 *
 * The access token stays in memory. The refresh token is written to `localStorage`, because it has to
 * survive the tab being closed — a user signed in yesterday should not have to sign in again today.
 *
 * `localStorage` is readable by any script that runs on this origin, which is to say: a cross-site
 * scripting hole in the app is a stolen session. That is a real cost, and it is paid because the
 * alternatives are worse here — the web app and the extension are different origins from the API, so a
 * cookie would have to be `SameSite=None`, and the extension cannot use one at all. What follows from it
 * is that the token is worth nothing beyond a session: it names a device, it can be revoked, it rotates
 * on every use, and a stolen one announces itself the moment the real device refreshes (see the server's
 * `Tokens.kt`).
 *
 * ## Refreshing
 *
 * An access token lives fifteen minutes and a sync runs every minute, so most of the time this hands the
 * server one that is still good. When it does not, the call comes back 401, and [withToken] refreshes and
 * tries once more. The caller — the sync engine — never sees it, and never has to think about tokens at
 * all.
 */
class StramusApi(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient(io.ktor.client.engine.js.Js) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    },
) : SyncApi {

    private var accessToken: String? = null

    /** The device this browser is, made once and kept. Not a secret; it names a session, not a person. */
    val deviceId: Uuid
        get() {
            localStorage.getItem(DEVICE_KEY)?.let { return Uuid.parse(it) }
            return Uuid.random().also { localStorage.setItem(DEVICE_KEY, it.toString()) }
        }

    private var refreshToken: String?
        get() = localStorage.getItem(REFRESH_KEY)
        set(value) {
            if (value == null) localStorage.removeItem(REFRESH_KEY) else localStorage.setItem(REFRESH_KEY, value)
        }

    /** Whether this browser has a session to resume. It may still turn out to be expired. */
    fun hasSession(): Boolean = refreshToken != null

    suspend fun register(email: String, password: String): Me {
        val tokens: TokenPair = post("/v1/auth/register", RegisterRequest(email, password, deviceId.toString()))
        return keep(tokens)
    }

    suspend fun login(email: String, password: String): Me {
        val tokens: TokenPair = post("/v1/auth/login", LoginRequest(email, password, deviceId.toString()))
        return keep(tokens)
    }

    /** Ask for a code on the mail. Answers the same way whether or not the address has an account. */
    suspend fun requestCode(email: String) {
        post<CodeRequest, Unit>("/v1/auth/code/request", CodeRequest(email))
    }

    suspend fun verifyCode(email: String, code: String): Me {
        val tokens: TokenPair = post("/v1/auth/code/verify", CodeVerifyRequest(email, code, deviceId.toString()))
        return keep(tokens)
    }

    /** Pick a session back up on a page that has just loaded. Null if there is none, or it has expired. */
    suspend fun resume(): Me? {
        val token = refreshToken ?: return null
        val tokens: TokenPair = runCatching { post<RefreshRequest, TokenPair>("/v1/auth/refresh", RefreshRequest(token)) }
            .getOrElse {
                // Expired, revoked, or the device was cut off because someone else used this token. Either
                // way there is nothing to resume, and the user signs in again.
                signOutLocally()
                return null
            }
        return keep(tokens)
    }

    suspend fun signOut() {
        refreshToken?.let { runCatching { post<LogoutRequest, Unit>("/v1/auth/logout", LogoutRequest(it)) } }
        signOutLocally()
    }

    /** Erase the account on the server. The local database is not touched — that is the app's to decide. */
    suspend fun deleteAccount() {
        withToken { token ->
            http.delete("$baseUrl/v1/account") { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        signOutLocally()
    }

    override suspend fun sync(request: SyncRequest): SyncResponse = withToken { token ->
        http.post("$baseUrl/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.body()

    private fun signOutLocally() {
        accessToken = null
        refreshToken = null
    }

    private suspend fun keep(tokens: TokenPair): Me {
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        return withToken { token ->
            http.get("$baseUrl/v1/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        }.body()
    }

    /**
     * Runs [call] with an access token, refreshing once if the server says the token is stale. A failure
     * to refresh is a failure to be signed in, and it is thrown: the engine will try again on its next
     * run, and the UI will show that the account needs signing in to.
     */
    private suspend fun withToken(call: suspend (String) -> HttpResponse): HttpResponse {
        val token = accessToken ?: refreshAccess() ?: throw ApiException(401, "not signed in")
        val response = call(token)
        if (response.status != HttpStatusCode.Unauthorized) return response.orThrow()

        val fresh = refreshAccess() ?: throw ApiException(401, "the session has expired")
        return call(fresh).orThrow()
    }

    private suspend fun refreshAccess(): String? {
        val token = refreshToken ?: return null
        val tokens = runCatching { post<RefreshRequest, TokenPair>("/v1/auth/refresh", RefreshRequest(token)) }
            .getOrElse { signOutLocally(); return null }
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        return tokens.accessToken
    }

    private suspend inline fun <reified B, reified R> post(path: String, body: B): R {
        val response = http.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.orThrow()
        return if (R::class == Unit::class) Unit as R else response.body()
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        // The server says why in an [ApiError]; anything else (a proxy, a dead gateway) says only its
        // status, and the user is better served by that than by "something went wrong".
        val message = runCatching { body<ApiError>().message }.getOrElse { bodyAsText().ifBlank { status.description } }
        throw ApiException(status.value, message)
    }

    private companion object {
        const val DEVICE_KEY = "stramus.deviceId"
        const val REFRESH_KEY = "stramus.refreshToken"
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
