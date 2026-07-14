@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.db

import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import stramus.core.model.Card

/**
 * What the repositories promise about order, now that a place is a key rather than a number: dragging
 * a card writes the card that moved and nothing else, and everything still comes back in the order the
 * user left it in.
 *
 * The "writes one row" part is not decoration — it is the reason for the whole change. Under the old
 * scheme a move renumbered every card of the collection, and once two devices sync, a hundred rewritten
 * rows are a hundred rows to disagree about.
 */
class StoreTest {

    @Test
    fun `a card dragged up the grid lands where it was dropped`() = runTest {
        val store = openStore()
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) } // drop the seeded note

        listOf("a", "b", "c", "d").forEach { store.cards.add(collection, it, "https://example.org/$it", null) }
        val before = store.cards.byCollection(collection)
        assertEquals(listOf("a", "b", "c", "d"), before.titles())

        // "d" to the front.
        store.cards.move(before.first { it.title == "d" }.id, collection, null, 0)
        assertEquals(listOf("d", "a", "b", "c"), store.cards.byCollection(collection).titles())

        // and into the middle.
        store.cards.move(before.first { it.title == "a" }.id, collection, null, 2)
        assertEquals(listOf("d", "b", "a", "c"), store.cards.byCollection(collection).titles())
    }

    @Test
    fun `a move rewrites the card that moved and leaves the rest alone`() = runTest {
        val store = openStore()
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) }
        listOf("a", "b", "c", "d").forEach { store.cards.add(collection, it, "https://example.org/$it", null) }

        val before = store.cards.byCollection(collection).associate { it.title to it.orderKey }
        store.cards.move(store.cards.byCollection(collection).first { it.title == "d" }.id, collection, null, 0)
        val after = store.cards.byCollection(collection).associate { it.title to it.orderKey }

        assertEquals(before.filterKeys { it != "d" }, after.filterKeys { it != "d" })
        assertTrue(after.getValue("d") < after.getValue("a"), "the moved card should now sort first")
    }

    @Test
    fun `a card dragged into a group joins it, and leaving the group ungroups it`() = runTest {
        val store = openStore()
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) }

        val group = store.cardSections.create(collection, "Later", null)
        val card = store.cards.add(collection, "a", "https://example.org/a", null)
        store.cards.add(collection, "grouped", "https://example.org/g", null, cardSectionId = group.id)

        store.cards.move(card.id, collection, group.id, 0)
        assertEquals(
            listOf("a", "grouped"),
            store.cards.byCollection(collection).filter { it.cardSectionId == group.id }.titles(),
        )

        store.cards.move(card.id, collection, null, 0)
        assertEquals(
            listOf("a"),
            store.cards.byCollection(collection).filter { it.cardSectionId == null }.titles(),
        )
    }

    @Test
    fun `sorting a group re-lays it out and leaves the cards it did not name at the end`() = runTest {
        val store = openStore()
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) }
        listOf("a", "b", "c").forEach { store.cards.add(collection, it, "https://example.org/$it", null) }

        val cards = store.cards.byCollection(collection).associateBy { it.title }
        // A card saved while the sort was being chosen is not in the list the caller hands over; it
        // keeps its place at the end rather than being dropped on the floor.
        val late = store.cards.add(collection, "late", "https://example.org/late", null)
        store.cards.reorder(collection, null, listOf(cards.getValue("c").id, cards.getValue("a").id, cards.getValue("b").id))

        assertEquals(listOf("c", "a", "b", "late"), store.cards.byCollection(collection).titles())
        assertEquals("late", store.cards.byCollection(collection).last().title)
        assertTrue(store.cards.byCollection(collection).any { it.id == late.id })
    }

    @Test
    fun `a collection is ordered within its section, and moving it between sections keeps both in order`() = runTest {
        val store = openStore()
        val main = store.sections.all().single().id
        val work = store.sections.create("Work") // comes with a collection of its own name

        store.collections.create("second", main)
        val moving = store.collections.create("moving", main)

        suspend fun inMain() = store.collections.all().filter { it.sectionId == main }.map { it.title }
        suspend fun inWork() = store.collections.all().filter { it.sectionId == work.id }.map { it.title }

        assertEquals(listOf("Getting started", "second", "moving"), inMain())
        assertEquals(listOf("Work"), inWork())

        // To the front of the other section.
        store.collections.move(moving.id, work.id, 0)
        assertEquals(listOf("Getting started", "second"), inMain())
        assertEquals(listOf("moving", "Work"), inWork())
    }

    @Test
    fun `sections reorder in the sidebar`() = runTest {
        val store = openStore()
        store.sections.create("Work")
        store.sections.create("Play")
        assertEquals(listOf("Main", "Work", "Play"), store.sections.all().map { it.title })

        val play = store.sections.all().first { it.title == "Play" }
        store.sections.move(play.id, 0)
        assertEquals(listOf("Play", "Main", "Work"), store.sections.all().map { it.title })
    }

    @Test
    fun `an undone deletion puts the collection back where it was, not at the end`() = runTest {
        val store = openStore()
        val main = store.sections.all().single().id
        store.collections.create("second", main)
        store.collections.create("third", main)

        val second = store.collections.all().first { it.title == "second" }
        val deleted = store.collections.delete(second.id)!!
        assertEquals(listOf("Getting started", "third"), store.collections.all().map { it.title })

        store.collections.restore(deleted)
        assertEquals(listOf("Getting started", "second", "third"), store.collections.all().map { it.title })
    }
}

private fun List<Card>.titles(): List<String> = map { it.title }

private suspend fun openStore(): StramusStore {
    val db: SuspendDatabase<StramusDb> =
        createSqliteDatabase(createTempDirectory("stramus-test").resolve("stramus.db").toString())
    return openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))
}
