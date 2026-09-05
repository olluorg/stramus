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
 * The matching rules, which are the whole of what a merge risks getting wrong.
 *
 * The cases that matter are the ones that must *not* match: a collection of the same name under a
 * different section, a note whose body differs, a file whose bytes differ. A merge that joins too little
 * leaves the user with a tidy-up to finish by hand; one that joins too much has thrown something away.
 */
class DuplicateMergeTest {

    @Test
    fun `sections of the same name are one section, whatever the case and the spacing`() {
        val a = section("ХОББИ")
        val b = section("  хобби ")
        val c = section("Работа")

        val plan = planMerge(listOf(a, b, c), emptyList(), emptyList(), emptyList())

        val fused = plan.sections.single { it.section.fuses }
        assertEquals(a.id, fused.section.winner)
        assertEquals(listOf(b.id), fused.section.losers)
    }

    @Test
    fun `the default section is matched by being the default, not by its name`() {
        // Two first installs opened in different languages. They are the same section — the one no user
        // ever made and no user can delete — and no title will ever bring them together.
        val russian = section("Главная", seeded = true)
        val english = section("Main", seeded = true)

        val plan = planMerge(listOf(russian, english), emptyList(), emptyList(), emptyList())

        val fused = plan.sections.single().section
        assertEquals(setOf(russian.id, english.id), fused.ids.toSet())
    }

    @Test
    fun `collections of the same name in different sections are left alone`() {
        val hobby = section("ХОББИ")
        val work = section("РАБОТА")
        val one = collection(hobby, "Работа")
        val two = collection(work, "Работа")

        val plan = planMerge(listOf(hobby, work), listOf(one, two), emptyList(), emptyList())

        assertTrue(plan.empty, "a name is only a name inside its own section")
    }

    @Test
    fun `collections of the same name inside one section are joined, and their cards deduplicated`() {
        val hobby = section("ХОББИ")
        val first = collection(hobby, "Kormium", createdAt = at(1))
        val second = collection(hobby, "kormium", createdAt = at(2))
        val kept = link(first, "https://kotlinlang.org/docs/")
        val same = link(second, "http://www.kotlinlang.org/docs?utm_source=x#top")
        val other = link(second, "https://github.com/olluorg/korm")

        val plan = planMerge(listOf(hobby), listOf(first, second), emptyList(), listOf(kept, same, other))

        val merged = plan.sections.single().collections.single()
        assertEquals(first.id, merged.collection.winner, "the older collection keeps its id")
        assertEquals(listOf(second.id), merged.collection.losers)
        // The two are one page: scheme, www, the tracking parameter and the fragment are not the address.
        assertEquals(1, merged.duplicateCards)
        assertEquals(setOf(kept.id, same.id), merged.cards.single().ids.toSet())
    }

    @Test
    fun `two notes under one title with different words are two notes`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Заметки", createdAt = at(1))
        val two = collection(hobby, "Заметки", createdAt = at(2))
        val mine = note(one, "План", "первый абзац")
        val theirs = note(two, "План", "совсем другой абзац")

        val plan = planMerge(listOf(hobby), listOf(one, two), emptyList(), listOf(mine, theirs))

