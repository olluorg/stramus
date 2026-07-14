package stramus.protocol

import kotlinx.serialization.Serializable

/**
 * What the client and the server say to each other about *who* is asking. The synchronisation itself
 * is in `Sync.kt`; this is only how a device comes to hold a token.
 *
 * Two ways in, and they end in the same place. A password is what a returning user types without
 * thinking; a one-time code on the mail is for the user who never chose a password (and is how they
 * sign up in the first place). A code, not a link in a letter: the letter opens in a browser, and the
 * device that needs to be signed in is often the *extension*, where a six-digit code can simply be
 * typed. One mechanism serves both clients.
 *
 * The device sends its own [deviceId] — it generates one on first run and keeps it. The server hands
 * back a short-lived access token and a refresh token bound to that device, so signing out on one
 * machine leaves the others alone.
 */

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val deviceId: String,
    val deviceName: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceId: String,
    val deviceName: String? = null,
)

/** Ask for a one-time code by mail. Answered the same way whether or not the address is known. */
@Serializable
data class CodeRequest(val email: String)

/**
 * Hand back the code that was mailed. If no user has this address, this is where the account is made:
 * proving you read the mail at that address is the whole of what a password would have proved.
 */
@Serializable
data class CodeVerifyRequest(
    val email: String,
    val code: String,
    val deviceId: String,
    val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

/**
 * [accessToken] is short-lived and carries the user and device in its claims; it is what every other
 * request sends. [refreshToken] is long-lived, lives in the database as a hash, and is exchanged for a
 * new pair — the old one dying as it does. Presenting a refresh token twice is taken as a stolen one:
 * see the server's `Tokens.kt`.
 */
@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    /** Seconds until [accessToken] expires, so the client can refresh before a request fails. */
    val expiresIn: Long,
)

/** Who the bearer of an access token is. */
@Serializable
data class Me(val userId: String, val email: String)

/** What every failure says, so the client never has to guess from a status code alone. */
@Serializable
data class ApiError(val error: String, val message: String)
