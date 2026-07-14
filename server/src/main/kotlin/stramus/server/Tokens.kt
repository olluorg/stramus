@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.github.kormium.SuspendScope
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.and
import io.github.kormium.isNull
import io.github.kormium.suspendTransaction
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.protocol.TokenPair

const val JWT_ISSUER = "stramus"
const val CLAIM_DEVICE = "did"

/** Refused sign-in, refused refresh — anything the caller is not allowed to do. Answered as 401. */
class AuthException(message: String) : RuntimeException(message)

/** What a refresh turned out to be, decided inside the transaction and answered outside it. */
private sealed interface RefreshOutcome {
    data class Rotated(val pair: TokenPair) : RefreshOutcome
    data object Unknown : RefreshOutcome
    data object Expired : RefreshOutcome
    data object Reused : RefreshOutcome
}

/** Issues and checks the short-lived access tokens. */
class AccessTokens(private val config: ServerConfig) {
    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(JWT_ISSUER).build()

    /** A token for [userId] on [deviceId]. The device is in the claims so a request knows which one. */
    fun issue(userId: Uuid, deviceId: Uuid, now: Instant = Clock.System.now()): String =
        JWT.create()
            .withIssuer(JWT_ISSUER)
            .withSubject(userId.toString())
            .withClaim(CLAIM_DEVICE, deviceId.toString())
            .withIssuedAt(Date(now.toEpochMilliseconds()))
            .withExpiresAt(Date((now + config.accessTokenTtl).toEpochMilliseconds()))
            .sign(algorithm)
}

/**
 * The session store: refresh tokens, their rotation, and what to make of one that comes back twice.
 *
 * **Rotation.** Every refresh issues a new token and revokes the one presented, recording the new one as
 * its replacement. A device therefore holds exactly one live token at a time, and the ones behind it
 * form a chain.
 *
 * **Reuse detection.** A revoked token presented again means two parties hold what should have been one
 * secret: the device that rotated it, and someone who copied it. There is no way to tell which of them
 * is calling, so the only safe answer is to trust neither — every token of that device is revoked, and
 * the person signs in again. The alternative (refuse this call, leave the rest alive) leaves the thief
 * with a working session whenever they are the one who refreshes first.
 */
class Sessions(
    private val db: SuspendDatabase<ServerDb>,
    private val config: ServerConfig,
    private val accessTokens: AccessTokens,
) {

    /** Signs [userId] in on [deviceId]: a device row, a fresh pair, nothing revoked. */
    suspend fun start(userId: Uuid, deviceId: Uuid, deviceName: String?): TokenPair {
        val now = Clock.System.now()
        return db.suspendTransaction {
            touchDevice(userId, deviceId, deviceName, now)
            issuePair(userId, deviceId, now)
        }
    }

    /** Exchanges a refresh token for a new pair, killing the one presented. */
    suspend fun refresh(refreshToken: String): TokenPair {
        val now = Clock.System.now()
        val hash = hashToken(refreshToken)

        // The transaction decides and *commits*; the refusal is thrown afterwards. Throwing from inside
        // would roll the transaction back — and with it the revocation that a reused token triggers,
        // which is the one thing that must survive this call. The thief would keep their session.
        val outcome: RefreshOutcome = db.suspendTransaction {
            val row = RefreshTokens.findOne { where { RefreshTokens.tokenHash eq hash } }
                ?: return@suspendTransaction RefreshOutcome.Unknown

            if (row.revokedAt != null) {
                // Presented twice. See the class comment: cut the device off entirely rather than leave
                // a thief holding a session that still works.
                revokeDevice(row.deviceId, now)
                return@suspendTransaction RefreshOutcome.Reused
            }
            if (row.expiresAt <= now) return@suspendTransaction RefreshOutcome.Expired

            val pair = issuePair(row.userId, row.deviceId, now)
            val issued = RefreshTokens.findOne { where { RefreshTokens.tokenHash eq hashToken(pair.refreshToken) } }
            RefreshTokens.update(
                RefreshTokenRow().apply {
                    revokedAt = now
                    replacedBy = issued?.id
                },
            ) { where { RefreshTokens.id eq row.id } }

            touchDevice(row.userId, row.deviceId, name = null, now = now)
            RefreshOutcome.Rotated(pair)
        }

        return when (outcome) {
            is RefreshOutcome.Rotated -> outcome.pair
            RefreshOutcome.Unknown -> throw AuthException("unknown refresh token")
            RefreshOutcome.Expired -> throw AuthException("refresh token expired")
            RefreshOutcome.Reused -> throw AuthException("refresh token reused — the device has been signed out")
        }
    }

    /** Signs one device out. Nothing else of the user's is touched. */
    suspend fun logout(refreshToken: String) {
        val now = Clock.System.now()
        val hash = hashToken(refreshToken)
        db.suspendTransaction {
            val row = RefreshTokens.findOne { where { RefreshTokens.tokenHash eq hash } } ?: return@suspendTransaction
            revokeDevice(row.deviceId, now)
        }
    }

    private suspend fun SuspendScope<ServerDb>.issuePair(userId: Uuid, deviceId: Uuid, now: Instant): TokenPair {
        val refresh = newSecret()
        RefreshTokens.insert(
            RefreshTokenRow().apply {
                id = Uuid.random()
                this.userId = userId
                this.deviceId = deviceId
                tokenHash = hashToken(refresh)
                issuedAt = now
                expiresAt = now + config.refreshTokenTtl
                revokedAt = null
                replacedBy = null
            },
        )
        return TokenPair(
            accessToken = accessTokens.issue(userId, deviceId, now),
            refreshToken = refresh,
            expiresIn = config.accessTokenTtl.inWholeSeconds,
        )
    }

    private suspend fun SuspendScope<ServerDb>.revokeDevice(deviceId: Uuid, now: Instant) {
        RefreshTokens.update(RefreshTokenRow().apply { revokedAt = now }) {
            where { (RefreshTokens.deviceId eq deviceId) and RefreshTokens.revokedAt.isNull() }
        }
    }

    private suspend fun SuspendScope<ServerDb>.touchDevice(
        userId: Uuid,
        deviceId: Uuid,
        name: String?,
        now: Instant,
    ) {
        val existing = Devices.findOne { where { Devices.id eq deviceId } }
        if (existing == null) {
            Devices.insert(
                DeviceRow().apply {
                    id = deviceId
                    this.userId = userId
                    this.name = name
                    lastSeenAt = now
                },
            )
            return
        }
        // A device id is the client's own invention, so it can name someone else's device — by accident
        // or on purpose. It belongs to the account that first claimed it, and a second account claiming
        // it does not get to move it, or to learn that it exists.
        if (existing.userId != userId) throw AuthException("device belongs to another account")

        Devices.update(
            DeviceRow().apply {
                lastSeenAt = now
                if (name != null) this.name = name
            },
        ) { where { Devices.id eq deviceId } }
    }
}
