@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section

/**
 * The properties a merge has to have, rather than examples of it working.
 *
 * `DuplicateMergeTest` checks what matches what. These check the things that must hold *whatever* the
 * tree is, which is where the worry actually lies: that a merge leaves duplicates behind, that running
 * it again keeps finding more, or that one row is handed to two different fusions and re-parented twice.
 * None of those show up in an example; all of them show up here.
 *
 * The plan is applied to the models rather than to a store — the store's own version of this is
 * exercised in `StoreTest`. What is proven here is the *plan*: that carrying it out faithfully leaves a
 * tree with nothing left to merge.
 */
class MergeConvergenceTest {

    @Test
    fun `merging twice finds nothing the second time`() {
        val tree = messyTree()
        val plan = planMerge(tree.sections, tree.collections, tree.cardSections, tree.cards)
        assertTrue(!plan.empty, "the tree is doubled on purpose; this is the test's own premise")

        val merged = tree.apply(plan)
        val again = planMerge(merged.sections, merged.collections, merged.cardSections, merged.cards)

        assertTrue(again.empty, "a merged tree has nothing left to merge — otherwise the user is asked forever")
    }

    @Test
    fun `no row is claimed by two fusions`() {
        val tree = messyTree()
        val plan = planMerge(tree.sections, tree.collections, tree.cardSections, tree.cards)

        val everyId = buildList {
            plan.sections.forEach { sectionPlan ->
                addAll(sectionPlan.section.ids)
                sectionPlan.collections.forEach { collectionPlan ->
                    addAll(collectionPlan.collection.ids)
                    collectionPlan.cardSections.forEach { addAll(it.ids) }
                    collectionPlan.cards.forEach { addAll(it.ids) }
                }
            }
        }
        assertEquals(everyId.size, everyId.toSet().size, "a row named twice would be re-parented twice")
    }

    @Test
    fun `a loser is never also a winner`() {
        val tree = messyTree()
        val plan = planMerge(tree.sections, tree.collections, tree.cardSections, tree.cards)

        val fusions = buildList {
            plan.sections.forEach { sectionPlan ->
                add(sectionPlan.section)
                sectionPlan.collections.forEach { collectionPlan ->
                    add(collectionPlan.collection)
                    addAll(collectionPlan.cardSections)
                    addAll(collectionPlan.cards)
                }
            }
        }
        val losers = fusions.flatMap { it.losers }.toSet()
        val winners = fusions.map { it.winner }.toSet()
        assertTrue((losers intersect winners).isEmpty(), "a row cannot both survive and be deleted")
    }

    @Test
    fun `nothing is lost - every card of the tree is still somewhere after the merge`() {
        val tree = messyTree()
        val plan = planMerge(tree.sections, tree.collections, tree.cardSections, tree.cards)
        val merged = tree.apply(plan)

        // The cards that went are exactly the duplicates the plan named, and every one of them has the
        // card it duplicates still standing.
        val dropped = plan.sections.flatMap { it.collections }.flatMap { it.cards }.flatMap { it.losers }.toSet()
        val kept = merged.cards.map { it.id }.toSet()
        assertEquals(tree.cards.size - dropped.size, merged.cards.size)
        assertTrue(tree.cards.none { it.id !in kept && it.id !in dropped }, "a card went that nobody asked to go")
        // And every card still hangs under a collection that exists.
        val liveCollections = merged.collections.map { it.id }.toSet()
        assertTrue(merged.cards.all { it.collectionId in liveCollections }, "a card left under a deleted collection")
    }

    @Test
    fun `a tree that was never doubled is left completely alone`() {
        val main = section("Main", seeded = true)
        val work = section("Работа")
        val a = collection(main, "Getting started")
        val b = collection(work, "Frontend")
        val tree = Tree(
            listOf(main, work),
            listOf(a, b),
            listOf(cardSection(b, "Docs")),
            listOf(link(a, "https://example.org/a"), link(b, "https://example.org/b")),
        )

        assertTrue(planMerge(tree.sections, tree.collections, tree.cardSections, tree.cards).empty)
    }

    @Test
    fun `the names the import and the merge call the same are the same`() {
        // The one rule both sides have to agree on, or an import quietly makes the pairs the merge is
        // there to prevent. Every one of these was a real shape in an exported bookmarks file.
        val same = listOf(
            "Работа" to "работа",
            "Работа" to " Работа ",
            "Work  Notes" to "work notes",
            "GitHub" to "github",
        )
        same.forEach { (a, b) ->
            assertEquals(mergeKeyOf(a), mergeKeyOf(b), "“$a” and “$b” have to be one name")
        }

        val different = listOf("Работа" to "Работы", "Frontend" to "Backend", "Docs" to "Doc")
        different.forEach { (a, b) ->
            assertTrue(mergeKeyOf(a) != mergeKeyOf(b), "“$a” and “$b” are two names")
        }
    }
}

// ---- a tree with every kind of doubling in it -------------------------------------------------------

private class Tree(
    val sections: List<Section>,
    val collections: List<Collection>,
    val cardSections: List<CardSection>,
    val cards: List<Card>,
)

