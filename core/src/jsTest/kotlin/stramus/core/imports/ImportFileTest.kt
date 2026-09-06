@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.imports

import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import stramus.core.db.StoreSeed
import stramus.core.db.StramusStore
import stramus.core.db.installIndexedDb
import stramus.core.db.openStramusStore
import stramus.core.db.stramusSchema
import stramus.core.merge.planMerge

/**
 * What an import does to the database — which is where duplication is either prevented or created.
 *
 * The parsing is `ImportParseTest`'s. This is the other half, and the half that was worth worrying
 * about: an import decides, for every folder and every link in the file, whether the thing is already
 * here. Answer that question differently from the way the duplicate finder answers it and the import
 * quietly makes the pairs the finder exists to clean up — a folder named " Работа" landing beside the
 * "Работа" that was already there, with only a later merge to notice they were one thing.
 *
 * So the last test here is the one that matters most: after an import, there is nothing to merge.
 */
class ImportFileTest {

    private val bookmarks = """
        <DL><p>
            <DT><H3>Работа</H3>
            <DL><p>
                <DT><H3>Frontend</H3>
                <DL><p>
                    <DT><H3>Docs</H3>
                    <DL><p>
                        <DT><A HREF="https://developer.mozilla.org/">MDN</A>
                    </DL><p>
                    <DT><A HREF="https://vite.dev/">Vite</A>
                </DL><p>
            </DL><p>
        </DL><p>
    """.trimIndent()

