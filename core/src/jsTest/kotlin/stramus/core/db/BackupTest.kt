package stramus.core.db

import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The backup is the way out of a database the app cannot open, so what it has to prove is that it takes
 * everything and gives everything back — including the values that do not survive a naive round trip
 * through JSON, which is every timestamp in the schema (kidx stores an `Instant` as a JS `Date`).
 *
 * Written through kidx rather than through [StramusStore]: the store starts a background search index
 * that outlives a closed database, and this test closes one on purpose.
 */
class BackupTest {

    @Test
    fun `a backup taken and restored puts every row back`() = runTest {
        installIndexedDb()
        deleteDatabase(stramusSchema.databaseName)

        val sectionId = Uuid.random()
        val collectionId = Uuid.random()
        val cardId = Uuid.random()
        val savedAt = Clock.System.now()

        openDatabase(stramusSchema).let { db ->
            db.write(Sections, Collections, Cards) {
                Sections.put(
                    SectionRow().apply {
                        id = sectionId
                        title = "Main"
                        orderKey = "m"
                        deletable = 0
                        collapsed = 0
                        updatedAt = savedAt
                    },
                )
                Collections.put(
                    CollectionRow().apply {
                        id = collectionId
                        this.sectionId = sectionId
                        title = "Reading"
                        orderKey = "m"
                        createdAt = savedAt
                        readOnly = 0
                        updatedAt = savedAt
                    },
                )
                Cards.put(
                    CardRow().apply {
                        id = cardId
                        this.collectionId = collectionId
                        cardSectionId = null
                        kind = "note"
                        title = "a note"
                        url = ""
                        content = "with a body"
                        orderKey = "m"
                        createdAt = savedAt
                        updatedAt = savedAt
                    },
                )
            }
            db.close()
        }

        val backup = exportStramusBackup()
        assertTrue(looksLikeStramusBackup(backup), "the file says what it is")

        // The failure this exists for, in the only form a test can stage: the database is gone.
        deleteDatabase(stramusSchema.databaseName)

        val db = openDatabase(stramusSchema)
        val restore = restoreStramusBackup(backup)
        assertTrue(restore.rows >= 3, "every row was written back, not only some")
        assertEquals(emptyList(), restore.unknownStores)

        val card = db.read(Cards) { Cards.get(cardId) }
        assertEquals("a note", card?.title)
        assertEquals("with a body", card?.content)
        // The one value a plain JSON round trip would have flattened to a string: reaching it at all is
        // the assertion, since kidx throws when a field of type Instant finds text where a Date belongs.
        assertEquals(savedAt.truncatedToMillis(), card?.createdAt)
        assertEquals("Reading", db.read(Collections) { Collections.get(collectionId) }?.title)
        db.close()
    }

    @Test
    fun `a store the backup names and this database has not is reported, not invented`() = runTest {
        installIndexedDb()
        deleteDatabase(stramusSchema.databaseName)
        openDatabase(stramusSchema).close()

        val fromNewerBuild = """
            {"format":"stramus-backup","version":1,"database":"${stramusSchema.databaseName}",
             "stores":{"link_previews":[{"url":"https://example.org","title":"t"}]}}
        """.trimIndent()

        val restore = restoreStramusBackup(fromNewerBuild)
        assertEquals(0, restore.rows)
        assertEquals(listOf("link_previews"), restore.unknownStores)
    }
}

/** What a `Date` can hold: kidx stores an `Instant` to the millisecond, and reads it back the same. */
private fun Instant.truncatedToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())
