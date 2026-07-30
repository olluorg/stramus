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
     * The mail relay the one-time codes go through. Blank means there is none: on a developer's machine the
     * codes go to the log, and in production the mailed-code door is simply closed ([DisabledMailer]) rather
     * than the server refusing to start — password and Google sign-in need no mail relay.
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

    /**
     * A second, separate OAuth client id — the one registered as a *Chrome Extension* rather than a
     * *Web application* — for `chrome.identity.getAuthToken`, which hands back an opaque access token
     * rather than a signed ID token and so needs a different check ([GoogleAccessTokenVerifier]). Blank
     * means that door specifically is not there; [googleClientId] alone still opens the other one.
     */
    val googleExtensionClientId: String = "",

    /** Browsers refuse a cross-origin request that this does not name. Both clients are cross-origin. */
    val allowedOrigins: List<String> = listOf("http://localhost:8080"),

    /**
     * Whether the server fetches site icons on the clients' behalf ([FaviconService]). Off means the clients
     * fall back to asking the public icon services themselves, which works and is what they did before —
     * it just tells those services which hosts a given person has saved.
     */
    val faviconProxyEnabled: Boolean = true,

    /** How long a fetched icon is served from the cache before the site is asked again. */
    val faviconTtl: Duration = 30.days,

    /**
     * How long "this host has no icon" is believed. Shorter than [faviconTtl] on purpose: a site that gains
     * an icon has no way to tell us, and a month of a letter tile for a site that has had one for three
     * weeks is a long time to be wrong.
     */
    val faviconNegativeTtl: Duration = 3.days,

    /** Anything larger is not a favicon. Also the ceiling on what a hostile host can make this server read. */
    val maxFaviconBytes: Int = 150 * 1024,

    /**
     * Cache misses one address may cause per minute. A hit costs nothing and is not counted; a miss makes
     * this server fetch a host of the caller's choosing, and that is the thing worth rationing.
     */
    val faviconMissesPerMinute: Int = 60,

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
                googleExtensionClientId = env["STRAMUS_GOOGLE_EXTENSION_CLIENT_ID"] ?: "",
                jwtSecret = env["STRAMUS_JWT_SECRET"] ?: "dev-secret-not-for-production",
                faviconProxyEnabled = env["STRAMUS_FAVICON_PROXY"] != "0",
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

    /**
     * The verifier(s) this configuration asks for, or null when neither Google client id is set. Both
     * configured means both doors are open — an incoming token is tried against each check in turn, since
     * whether it is a signed ID token or an opaque access token is exactly what tells them apart.
     */
    fun googleVerifier(): GoogleVerifier? {
        val verifiers = listOfNotNull(
            googleClientId.takeIf { it.isNotBlank() }?.let { GoogleIdTokenVerifier(it) },
            googleExtensionClientId.takeIf { it.isNotBlank() }?.let { GoogleAccessTokenVerifier(it) },
        )
        return when (verifiers.size) {
            0 -> null
            1 -> verifiers.single()
            else -> GoogleVerifier { token -> verifiers.firstNotNullOfOrNull { it.verify(token) } }
        }
    }

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
        // No mail relay is not refused here: without STRAMUS_SMTP_HOST, mailerFor() hands out a
        // DisabledMailer rather than logging codes, so the mailed-code door is closed, not insecure.
        require(smtpRequireTls) {
            "the mail connection is unencrypted — the SMTP password and every sign-in code would cross " +
                "the network in the clear"
        }
    }
}