/**
 * Carry a plan out over the models, the way the store carries it out over the rows: losers go, and what
 * hung under them hangs under the winner instead.
 */
private fun Tree.apply(plan: MergePlan): Tree {
    val sectionWinner = mutableMapOf<Uuid, Uuid>()
    val collectionWinner = mutableMapOf<Uuid, Uuid>()
    val cardSectionWinner = mutableMapOf<Uuid, Uuid>()
    val goneCards = mutableSetOf<Uuid>()

    plan.sections.forEach { sectionPlan ->
        sectionPlan.section.losers.forEach { sectionWinner[it] = sectionPlan.section.winner }
        sectionPlan.collections.forEach { collectionPlan ->
            collectionPlan.collection.losers.forEach { collectionWinner[it] = collectionPlan.collection.winner }
            collectionPlan.cardSections.forEach { fusion ->
                fusion.losers.forEach { cardSectionWinner[it] = fusion.winner }
            }
            collectionPlan.cards.forEach { goneCards += it.losers }
        }
    }

    val goneSections = sectionWinner.keys
    val goneCollections = collectionWinner.keys
    val goneCardSections = cardSectionWinner.keys

    return Tree(
        sections = sections.filter { it.id !in goneSections },
        collections = collections
            .filter { it.id !in goneCollections }
            .map { it.copy(sectionId = sectionWinner[it.sectionId] ?: it.sectionId) },
        cardSections = cardSections
            .filter { it.id !in goneCardSections }
            .map { it.copy(collectionId = collectionWinner[it.collectionId] ?: it.collectionId) },
        cards = cards
            .filter { it.id !in goneCards }
            .map { card ->
                card.copy(
                    collectionId = collectionWinner[card.collectionId] ?: card.collectionId,
                    cardSectionId = card.cardSectionId?.let { cardSectionWinner[it] ?: it },
                )
            },
    )
}

/**
 * Two devices' worth of the same collections, in every shape the duplication actually takes: a default
 * section under two languages, a section doubled by name, one doubled inside a single section by a
 * repeated import, card sections doubled under a merged collection, and the same page saved on both
 * sides under two spellings of its address.
 */
private fun messyTree(): Tree {
    val mainEn = section("Main", seeded = true)
    val mainRu = section("Главная", seeded = true)
    val hobbyA = section("ХОББИ")
    val hobbyB = section(" хобби ")

    val gettingStarted = collection(mainEn, "Getting started", at(1))
    val welcome = collection(mainRu, "Getting Started", at(2))
    val kormiumA = collection(hobbyA, "Kormium", at(1))
    val kormiumB = collection(hobbyB, "kormium", at(2))
    // Doubled inside one section, which is what running an import twice leaves behind.
    val kormiumC = collection(hobbyA, "Kormium  ", at(3))
    val alone = collection(hobbyA, "Stramus", at(1))

    val java = cardSection(kormiumA, "Java")
    val javaAgain = cardSection(kormiumB, "java")
    val general = cardSection(kormiumC, "General")

    return Tree(
        sections = listOf(mainEn, mainRu, hobbyA, hobbyB),
        collections = listOf(gettingStarted, welcome, kormiumA, kormiumB, kormiumC, alone),
        cardSections = listOf(java, javaAgain, general),
        cards = listOf(
            link(kormiumA, "https://kotlinlang.org/docs/", java),
            link(kormiumB, "http://www.kotlinlang.org/docs?utm_source=x#top", javaAgain),
            link(kormiumC, "https://kotlinlang.org/docs/", general),
            link(kormiumA, "https://github.com/olluorg/korm"),
            link(alone, "https://example.org/alone"),
            note(gettingStarted, "How to use", "the same words"),
            note(welcome, "How to use", "the same words"),
            note(welcome, "How to use", "quite other words"),
        ),
    )
}

private fun at(minute: Int): Instant = Instant.parse("2026-07-14T12:0$minute:00Z")

private fun section(title: String, seeded: Boolean = false, locked: Boolean = false) =
    Section(Uuid.random(), title, "a", deletable = !seeded, collapsed = false, locked = locked, seeded = seeded)

private fun collection(section: Section, title: String, createdAt: Instant = at(0)) =
    Collection(Uuid.random(), section.id, title, "a", createdAt, readOnly = false)

private fun cardSection(collection: Collection, title: String) =
    CardSection(Uuid.random(), collection.id, title, null, "a", collapsed = false)

private fun card(
    collection: Collection,
    kind: CardKind,
    title: String,
    url: String = "",
    content: String? = null,
    cardSection: CardSection? = null,
) = Card(
    id = Uuid.random(),
    collectionId = collection.id,
    cardSectionId = cardSection?.id,
    kind = kind,
    title = title,
    url = url,
    favicon = null,
    content = content,
    thumb = null,
    mime = null,
    blobSha = null,
    orderKey = "a",
    createdAt = at(0),
)

private fun link(collection: Collection, url: String, cardSection: CardSection? = null) =
    card(collection, CardKind.LINK, url, url = url, cardSection = cardSection)

private fun note(collection: Collection, title: String, body: String) =
    card(collection, CardKind.NOTE, title, content = body)