    @Test
    fun `the file's folders become the tree it describes`() = importTest { store ->
        val result = store.import(bookmarks)
        assertEquals(2, result.added)

        val section = store.sections.all().single { it.title == "Работа" }
        val collection = store.collections.all().single { it.sectionId == section.id && it.title == "Frontend" }
        val cards = store.cards.byCollection(collection.id)
        assertEquals(setOf("MDN", "Vite"), cards.map { it.title }.toSet())

        val docs = store.cardSections.byCollection(collection.id).single()
        assertEquals("Docs", docs.title)
        assertEquals(docs.id, cards.first { it.title == "MDN" }.cardSectionId)
        assertEquals(null, cards.first { it.title == "Vite" }.cardSectionId, "one folder shallower, no group")
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() = importTest { store ->
        val first = store.import(bookmarks)
        val before = store.everything()

        val second = store.import(bookmarks)

        assertEquals(first.added, second.skipped, "every link was recognised as one already here")
        assertEquals(0, second.added)
        assertEquals(before, store.everything(), "not one row moved")
    }

    @Test
    fun `a folder spelled differently is the folder that is already here`() = importTest { store ->
        store.import(bookmarks)
        val sectionsBefore = store.sections.all().size
        val collectionsBefore = store.collections.all().size

        // What an export from another browser actually looks like: the same folders, with the spacing
        // and the capitals its own exporter felt like using.
        store.import(
            """
            <DL><p>
                <DT><H3>  работа </H3>
                <DL><p>
                    <DT><H3>FRONTEND</H3>
                    <DL><p>
                        <DT><H3>docs</H3>
                        <DL><p><DT><A HREF="https://caniuse.com/">Can I use</A></DL><p>
                    </DL><p>
                </DL><p>
            </DL><p>
            """.trimIndent(),
        )

        assertEquals(sectionsBefore, store.sections.all().size, "no second “Работа”")
        assertEquals(collectionsBefore, store.collections.all().size, "no second “Frontend”")
        val collection = store.collections.all().single { it.title == "Frontend" }
        assertEquals(1, store.cardSections.byCollection(collection.id).size, "no second “Docs”")
        assertEquals(3, store.cards.byCollection(collection.id).size, "and the new link joined them")
    }

    @Test
    fun `the same page written two ways is one card`() = importTest { store ->
        val result = store.import(
            """
            <DL><p>
                <DT><H3>Ссылки</H3>
                <DL><p>
                    <DT><A HREF="https://kotlinlang.org/docs/">Docs</A>
                    <DT><A HREF="http://www.kotlinlang.org/docs?utm_source=newsletter#top">Docs again</A>
                    <DT><A HREF="https://kotlinlang.org/docs">Docs, no slash</A>
                </DL><p>
            </DL><p>
            """.trimIndent(),
        )

        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        val collection = store.collections.all().single { it.title == "Ссылки" }
        assertEquals(1, store.cards.byCollection(collection.id).size)
    }

    @Test
    fun `the same page in another collection is another card`() = importTest { store ->
        // Sameness is per collection, on purpose: one page can belong in two places, and a person who
        // filed it in both meant to.
        val result = store.import(
            """
            <DL><p>
                <DT><H3>Раздел</H3>
                <DL><p>
                    <DT><H3>Первая</H3>
                    <DL><p><DT><A HREF="https://example.org/">Page</A></DL><p>
                    <DT><H3>Вторая</H3>
                    <DL><p><DT><A HREF="https://example.org/">Page</A></DL><p>
                </DL><p>
            </DL><p>
            """.trimIndent(),
        )
        assertEquals(2, result.added)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `links with no folder land in the collection the import is named after`() = importTest { store ->
        store.import("""<DL><p><DT><A HREF="https://example.org/">Loose</A></DL><p>""")

        // In the default section — no new section is invented for links whose file named no folder —
        // under a collection carrying the import's own name.
        val default = store.sections.all().single { !it.deletable }
        val collection = store.collections.all().single { it.sectionId == default.id && it.title == IMPORTED }
        assertEquals("Loose", store.cards.byCollection(collection.id).single().title)
    }

    @Test
    fun `the collection a new section is born with is swept away when the file never used it`() =
        importTest { store ->
            // `sections.create` gives every new section a collection of its own name. This file names
            // its own collection, so that one is left empty — and an empty collection nobody asked for
            // is not something to leave in the sidebar.
            store.import(bookmarks)

            val section = store.sections.all().single { it.title == "Работа" }
            assertEquals(
                listOf("Frontend"),
                store.collections.all().filter { it.sectionId == section.id }.map { it.title },
            )
        }

    @Test
    fun `a file with nothing in it changes nothing`() = importTest { store ->
        val before = store.everything()
        assertEquals(ImportResult(0, 0), store.import(""))
        assertEquals(ImportResult(0, 0), store.import("<DL><p></DL><p>"))
        assertEquals(before, store.everything())
    }

    @Test
    fun `a csv and a bookmarks file describing the same tree agree about it`() = importTest { store ->
        store.import(bookmarks)
        val before = store.everything()

        // The same two links, the same folders, a different format and a different spelling.
        store.import(
            "url,title,section,collection,card section\n" +
                "https://developer.mozilla.org/,MDN,работа,frontend,docs\n" +
                "https://vite.dev/,Vite,работа,frontend,\n",
            fileName = "export.csv",
        )

        assertEquals(before, store.everything(), "a second reading of the same tree is not a second tree")
    }

    @Test
    fun `after an import there is nothing left to merge`() = importTest { store ->
        // The whole worry, in one assertion. The import and the duplicate finder have to agree about
        // what "already here" means; if they ever drift apart, this is what says so.
        store.import(bookmarks)
        store.import(
            """
            <DL><p>
                <DT><H3> РАБОТА </H3>
                <DL><p>
                    <DT><H3>Frontend  </H3>
                    <DL><p><DT><A HREF="https://caniuse.com/">Can I use</A></DL><p>
                </DL><p>
            </DL><p>
            """.trimIndent(),
        )
        store.import(
            "url,title,section,collection\nhttps://example.org/,Example,Работа,Frontend\n",
            fileName = "more.csv",
        )

        val plan = planMerge(
            store.sections.all(),
            store.collections.all(),
            store.collections.all().flatMap { store.cardSections.byCollection(it.id) },
            store.collections.all().flatMap { store.cards.byCollection(it.id) },
        )
        assertTrue(plan.empty, "an import that leaves duplicates behind is an import that made them")
    }
}

// ---- helpers ---------------------------------------------------------------------------------------

private const val IMPORTED = "Imported"

/** Everything the database holds, as text — for asserting that a second import changed nothing at all. */
private suspend fun StramusStore.everything(): String {
    val sections = sections.all().sortedBy { it.title }.joinToString("|") { "S:${it.title}" }
    val collections = collections.all().sortedBy { it.title }.joinToString("|") { "C:${it.sectionId}/${it.title}" }
    val groups = collections().flatMap { c -> cardSections.byCollection(c.id) }
        .sortedBy { it.title }
        .joinToString("|") { "G:${it.collectionId}/${it.title}" }
    val cards = collections().flatMap { c -> cards.byCollection(c.id) }
        .sortedBy { it.title }
        .joinToString("|") { "K:${it.collectionId}/${it.cardSectionId}/${it.title}/${it.url}" }
    return "$sections\n$collections\n$groups\n$cards"
}

private suspend fun StramusStore.collections() = collections.all()

private suspend fun StramusStore.import(text: String, fileName: String = "bookmarks.html"): ImportResult =
    importFile(this, fileName, text, IMPORTED) { null }

private fun importTest(block: suspend (StramusStore) -> Unit) = runTest {
    installIndexedDb()
    deleteDatabase(stramusSchema.databaseName)
    val db = openDatabase(stramusSchema)
    val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))
    try {
        block(store)
    } finally {
        // The store, not the database: closing the connection alone leaves the search index still
        // watching it, and the next test to open one inherits the error. See `StramusStore.close`.
        store.close()
    }
}
