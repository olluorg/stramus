package stramus.server

import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.migrate

/**
 * Opens the server's database and brings its schema up to date.
 *
 * SQLite, on a file. One writer (Kormium's `poolSize` defaults to 1 for exactly that reason), which
 * means one instance of the server and no horizontal scaling — an honest ceiling for a personal tab
 * manager with hundreds of users, and a deliberate one rather than an accident.
 *
 * Moving to Postgres later is meant to be a small thing, and it stays small only if two rules hold:
 * **every read and write goes through Kormium's DSL**, and **raw SQL exists only in [serverMigrations]**.
 * Then the move is a different factory here plus a Postgres-flavoured migration list — not an audit of
 * every query in the service. The rest of this module is written to that rule.
 */
fun openServerDatabase(config: ServerConfig): SuspendDatabase<ServerDb> =
    createSqliteDatabase(config.databasePath) {
        beforeStart { migrate(serverMigrations) }
    }

/**
 * The schema, as SQL, applied in order and recorded once — `kormium-migrate` keeps the journal, checks
 * the checksums, and runs the batch in one transaction, so calling this on every start is safe.
 *
 * Migrations are immutable once applied: to change the schema, add another one. Editing the SQL below
 * after it has run anywhere will fail the next start with a checksum error, which is the point.
 */
val serverMigrations: List<Migration<ServerDb>> = listOf(
    Migration(
        "001-accounts",
        """
        CREATE TABLE "users" (
            "id" text NOT NULL,
            "email" text NOT NULL,
            "passwordHash" text,
            "createdAt" text NOT NULL,
            PRIMARY KEY ("id")
        );

        -- One account per address, whichever door it came in by. Lowercased on the way in, so this
        -- also settles the question of whether Ada@example.org and ada@example.org are two people.
        CREATE UNIQUE INDEX "idx_users_email" ON "users" ("email");

        CREATE TABLE "identities" (
            "id" text NOT NULL,
            "userId" text NOT NULL,
            "provider" text NOT NULL,
            "subject" text NOT NULL,
            "createdAt" text NOT NULL,
            PRIMARY KEY ("id")
        );
        CREATE UNIQUE INDEX "idx_identities_provider_subject" ON "identities" ("provider", "subject");

        CREATE TABLE "devices" (
            "id" text NOT NULL,
            "userId" text NOT NULL,
            "name" text,
            "lastSeenAt" text NOT NULL,
            PRIMARY KEY ("id")
        );
        CREATE INDEX "idx_devices_user" ON "devices" ("userId");

        CREATE TABLE "refresh_tokens" (
            "id" text NOT NULL,
            "userId" text NOT NULL,
            "deviceId" text NOT NULL,
            "tokenHash" text NOT NULL,
            "issuedAt" text NOT NULL,
            "expiresAt" text NOT NULL,
            "revokedAt" text,
            "replacedBy" text,
            PRIMARY KEY ("id")
        );
        -- Every refresh looks a token up by its hash; nothing ever looks one up by its id alone.
        CREATE UNIQUE INDEX "idx_refresh_tokens_hash" ON "refresh_tokens" ("tokenHash");
        CREATE INDEX "idx_refresh_tokens_device" ON "refresh_tokens" ("deviceId");

        CREATE TABLE "login_codes" (
            "id" text NOT NULL,
            "email" text NOT NULL,
            "codeHash" text NOT NULL,
            "issuedAt" text NOT NULL,
            "expiresAt" text NOT NULL,
            "usedAt" text,
            "attempts" integer NOT NULL DEFAULT 0,
            PRIMARY KEY ("id")
        );
        CREATE INDEX "idx_login_codes_email" ON "login_codes" ("email", "issuedAt");

        CREATE TABLE "user_seq" (
            "userId" text NOT NULL,
            "rev" integer NOT NULL,
            PRIMARY KEY ("userId")
        );
        """,
    ),
)
