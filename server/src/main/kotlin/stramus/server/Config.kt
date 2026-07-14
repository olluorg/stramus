package stramus.server

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Everything the server needs to know that is not in the code. Read from the environment, with
 * defaults that are right for a developer's machine and wrong for anywhere else — [requireProduction]
 * is what refuses to let the wrong ones out of the house.
 */
data class ServerConfig(
    /** 8090, not 8080: the web app's own dev server has 8080, and the two are always run together. */
    val port: Int = 8090,

    /**
     * Where the database lives. SQLite for now — one writer, one instance, which is the honest shape
     * of this service today. The whole of the data access is Kormium's DSL and the whole of the schema
     * is in migrations, so moving to Postgres is a new set of migrations and a different factory
     * ([openServerDatabase]), not a rewrite.
     */
    val databasePath: String = "stramus.db",

    /** Signs the access tokens. A server started without one in production will not start. */
    val jwtSecret: String = "dev-secret-not-for-production",

    /** Short, because it cannot be revoked: everything it authorises is over within this. */
    val accessTokenTtl: Duration = 15.minutes,

    /** Long, because the user should not be asked to sign in again on a device they use. Rotated. */
    val refreshTokenTtl: Duration = 90.days,

    /** How long a mailed one-time code is good for. */
    val loginCodeTtl: Duration = 10.minutes,

    /**
     * The mail relay the one-time codes go through. Blank means there is none, and the codes go to the log
     * instead — which is right on a developer's machine and a catastrophe anywhere else, so production
     * refuses to start without it.
     */
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUser: String? = null,
    val smtpPassword: String? = null,
    val mailFrom: String = "stramus@localhost",

    /**
     * Whether the connection to the relay must be encrypted (STARTTLS, or implicit TLS on port 465).
     *
     * True everywhere it matters, and false only for a test SMTP server in the same JVM, which has no TLS
     * and needs none — there is no network for anyone to listen on. Turning it off against a real relay
     * would put the SMTP password, and every sign-in code, on the wire in the clear; production refuses.
     */
    val smtpRequireTls: Boolean = true,

    /** Where the file bytes live. Not in the database: a backup should not have to copy them. */
    val blobDir: String = "blobs",

    /** The largest single file. A tab manager is not a file host, and someone will try. */
    val maxBlobBytes: Int = 10 * 1024 * 1024,

    /** The most one account may store. */
    val quotaBytes: Long = 500L * 1024 * 1024,

    /** Whether the sweep for orphaned files runs at all. Off in tests, which do their own sweeping. */
    val blobGcEnabled: Boolean = true,

    /** How often it runs. An orphaned file costs disk and nothing else, so daily is generous. */
    val blobGcInterval: Duration = 24.hours,

    /**
     * The OAuth client id of this application, as registered with Google. Blank means the Google door is not
     * there at all — and it is the *only* thing that makes an ID token ours rather than anybody's, so a
     * server without one refuses Google sign-ins rather than accepting them loosely. See [GoogleIdTokenVerifier].
     */
    val googleClientId: String = "",

    /** Browsers refuse a cross-origin request that this does not name. Both clients are cross-origin. */
    val allowedOrigins: List<String> = listOf("http://localhost:8080"),

    val production: Boolean = false,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val production = env["STRAMUS_ENV"] == "production"
            val config = ServerConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8090,
                databasePath = env["STRAMUS_DB"] ?: "stramus.db",
                blobDir = env["STRAMUS_BLOBS"] ?: "blobs",
                smtpHost = env["STRAMUS_SMTP_HOST"] ?: "",
                smtpPort = env["STRAMUS_SMTP_PORT"]?.toIntOrNull() ?: 587,
                smtpUser = env["STRAMUS_SMTP_USER"],
                smtpPassword = env["STRAMUS_SMTP_PASSWORD"],
                mailFrom = env["STRAMUS_MAIL_FROM"] ?: "stramus@localhost",
                googleClientId = env["STRAMUS_GOOGLE_CLIENT_ID"] ?: "",
                jwtSecret = env["STRAMUS_JWT_SECRET"] ?: "dev-secret-not-for-production",
                allowedOrigins = env["STRAMUS_ALLOWED_ORIGINS"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: ServerConfig().allowedOrigins,
                production = production,
            )
            if (production) config.requireProduction()
            return config
        }
    }

    /** The verifier this configuration asks for, or null when no Google client id is set. */
    fun googleVerifier(): GoogleVerifier? =
        if (googleClientId.isNotBlank()) GoogleIdTokenVerifier(googleClientId) else null

    /**
     * The defaults above are conveniences for a developer, and every one of them is a hole in a server
     * on the open internet. Rather than trust that they were all overridden, say so out loud and fail
     * to start.
     */
    fun requireProduction() {
        require(jwtSecret != ServerConfig().jwtSecret) {
            "STRAMUS_JWT_SECRET is still the development default — every token the server issues would be forgeable"
        }
        require(jwtSecret.length >= 32) {
            "STRAMUS_JWT_SECRET is shorter than 32 characters"
        }
        require(allowedOrigins.none { it.startsWith("http://localhost") }) {
            "STRAMUS_ALLOWED_ORIGINS still names localhost"
        }
        require(smtpHost.isNotBlank()) {
            "STRAMUS_SMTP_HOST is not set — sign-in codes would be printed to the log, " +
                "which hands every account to anyone who can read it"
        }
        require(smtpRequireTls) {
            "the mail connection is unencrypted — the SMTP password and every sign-in code would cross " +
                "the network in the clear"
        }
    }
}
