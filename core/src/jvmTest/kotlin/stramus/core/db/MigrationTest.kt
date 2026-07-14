@file:OptIn(ExperimentalUuidApi::class, DelicateKormiumApi::class)

package stramus.core.db

import io.github.kormium.DelicateKormiumApi
import io.github.kormium.SuspendScope
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.createSqliteDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * The migration off integer positions, run against the database it will actually meet: one written by
 * the shipped version of the app, with rows in an order the user put them in.
 *
 * This is the one piece of this change that touches data a user cannot get back if it goes wrong — the
 * four tables are rebuilt and the originals dropped — so it is checked here rather than by opening the
 * app and looking at the sidebar. The SQLite under these tests is the same SQLite the browser runs,
 * only reachable: the store's code is common, and only the engine beneath it differs.
 */
class MigrationTest {

    @Test
    fun `an old database keeps its order`() = runTest {
        val db = openLegacyDatabase()

        val main = Uuid.random()
        val work = Uuid.random()
        val inbox = Uuid.random()
        val reading = Uuid.random()
        val group = Uuid.random()

        db.suspendTransaction {
            // Two sections, in the order the sidebar had them.
            legacySection(main, "Main", position = 0, deletable = 0)
            legacySection(work, "Work", position = 1, deletable = 1)

            // The old scheme numbered collections across the whole sidebar, not within a section.
            legacyCollection(inbox, main, "Inbox", position = 0)
            legacyCollection(reading, work, "Reading", position = 1)

            legacyCardSection(group, inbox, "Later", position = 0)

            // And cards in one sequence per collection, with the groups kept contiguous inside it:
            // the two ungrouped cards first, then the two in the card section.
            legacyCard(inbox, null, "first", position = 0)
            legacyCard(inbox, null, "second", position = 1)
            legacyCard(inbox, group, "third", position = 2)
            legacyCard(inbox, group, "fourth", position = 3)
            legacyCard(reading, null, "elsewhere", position = 0)
        }

        val store = openStramusStore(db)

        assertEquals(listOf("Main", "Work"), store.sections.all().map { it.title })

        // Collections are ordered within their section and nowhere else — the first collection of every
        // section holds the same key, and comparing two sections' collections to each other is a
        // question with no answer. The UI never asks it: it walks the sections, then their collections.
        val collections = store.collections.all()
        assertEquals(listOf("Inbox"), collections.filter { it.sectionId == main }.map { it.title })
        assertEquals(listOf("Reading"), collections.filter { it.sectionId == work }.map { it.title })

        assertEquals(listOf("Later"), store.cardSections.byCollection(inbox).map { it.title })

        val cards = store.cards.byCollection(inbox)
        assertEquals(listOf("first", "second"), cards.filter { it.cardSectionId == null }.map { it.title })
        assertEquals(listOf("third", "fourth"), cards.filter { it.cardSectionId == group }.map { it.title })
        assertEquals(listOf("elsewhere"), store.cards.byCollection(reading).map { it.title })
    }

    @Test
    fun `the old tables are gone and the new ones carry keys`() = runTest {
        val db = openLegacyDatabase()
        val section = Uuid.random()
        val collection = Uuid.random()
        db.suspendTransaction {
            legacySection(section, "Main", position = 0, deletable = 0)
            legacyCollection(collection, section, "Inbox", position = 0)
            legacyCard(collection, null, "a card", position = 0)
        }

        val store = openStramusStore(db)

        // Every row came out with a key, and the tables the migration renamed out of the way are not
        // still sitting there holding a second copy of the user's data.
        assertTrue(store.cards.byCollection(collection).all { it.orderKey.isNotEmpty() })
        assertTrue(store.sections.all().all { it.orderKey.isNotEmpty() })

        val readingLeftovers = runCatching {
            db.suspendAutocommit { Sections.execSql("""SELECT 1 FROM "cards_legacy"""") }
        }
        assertTrue(readingLeftovers.isFailure, "cards_legacy should not exist once the migration is done")

        // And the column they were rebuilt to be rid of is gone with them: a card can be saved without
        // a `position`, which on the old table would have been refused as NOT NULL.
        val card = store.cards.add(collection, "saved after the migration", "https://example.org", null)
        assertEquals("saved after the migration", store.cards.byCollection(collection).last { it.id == card.id }.title)
    }

    @Test
    fun `a database migrated once is not migrated again`() = runTest {
        val db = openLegacyDatabase()
        val section = Uuid.random()
        val collection = Uuid.random()
        db.suspendTransaction {
            legacySection(section, "Main", position = 0, deletable = 0)
            legacyCollection(collection, section, "Inbox", position = 0)
            legacyCard(collection, null, "first", position = 0)
            legacyCard(collection, null, "second", position = 1)
        }

        val first = openStramusStore(db)
        val keysAfterFirstOpen = first.cards.byCollection(collection).map { it.title to it.orderKey }

        // Opening again is what happens on every reload of the page, and it must be a no-op: the rows
        // are not re-keyed, and nothing is rebuilt.
        val second = openStramusStore(db)
        assertEquals(keysAfterFirstOpen, second.cards.byCollection(collection).map { it.title to it.orderKey })
    }

    @Test
    fun `a fresh database is seeded, not migrated`() = runTest {
        val db = freshDatabase()

        val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))

        val sections = store.sections.all()
        assertEquals(listOf("Main"), sections.map { it.title })
        val collections = store.collections.all()
        assertEquals(listOf("Getting started"), collections.map { it.title })
        val note = store.cards.byCollection(collections.single().id).single()
        assertEquals("How to use", note.title)
        assertNotNull(note.orderKey)
    }
}

