@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.KormiumException
import io.github.kormium.database.SuspendDatabase
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.protocol.ApiError
import stramus.protocol.CodeRequest
import stramus.protocol.CodeVerifyRequest
import stramus.protocol.LoginRequest
import stramus.protocol.LogoutRequest
import stramus.protocol.Me
import stramus.protocol.RefreshRequest
import stramus.protocol.AccountExport
import stramus.protocol.RegisterRequest
import stramus.protocol.SyncRequest

/** The name of the JWT authentication provider — `authenticate(BEARER)` guards a route with it. */
const val BEARER = "bearer"

/**
 * Wires the server up. Everything it needs is passed in rather than made here, so a test can hand it a
 * database on a temporary file and a mailer that keeps what it sent ([RecordingMailer]) instead of
 * standing up a real one.
 */
fun Application.stramusModule(
    config: ServerConfig,
    db: SuspendDatabase<ServerDb>,
    mailer: Mailer = LoggingMailer(),
) {
    val accessTokens = AccessTokens(config)
    val sessions = Sessions(db, config, accessTokens)
    val accounts = Accounts(db, config, sessions, mailer)
    val sync = SyncService(db)

    // Ktor's own DI, keyed by the full type: this is what `call.transaction<ServerDb, _> { }` resolves
    // the database out of, so a route never has to be handed one.
    dependencies {
        provide<SuspendDatabase<ServerDb>> { db }
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(CORS) {
        // The web app and the extension are both cross-origin — one on a static host, one on
        // `chrome-extension://…` — so a browser will not send a request this does not name. There is no
        // wildcard here on purpose: the tokens are Bearer, and any origin allowed to send them is an
        // origin allowed to act as the user.
        config.allowedOrigins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://"))) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }

    install(Authentication) {
        jwt(BEARER) {
            realm = JWT_ISSUER
            verifier(accessTokens.verifier)
            validate { credential ->
                // A token is only good if it still names a user: an account deleted a minute ago must
                // not keep working for the fifteen its access token has left.
                val userId = credential.payload.subject?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                if (userId != null && accounts.me(userId) != null) JWTPrincipal(credential.payload) else null
            }
        }
    }

    install(StatusPages) {
        exception<AccountException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), ApiError("account", e.message ?: "bad request"))
        }
        exception<AuthException> { call, e ->
            call.respond(HttpStatusCode.Unauthorized, ApiError("auth", e.message ?: "unauthorized"))
        }
        exception<KormiumException> { call, e ->
            call.respond(HttpStatusCode.InternalServerError, ApiError("database", e.message ?: "database error"))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/v1/auth") {
            post("/register") {
                val body = call.receive<RegisterRequest>()
                call.respond(
                    HttpStatusCode.Created,
                    accounts.register(body.email, body.password, body.deviceId.asDeviceId(), body.deviceName),
                )
            }

            post("/login") {
                val body = call.receive<LoginRequest>()
                call.respond(accounts.login(body.email, body.password, body.deviceId.asDeviceId(), body.deviceName))
            }

            post("/code/request") {
                val body = call.receive<CodeRequest>()
                accounts.requestCode(body.email)
                // The same answer whether or not the address has an account, and whether or not a code
                // was actually sent: anything else is a way to ask who has an account here.
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "sent"))
            }

            post("/code/verify") {
                val body = call.receive<CodeVerifyRequest>()
                call.respond(
                    accounts.verifyCode(body.email, body.code, body.deviceId.asDeviceId(), body.deviceName),
                )
            }

            post("/refresh") {
                val body = call.receive<RefreshRequest>()
                call.respond(sessions.refresh(body.refreshToken))
            }

            post("/logout") {
                val body = call.receive<LogoutRequest>()
                sessions.logout(body.refreshToken)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        authenticate(BEARER) {
            get("/v1/me") {
                val userId = call.userId()
                val user = accounts.me(userId) ?: throw AuthException("no such user")
                call.respond(Me(user.id.toString(), user.email))
            }

            post("/v1/sync") {
                val body = call.receive<SyncRequest>()
                // The device is taken from the token, not from the body: a signed-in caller does not get
                // to write rows as one of the user's *other* devices, which is what the tie-break in a
                // conflict is decided by.
                call.respond(sync.sync(call.userId(), call.deviceId(), body.since, body.rows))
            }

            /**
             * Everything the server holds about the caller, in the form it holds it. The right to have a
             * copy of your data, and to take it elsewhere — and the honest test of whether we know what
             * we are storing.
             */
            get("/v1/account/export") {
                val userId = call.userId()
                val user = accounts.me(userId) ?: throw AuthException("no such user")
                call.respond(
                    AccountExport(
                        email = user.email,
                        createdAt = user.createdAt.toString(),
                        rows = sync.exportAll(userId),
                    ),
                )
            }

            /**
             * The right to be forgotten, meant literally: the rows go, the devices go, the sessions go,
             * the account goes. Not a flag — nothing is left behind that says this person was ever here.
             */
            delete("/v1/account") {
                accounts.delete(call.userId())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** The user behind the access token on this call. Only valid inside `authenticate(BEARER)`. */
fun io.ktor.server.application.ApplicationCall.userId(): Uuid {
    val principal = principal<JWTPrincipal>() ?: throw AuthException("not signed in")
    val subject = principal.payload.subject ?: throw AuthException("token names no user")
    return runCatching { Uuid.parse(subject) }.getOrElse { throw AuthException("token names no user") }
}

/** The device behind the access token on this call. */
fun io.ktor.server.application.ApplicationCall.deviceId(): Uuid {
    val principal = principal<JWTPrincipal>() ?: throw AuthException("not signed in")
    val claim = principal.payload.getClaim(CLAIM_DEVICE).asString() ?: throw AuthException("token names no device")
    return runCatching { Uuid.parse(claim) }.getOrElse { throw AuthException("token names no device") }
}

/** The client makes its own device id and keeps it. Anything that is not a UUID is a client bug. */
private fun String.asDeviceId(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw AccountException(400, "deviceId must be a UUID") }
