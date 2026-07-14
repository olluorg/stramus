package stramus.server

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
    }
}
