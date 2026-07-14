@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import kotlin.uuid.ExperimentalUuidApi

/** Compile-time database identity for the stramus server. Distinct from the client's `StramusDb`. */
object ServerDb : Catalog

class UserRow : Entity() {
    var id by Users.id
    var email by Users.email
    var passwordHash by Users.passwordHash
    var createdAt by Users.createdAt
}

/**
 * An account.
 *
 * [passwordHash] is nullable, and that is not an unfinished field: a user who signed up with a one-time
 * code on their mail has no password, and never needs one. Signing in by code and signing in by password
 * are two doors into the same house, not a fallback for a broken one.
 */
object Users : Table<ServerDb, UserRow>("users", ::UserRow) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text() // stored lowercased; unique index in the migration
    val passwordHash by Column.Text().nullable()
    val createdAt by Column.Instant()

    init { id; email; passwordHash; createdAt }
}

class IdentityRow : Entity() {
    var id by Identities.id
    var userId by Identities.userId
    var provider by Identities.provider
    var subject by Identities.subject
    var createdAt by Identities.createdAt
}

/**
 * An account at someone else's house that stands for this one — "sign in with Google", when it comes.
 *
 * Nothing writes here yet. It is in the first migration all the same, because the alternative is to
 * discover later that adding it means moving user rows around: an account must be able to have several
 * ways in without one of them being privileged, and that shape has to be there from the start.
 */
object Identities : Table<ServerDb, IdentityRow>("identities", ::IdentityRow) {
    val id by Column.UUID().primaryKey()
    val userId by Column.UUID()
    val provider by Column.Text() // "google", "github", …
    val subject by Column.Text() // the provider's own id for this person
    val createdAt by Column.Instant()

    init { id; userId; provider; subject; createdAt }
}

class DeviceRow : Entity() {
    var id by Devices.id
    var userId by Devices.userId
    var name by Devices.name
    var lastSeenAt by Devices.lastSeenAt
}

/**
 * One browser, one row — the id the client makes on first run and keeps. Refresh tokens hang off a
 * device, so signing out of the laptop does not sign out of the phone, and a device that turns out to
 * be someone else's can be cut off on its own.
 */
object Devices : Table<ServerDb, DeviceRow>("devices", ::DeviceRow) {
    val id by Column.UUID().primaryKey()
    val userId by Column.UUID()
    val name by Column.Text().nullable()
    val lastSeenAt by Column.Instant()

    init { id; userId; name; lastSeenAt }
}

class RefreshTokenRow : Entity() {
    var id by RefreshTokens.id
    var userId by RefreshTokens.userId
    var deviceId by RefreshTokens.deviceId
    var tokenHash by RefreshTokens.tokenHash
    var issuedAt by RefreshTokens.issuedAt
    var expiresAt by RefreshTokens.expiresAt
    var revokedAt by RefreshTokens.revokedAt
    var replacedBy by RefreshTokens.replacedBy
}

/**
 * The long-lived half of a session, as a *hash*: the token itself is a random 32 bytes that exists only
 * in the client. A database that leaks therefore leaks nothing that can be signed in with.
 *
 * Each refresh rotates: the token presented is revoked, [replacedBy] the one handed back. That leaves a
 * chain, and the chain is what turns a stolen token into a detectable event — see `Tokens.kt`, where
 * presenting an already-revoked token cuts the whole device off rather than merely refusing the call.
 */
object RefreshTokens : Table<ServerDb, RefreshTokenRow>("refresh_tokens", ::RefreshTokenRow) {
    val id by Column.UUID().primaryKey()
    val userId by Column.UUID()
    val deviceId by Column.UUID()
    val tokenHash by Column.Text()
    val issuedAt by Column.Instant()
    val expiresAt by Column.Instant()
    val revokedAt by Column.Instant().nullable()
    val replacedBy by Column.UUID().nullable()

    init { id; userId; deviceId; tokenHash; issuedAt; expiresAt; revokedAt; replacedBy }
}

class LoginCodeRow : Entity() {
    var id by LoginCodes.id
    var email by LoginCodes.email
    var codeHash by LoginCodes.codeHash
    var issuedAt by LoginCodes.issuedAt
    var expiresAt by LoginCodes.expiresAt
    var usedAt by LoginCodes.usedAt
    var attempts by LoginCodes.attempts
}

/**
 * A six-digit code, mailed, good once and not for long. Hashed like a password, because it *is* one for
 * the ten minutes it lives, and because six digits are guessable: [attempts] is what stops a caller
 * from working through the million.
 */
object LoginCodes : Table<ServerDb, LoginCodeRow>("login_codes", ::LoginCodeRow) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text()
    val codeHash by Column.Text()
    val issuedAt by Column.Instant()
    val expiresAt by Column.Instant()
    val usedAt by Column.Instant().nullable()
    val attempts by Column.Int()

    init { id; email; codeHash; issuedAt; expiresAt; usedAt; attempts }
}

class SyncRowEntity : Entity() {
    var userId by SyncRows.userId
    var tbl by SyncRows.tbl
    var id by SyncRows.id
    var rev by SyncRows.rev
    var updatedAt by SyncRows.updatedAt
    var deletedAt by SyncRows.deletedAt
    var deviceId by SyncRows.deviceId
    var payload by SyncRows.payload
}

/**
 * Every row of every user, as JSON the server does not read.
 *
 * One table rather than seven mirrors of the client's, because the server has no use for the inside of a
 * card: it orders rows by [rev], decides which of two versions is later by [updatedAt], and hands them
 * on. Search and sorting happen in the browser. What that buys is a client free to change its own schema
 * without a server deploy — and two clients on different versions still syncing through a server that
 * understands neither.
 *
 * [deviceId] is the device that last wrote the row, and it exists to break a tie: two writes with the
 * same [updatedAt] to the millisecond must resolve the same way on every machine, and "the larger device
 * id" is arbitrary but agreed.
 *
 * [deletedAt] is a tombstone — the row is gone, and its going is a fact the other devices need. The
 * payload of a tombstone is null: what is deleted is not kept here in the hope it comes back.
 */
object SyncRows : Table<ServerDb, SyncRowEntity>("sync_rows", ::SyncRowEntity) {
    val userId by Column.UUID().primaryKey()
    val tbl by Column.Text().primaryKey()
    val id by Column.Text().primaryKey()
    val rev by Column.Long()
    val updatedAt by Column.Instant()
    val deletedAt by Column.Instant().nullable()
    val deviceId by Column.UUID()
    val payload by Column.Text().nullable()

    init { userId; tbl; id; rev; updatedAt; deletedAt; deviceId; payload }
}

class UserSeqRow : Entity() {
    var userId by UserSeq.userId
    var rev by UserSeq.rev
}

/**
 * The synchronisation cursor, one row per user: a counter that only goes up, bumped once per write and
 * stamped on every row that write touched. A client asks "what has changed since 41?" and the answer is
 * every row of that user with a greater revision.
 *
 * Per user, not global, so one busy account does not push everyone else's cursor along and make every
 * other device ask for a delta that turns out to be empty.
 */
object UserSeq : Table<ServerDb, UserSeqRow>("user_seq", ::UserSeqRow) {
    val userId by Column.UUID().primaryKey()
    val rev by Column.Long()

    init { userId; rev }
}