// ---- the old schema, as the shipped app wrote it ---------------------------------------------------

/**
 * A database of its own for each test — a file, not `:memory:`, which sqlite-jdbc shares across the
 * whole JVM and would hand every test the rows of the one before it.
 */
private fun freshDatabase(): SuspendDatabase<StramusDb> =
    createSqliteDatabase(createTempDirectory("stramus-test").resolve("stramus.db").toString())

/** A database in the shape the previous version of the app left behind: integer positions, no keys. */
private suspend fun openLegacyDatabase(): SuspendDatabase<StramusDb> {
    val db: SuspendDatabase<StramusDb> = freshDatabase()
    db.suspendTransaction {
        legacyDdl.forEach { Sections.execSql(it) }
    }
    return db
}

// Writing rows the way the old app wrote them: straight SQL against the old columns, because the table
// objects in the app no longer describe this shape — which is rather the point of the migration.

private suspend fun SuspendScope<StramusDb>.legacySection(id: Uuid, title: String, position: Int, deletable: Int) =
    Sections.execSql(
        """
        INSERT INTO "sections" ("id", "title", "position", "deletable", "collapsed")
        VALUES ('$id', '$title', $position, $deletable, 0)
        """.trimIndent(),
    )

private suspend fun SuspendScope<StramusDb>.legacyCollection(id: Uuid, sectionId: Uuid, title: String, position: Int) =
    Collections.execSql(
        """
        INSERT INTO "collections" ("id", "sectionId", "title", "position", "createdAt", "readOnly")
        VALUES ('$id', '$sectionId', '$title', $position, '${Clock.System.now()}', 0)
        """.trimIndent(),
    )

private suspend fun SuspendScope<StramusDb>.legacyCardSection(id: Uuid, collectionId: Uuid, title: String, position: Int) =
    CardSections.execSql(
        """
        INSERT INTO "card_sections" ("id", "collectionId", "title", "position", "collapsed")
        VALUES ('$id', '$collectionId', '$title', $position, 0)
        """.trimIndent(),
    )

private suspend fun SuspendScope<StramusDb>.legacyCard(collectionId: Uuid, cardSectionId: Uuid?, title: String, position: Int) =
    Cards.execSql(
        """
        INSERT INTO "cards" ("id", "collectionId", "cardSectionId", "kind", "title", "url", "position", "createdAt")
        VALUES (
            '${Uuid.random()}', '$collectionId', ${cardSectionId?.let { "'$it'" } ?: "NULL"},
            'link', '$title', 'https://example.org/$title', $position, '${Clock.System.now()}'
        )
        """.trimIndent(),
    )

private val legacyDdl: List<String> = listOf(
    """
    CREATE TABLE "sections" (
        "id" text NOT NULL, "title" text NOT NULL, "position" integer NOT NULL,
        "deletable" integer NOT NULL, "collapsed" integer NOT NULL DEFAULT 0,
        "pinSalt" text, "pinHash" text, PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE "collections" (
        "id" text NOT NULL, "sectionId" text NOT NULL DEFAULT '', "title" text NOT NULL,
        "position" integer NOT NULL, "createdAt" text NOT NULL,
        "readOnly" integer NOT NULL DEFAULT 0, PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE "card_sections" (
        "id" text NOT NULL, "collectionId" text NOT NULL, "title" text NOT NULL, "description" text,
        "position" integer NOT NULL, "collapsed" integer NOT NULL DEFAULT 0, PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """
    CREATE TABLE "cards" (
        "id" text NOT NULL, "collectionId" text NOT NULL, "cardSectionId" text,
        "kind" text NOT NULL DEFAULT 'link', "title" text NOT NULL, "url" text NOT NULL,
        "favicon" text, "content" text, "thumb" text, "mime" text, "position" integer NOT NULL,
        "createdAt" text NOT NULL, PRIMARY KEY ("id")
    )
    """.trimIndent(),
    """CREATE TABLE "card_blobs" ("cardId" text NOT NULL, "data" text NOT NULL, PRIMARY KEY ("cardId"))""",
    """CREATE INDEX "idx_cards_collection" ON "cards" ("collectionId", "position")""",
    """CREATE INDEX "idx_card_sections_collection" ON "card_sections" ("collectionId", "position")""",
    """CREATE INDEX "idx_collections_section" ON "collections" ("sectionId", "position")""",
)
