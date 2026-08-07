@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.db

import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
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
 *
 * Runs under Node against `fake-indexeddb`, the same way kidx tests itself: there is no JVM target here
 * any more, kidx being browser-only.
 */
class StoreTest {

    @Test
    fun `a card dragged up the grid lands where it was dropped`() = storeTest { store ->
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
    fun `a move rewrites the card that moved and leaves the rest alone`() = storeTest { store ->
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
    fun `a card dragged into a group joins it, and leaving the group ungroups it`() = storeTest { store ->
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
    fun `sorting a group re-lays it out and leaves the cards it did not name at the end`() = storeTest { store ->
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
    fun `a collection is ordered within its section, and moving it between sections keeps both in order`() = storeTest { store ->
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
    fun `sections reorder in the sidebar`() = storeTest { store ->
        store.sections.create("Work")
        store.sections.create("Play")
        assertEquals(listOf("Main", "Work", "Play"), store.sections.all().map { it.title })

        val play = store.sections.all().first { it.title == "Play" }
        store.sections.move(play.id, 0)
        assertEquals(listOf("Play", "Main", "Work"), store.sections.all().map { it.title })
    }

    @Test
    fun `deleting a card section ungroups its cards instead of taking them with it`() = storeTest { store ->
        val collection = store.collections.all().single().id
        val group = store.cardSections.create(collection, "Later", null)
        val card = store.cards.add(collection, "kept", "https://example.org/kept", null, cardSectionId = group.id)

        store.cardSections.delete(group.id)

        val after = store.cards.byCollection(collection).first { it.id == card.id }
        assertEquals(null, after.cardSectionId, "the card should have been left ungrouped, not deleted")
        assertEquals(emptyList(), store.cardSections.byCollection(collection))
    }

    @Test
    fun `deleting a collection leaves another collection's cards and groups alone`() = storeTest { store ->
        // A regression test for a real kidx bug (fixed upstream): a compound-index query pinning only
        // the leading field (`collectionId eq x`, no trailing range on `orderKey`) encoded its lower
        // bound as a bare value instead of an array. IndexedDB then compared that bare value against the
        // index's array-shaped stored keys by cross-type ordering (a string always sorts below an
        // array), which made the lower bound match everything and let a lexicographically-earlier
        // collection's rows leak into this collection's results — visible here as another collection's
        // card and group turning up (or this collection's own group vanishing, depending on which way the
        // random ids happened to sort) after an unrelated collection was deleted.
        val main = store.sections.all().single().id
        val keep = store.collections.create("keep", main)
        val gone = store.collections.create("gone", main)

        val keepGroup = store.cardSections.create(keep.id, "Later", null)
        store.cards.add(keep.id, "in group", "https://example.org/a", null, cardSectionId = keepGroup.id)
        store.cards.add(keep.id, "ungrouped", "https://example.org/b", null)

        store.cardSections.create(gone.id, "OtherGroup", null)
        store.cards.add(gone.id, "c", "https://example.org/c", null)

        store.collections.delete(gone.id)

        assertEquals(listOf("Later"), store.cardSections.byCollection(keep.id).map { it.title })

        val cards = store.cards.byCollection(keep.id)
        assertEquals(setOf("in group", "ungrouped"), cards.map { it.title }.toSet())
        assertEquals(keepGroup.id, cards.first { it.title == "in group" }.cardSectionId)
    }

    @Test
    fun `a PIN can be set, checked and taken off again`() = storeTest { store ->
        val section = store.sections.all().single().id

        store.sections.setPin(section, "1234")
        assertTrue(store.sections.all().single().locked)
        assertTrue(store.sections.verifyPin(section, "1234"))
        assertTrue(!store.sections.verifyPin(section, "4321"))

        // Clearing writes null over both the salt and the hash — the one thing a patch row cannot say.
        store.sections.clearPin(section)
        assertTrue(!store.sections.all().single().locked)
        assertTrue(store.sections.verifyPin(section, "anything"), "an unlocked section takes any PIN")
    }

    @Test
    fun `an undone deletion puts the collection back where it was, not at the end`() = storeTest { store ->
        val main = store.sections.all().single().id
        store.collections.create("second", main)
        store.collections.create("third", main)

        val second = store.collections.all().first { it.title == "second" }
        val deleted = store.collections.delete(second.id)!!
        assertEquals(listOf("Getting started", "third"), store.collections.all().map { it.title })

        store.collections.restore(deleted)
        assertEquals(listOf("Getting started", "second", "third"), store.collections.all().map { it.title })
    }

    @Test
    fun `an undone card deletion puts it back where it was, not at the end`() = storeTest { store ->
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) } // drop the seeded note
        listOf("a", "b", "c").forEach { store.cards.add(collection, it, "https://example.org/$it", null) }

        val b = store.cards.byCollection(collection).first { it.title == "b" }
        val deleted = store.cards.delete(b.id)!!
        assertEquals(listOf("a", "c"), store.cards.byCollection(collection).titles())

        store.cards.restore(deleted)
        assertEquals(listOf("a", "b", "c"), store.cards.byCollection(collection).titles())
    }
}

private fun List<Card>.titles(): List<String> = map { it.title }

/**
 * A fresh database per test, deleted first so a previous run's data (or a previous test's, since
 * `fake-indexeddb` is process-global) never leaks in — the pattern kidx's own suite uses.
 */
private fun storeTest(block: suspend (StramusStore) -> Unit) = runTest {
    installIndexedDb()
    deleteDatabase(stramusSchema.databaseName)
    val db = openDatabase(stramusSchema)
    val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag a link here."))
    try {
        block(store)
    } finally {
        db.close()
    }
}
