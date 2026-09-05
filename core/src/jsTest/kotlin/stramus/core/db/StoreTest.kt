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
import stramus.core.merge.planMerge
import stramus.protocol.SyncRow
import stramus.core.sync.applyRemote
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid
import kotlin.time.Clock

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
    fun `a second seeded section is an ordinary one, and can be deleted`() = storeTest { store ->
        // What sync leaves behind: this browser seeded its own default, then joined an account holding
        // another device's. Both rows say "default". Only one of them can be this database's, and if
        // being seeded were enough to make a section undeletable the user would be stuck with a section
        // they never made and no way to remove it.
        val mine = store.sections.all().single()
        // Arriving the way it really does: another device's default section, in that device's language,
        // written straight in by the sync engine.
        store.db.write(Sections) { applyRemote(remoteDefaultSection("Main")) }

        val all = store.sections.all()
        assertEquals(2, all.size)
        assertEquals(1, all.count { !it.deletable }, "exactly one section is the default")
        assertEquals(2, all.count { it.seeded }, "both were made as one, and both still say so")

        val spare = all.single { it.deletable }
        assertTrue(store.sections.delete(spare.id) != null, "the one that is not the default has to go")
        assertEquals(listOf(mine.id), store.sections.all().map { it.id })

        // And the one that stays is refused, as the default always is.
        assertEquals(null, store.sections.delete(mine.id))
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

    @Test
    fun `merging two collections moves everything across, drops the duplicates, and comes back whole`() = storeTest { store ->
        val main = store.sections.all().single().id
        val keep = store.collections.create("Kormium", main)
        val twin = store.collections.create("kormium", main)
        val group = store.cardSections.create(twin.id, "Java", null)
        store.cards.add(keep.id, "docs", "https://kotlinlang.org/docs/", null)
        store.cards.add(twin.id, "docs again", "http://www.kotlinlang.org/docs?utm_source=x", null)
        store.cards.add(twin.id, "korm", "https://github.com/olluorg/korm", null, cardSectionId = group.id)

        val plan = planMerge(
            store.sections.all(),
            store.collections.all(),
            store.cardSections.byCollection(keep.id) + store.cardSections.byCollection(twin.id),
            store.cards.byCollection(keep.id) + store.cards.byCollection(twin.id),
        )
        val result = store.applyMerge(plan)

        assertEquals(1, result.collections)
        assertEquals(1, result.cards, "the page saved on both sides is one page")
        // One collection where there were two, and it is the one that kept its id — the title is the
        // later-written of the pair, by the same last-write-wins the sync settles a rename by.
        val left = store.collections.all().filter { it.sectionId == main }
        assertTrue(left.none { it.id == twin.id }, "the twin is gone")
        assertTrue(left.any { it.id == keep.id }, "and the one that stayed is the one everything moved into")
        val cards = store.cards.byCollection(keep.id)
        assertEquals(setOf("docs", "korm"), cards.map { it.title }.toSet())
        // The section came across with its card still in it, not tipped out on the way.
        val moved = store.cardSections.byCollection(keep.id).single()
        assertEquals("Java", moved.title)
        assertEquals(moved.id, cards.first { it.title == "korm" }.cardSectionId)

        store.undoMerge(result.undo)

        assertTrue(store.collections.all().any { it.id == twin.id }, "the twin comes back")
        assertEquals(2, store.cards.byCollection(twin.id).size, "and its cards come back to it")
        assertEquals(1, store.cards.byCollection(keep.id).size)
        assertEquals(listOf("Java"), store.cardSections.byCollection(twin.id).map { it.title })
    }

    @Test
    fun `emptying a group takes its cards and leaves the group and the rest of the collection`() = storeTest { store ->
        val collection = store.collections.all().single().id
        store.cards.byCollection(collection).forEach { store.cards.delete(it.id) } // drop the seeded note
        val group = store.cardSections.create(collection, "Later", null)
        listOf("a", "b").forEach { store.cards.add(collection, it, "https://example.org/$it", null) }
        store.cards.add(collection, "in group", "https://example.org/g", null, cardSectionId = group.id)

        val deleted = store.cards.deleteGroup(collection, null)
        assertEquals(listOf("a", "b"), deleted.map { it.card.title })
        assertEquals(listOf("in group"), store.cards.byCollection(collection).titles())
        assertEquals(listOf("Later"), store.cardSections.byCollection(collection).map { it.title })

        // And back in their own group, in the order they were in — the section's card is ordered against
        // its own group's keys, not against theirs, so it is no part of this comparison.
        store.cards.restoreAll(deleted)
        val after = store.cards.byCollection(collection)
        assertEquals(listOf("a", "b"), after.filter { it.cardSectionId == null }.titles())
        assertEquals(listOf("in group"), after.filter { it.cardSectionId == group.id }.titles())
    }
}

private fun List<Card>.titles(): List<String> = map { it.title }

/**
 * A fresh database per test, deleted first so a previous run's data (or a previous test's, since
 * `fake-indexeddb` is process-global) never leaks in — the pattern kidx's own suite uses.
 */
/** A section as the server hands one over: another device's default, flag and all. */
private fun remoteDefaultSection(title: String) = SyncRow(
    tbl = "sections",
    id = Uuid.random().toString(),
    updatedAt = Clock.System.now().toString(),
    payload = JsonObject(
        mapOf(
            "title" to JsonPrimitive(title),
            "orderKey" to JsonPrimitive("b"),
            "deletable" to JsonPrimitive("0"),
            "collapsed" to JsonPrimitive("0"),
        ),
    ),
)

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
