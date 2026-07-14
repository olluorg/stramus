@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.gt
import io.github.kormium.isNull
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.protocol.TokenPair

/** Wrong password, wrong code, address already taken — anything the caller did wrong. Answered as 4xx. */
class AccountException(val status: Int, message: String) : RuntimeException(message)

/** How many wrong guesses a mailed code survives before it is dead. Six digits is a million; five is not. */
private const val MAX_CODE_ATTEMPTS = 5

/** How many codes one address may have live at once — a brake on using this server as a mail cannon. */
private const val MAX_LIVE_CODES_PER_EMAIL = 3

private val random = SecureRandom()

/**
 * Signing up, signing in, and the one-time codes on the mail.
 *
 * The two doors — password and mailed code — end in the same [Sessions.start], and the difference
 * between them is only what was proved: that the caller knows a secret, or that they read the mail at
 * that address. Neither is privileged, and an account may have one, the other, or (once it sets a
 * password) both.
 */
class Accounts(
    private val db: SuspendDatabase<ServerDb>,
    private val config: ServerConfig,
    private val sessions: Sessions,
    private val mailer: Mailer,
) {

    suspend fun register(email: String, password: String, deviceId: Uuid, deviceName: String?): TokenPair {
        val address = normalizeEmail(email)
        requirePassword(password)

        val userId = db.suspendTransaction {
            if (Users.findOne { where { Users.email eq address } } != null) {
                // The address is taken, and saying so tells a stranger who has an account here. That is
                // a real cost, and it is paid anyway: a sign-up form that refuses to say why it failed
                // is a sign-up form people give up on. The mailed-code door leaks nothing, and anyone
                // who cares can use it.
                throw AccountException(409, "that address already has an account")
            }
            val row = UserRow().apply {
                id = Uuid.random()
                this.email = address
                passwordHash = hashPassword(password)
                createdAt = Clock.System.now()
            }
            Users.insert(row)
            row.id
        }
        return sessions.start(userId, deviceId, deviceName)
    }

    suspend fun login(email: String, password: String, deviceId: Uuid, deviceName: String?): TokenPair {
        val address = normalizeEmail(email)
        val user = db.suspendAutocommit { Users.findOne { where { Users.email eq address } } }

        // The same answer whether the address is unknown or the password is wrong: the two must not be
        // distinguishable, or this is an oracle for which addresses have accounts. The hash is still
        // computed for an unknown address, so the two paths take the same time as well as saying the
        // same thing.
        val stored = user?.passwordHash ?: DUMMY_HASH
        val ok = verifyPassword(password, stored)
        if (user == null || user.passwordHash == null || !ok) throw AuthException("wrong address or password")

        return sessions.start(user.id, deviceId, deviceName)
    }

    /**
     * Mail a code to [email], if it is worth mailing one. Says nothing back either way: whether an
     * address has an account is not something a stranger gets to ask.
     */
    suspend fun requestCode(email: String) {
        val address = normalizeEmail(email)
        val now = Clock.System.now()

        val live = db.suspendAutocommit {
            LoginCodes.count {
                where {
                    (LoginCodes.email eq address) and LoginCodes.usedAt.isNull() and (LoginCodes.expiresAt gt now)
                }
            }
        }
        // Not an error the caller is told about: a brake, quietly applied. Telling them would let this
        // be used to find out whether an address is being hammered.
        if (live >= MAX_LIVE_CODES_PER_EMAIL) return

        val code = (1..6).map { random.nextInt(10) }.joinToString("")
        db.suspendTransaction {
            LoginCodes.insert(
                LoginCodeRow().apply {
                    id = Uuid.random()
                    this.email = address
                    codeHash = hashToken(code)
                    issuedAt = now
                    expiresAt = now + config.loginCodeTtl
                    usedAt = null
                    attempts = 0
                },
            )
        }
        mailer.sendLoginCode(address, code)
    }

    /**
     * Take the code the user typed. This is also where an account is born: proving you read the mail at
     * an address is the whole of what a password would have proved, so there is nothing left to sign up
     * for afterwards.
     */
    suspend fun verifyCode(email: String, code: String, deviceId: Uuid, deviceName: String?): TokenPair {
        val address = normalizeEmail(email)
        val now = Clock.System.now()

        // The transaction *decides*; it does not refuse. Throwing from inside it would roll it back —
        // and roll back with it the count of wrong guesses this call just made, leaving a six-digit code
        // that can be guessed for as long as it lives. So the verdict comes out, the transaction
        // commits, and only then is the caller told no.
        val userId: Uuid = db.suspendTransaction<ServerDb, Uuid?> {
            val candidates = LoginCodes.find {
                where {
                    (LoginCodes.email eq address) and LoginCodes.usedAt.isNull() and (LoginCodes.expiresAt gt now)
                }
                orderBy DESC LoginCodes.issuedAt
            }

            val matched = candidates.firstOrNull { it.codeHash == hashToken(code) && it.attempts < MAX_CODE_ATTEMPTS }
            if (matched == null) {
                // A wrong guess costs every live code of this address one of its lives, not just the one
                // the caller was aiming at — otherwise asking for three codes would buy fifteen guesses.
                candidates.forEach { row ->
                    val attempts = row.attempts + 1
                    LoginCodes.update(
                        LoginCodeRow().apply {
                            this.attempts = attempts
                            if (attempts >= MAX_CODE_ATTEMPTS) usedAt = now // spent, though never used
                        },
                    ) { where { LoginCodes.id eq row.id } }
                }
                null
            } else {
                LoginCodes.update(LoginCodeRow().apply { usedAt = now }) { where { LoginCodes.id eq matched.id } }

                val existing = Users.findOne { where { Users.email eq address } }
                existing?.id ?: UserRow().apply {
                    id = Uuid.random()
                    this.email = address
                    passwordHash = null // signed up by mail; there is no password, and none is missing
                    createdAt = now
                }.also { Users.insert(it) }.id
            }
        } ?: throw AccountException(401, "wrong or expired code")

        return sessions.start(userId, deviceId, deviceName)
    }

    suspend fun me(userId: Uuid): UserRow? = db.suspendAutocommit { Users.findOne { where { Users.id eq userId } } }

    /**
     * Erase the account: the synced rows, the devices, the sessions, the sign-in codes, the user.
     *
     * Every table is named here on purpose rather than left to a cascade the schema does not declare —
     * "forget me" that leaves a row behind somewhere is not forgetting, and the way that happens is a
     * table added later that nobody remembered to add to this list. It runs as one transaction, so a
     * half-deleted account is not a state this can end in.
     *
     * The access tokens already handed out cannot be recalled — they are signed, not stored — but they
     * die with the user: `validate` refuses a token whose account is gone, on every request.
     */
    suspend fun delete(userId: Uuid) {
        db.suspendTransaction {
            SyncRows.deleteWhere { where { SyncRows.userId eq userId } }
            UserSeq.deleteWhere { where { UserSeq.userId eq userId } }
            RefreshTokens.deleteWhere { where { RefreshTokens.userId eq userId } }
            Devices.deleteWhere { where { Devices.userId eq userId } }
            Identities.deleteWhere { where { Identities.userId eq userId } }

            val user = Users.findOne { where { Users.id eq userId } }
            if (user != null) {
                // The codes are keyed by address, not by user: a code mailed to an address that then
                // deleted its account must not sign the next owner of that address in.
                LoginCodes.deleteWhere { where { LoginCodes.email eq user.email } }
                Users.deleteWhere { where { Users.id eq userId } }
            }
        }
    }
}

/**
 * The hash a sign-in for an unknown address is checked against, so that the unknown-address path costs
 * the same Argon2 work as the wrong-password path. Without it, "no such user" answers in a millisecond
 * and "wrong password" in eighty, and the difference tells a stranger which addresses have accounts.
 */
private val DUMMY_HASH: String by lazy { hashPassword("this password belongs to nobody") }

/** Addresses are compared lowercased and trimmed: `Ada@Example.org` is the account `ada@example.org`. */
fun normalizeEmail(email: String): String {
    val address = email.trim().lowercase()
    // Not a real address parser, and not trying to be: the mail either arrives or it does not, and that
    // is the only test that means anything. This rejects what is obviously not an address at all.
    if (address.length < 3 || !address.contains('@') || address.startsWith('@') || address.endsWith('@')) {
        throw AccountException(400, "that does not look like an email address")
    }
    return address
}

private fun requirePassword(password: String) {
    // Length, and nothing else. Rules about punctuation and digits push people towards `Passw0rd!` and
    // buy nothing; length is what actually costs a guesser.
    if (password.length < 10) throw AccountException(400, "the password must be at least 10 characters")
    if (password.length > 200) throw AccountException(400, "the password is absurdly long")
}