        val merged = plan.sections.single().collections.single()
        assertTrue(merged.collection.fuses, "the collections are still one collection")
        assertEquals(0, merged.duplicateCards, "but the writing in them is not the same writing")
    }

    @Test
    fun `two notes that say the same thing are one note`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Заметки", createdAt = at(1))
        val two = collection(hobby, "Заметки", createdAt = at(2))

        val plan = planMerge(
            listOf(hobby),
            listOf(one, two),
            emptyList(),
            listOf(note(one, "План", "один и тот же текст"), note(two, "план", "один и тот же текст")),
        )

        assertEquals(1, plan.sections.single().collections.single().duplicateCards)
    }

    @Test
    fun `files are matched by their bytes, never by their name`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Файлы", createdAt = at(1))
        val two = collection(hobby, "Файлы", createdAt = at(2))
        val same = listOf(file(one, "отчёт.pdf", sha = "aaa"), file(two, "report.pdf", sha = "aaa"))
        val different = listOf(file(one, "смета.pdf", sha = "bbb"), file(two, "смета.pdf", sha = "ccc"))
        // Bytes this device has not got yet: nothing to compare, so nothing to delete over.
        val unknown = listOf(file(one, "скан.pdf", sha = null), file(two, "скан.pdf", sha = null))

        val plan = planMerge(listOf(hobby), listOf(one, two), emptyList(), same + different + unknown)

        val merged = plan.sections.single().collections.single()
        assertEquals(1, merged.duplicateCards)
        assertEquals(same.map { it.id }.toSet(), merged.cards.single().ids.toSet())
    }

    @Test
    fun `a locked section is not read at all, and is counted as skipped`() {
        val locked = section("Личное", locked = true)
        val alsoLocked = section("Личное", locked = true)
        val inside = collection(locked, "Секрет")

        val plan = planMerge(listOf(locked, alsoLocked), listOf(inside), emptyList(), emptyList())

        assertTrue(plan.empty, "a PIN is not a thing a tidy-up gets to look past")
        assertEquals(2, plan.skipped.lockedSections)
    }

    @Test
    fun `read-only collections are left as they are`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Сессия", readOnly = true)
        val two = collection(hobby, "Сессия", readOnly = true)

        val plan = planMerge(listOf(hobby), listOf(one, two), emptyList(), emptyList())

        assertTrue(plan.empty, "two saved sessions are two sessions")
        assertEquals(2, plan.skipped.readOnlyCollections)
    }

    @Test
    fun `card sections of one merged collection are joined by name`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Kormium", createdAt = at(1))
        val two = collection(hobby, "Kormium", createdAt = at(2))
        val mine = cardSection(one, "Java")
        val theirs = cardSection(two, "java")

        val plan = planMerge(listOf(hobby), listOf(one, two), listOf(mine, theirs), emptyList())

        val merged = plan.sections.single().collections.single()
        assertEquals(listOf(theirs.id), merged.cardSections.single().losers)
    }

    @Test
    fun `a link with no address is never matched to another`() {
        val hobby = section("ХОББИ")
        val one = collection(hobby, "Ссылки", createdAt = at(1))
        val two = collection(hobby, "Ссылки", createdAt = at(2))

        val plan = planMerge(
            listOf(hobby),
            listOf(one, two),
            emptyList(),
            listOf(link(one, ""), link(two, "   ")),
        )

        assertEquals(0, plan.sections.single().collections.single().duplicateCards)
    }

    @Test
    fun `a tree with nothing doubled in it yields nothing to do`() {
        val hobby = section("ХОББИ")
        val work = section("РАБОТА")
        val a = collection(hobby, "Kormium")
        val b = collection(work, "Вакансии")

        val plan = planMerge(
            listOf(hobby, work),
            listOf(a, b),
            listOf(cardSection(a, "Java")),
            listOf(link(a, "https://example.org/a"), link(b, "https://example.org/b")),
        )

        assertTrue(plan.empty)
    }
}

// ---- helpers ---------------------------------------------------------------------------------------

private fun at(minute: Int): Instant = Instant.parse("2026-07-14T12:0$minute:00Z")

/** [seeded] is a section made as some database's default — see [Section.seeded], which is what pairs them. */
private fun section(title: String, seeded: Boolean = false, locked: Boolean = false) =
    Section(Uuid.random(), title, "a", deletable = !seeded, collapsed = false, locked = locked, seeded = seeded)

private fun collection(
    section: Section,
    title: String,
    createdAt: Instant = at(0),
    readOnly: Boolean = false,
) = Collection(Uuid.random(), section.id, title, "a", createdAt, readOnly)

private fun cardSection(collection: Collection, title: String) =
    CardSection(Uuid.random(), collection.id, title, null, "a", collapsed = false)

private fun card(
    collection: Collection,
    kind: CardKind,
    title: String,
    url: String = "",
    content: String? = null,
    blobSha: String? = null,
) = Card(
    id = Uuid.random(),
    collectionId = collection.id,
    cardSectionId = null,
    kind = kind,
    title = title,
    url = url,
    favicon = null,
    content = content,
    thumb = null,
    mime = null,
    blobSha = blobSha,
    orderKey = "a",
    createdAt = at(0),
)

private fun link(collection: Collection, url: String) = card(collection, CardKind.LINK, url, url = url)

private fun note(collection: Collection, title: String, body: String) =
    card(collection, CardKind.NOTE, title, content = body)

private fun file(collection: Collection, name: String, sha: String?) =
    card(collection, CardKind.FILE, name, blobSha = sha)
