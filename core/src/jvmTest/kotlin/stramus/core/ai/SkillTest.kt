@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import stramus.core.model.Card
import stramus.core.model.CardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A skill's reasoning with no model and no browser under it: what a skill is read back to be, which of
 * a collection's cards it fetches, and what the model is shown. The point of the fetch bound and the
 * context budget is that neither a huge collection nor a stripped skill card can turn into a broken run.
 */
class SkillTest {

    private val collectionId = Uuid.random()

    private fun card(
        kind: CardKind,
        title: String = "t",
        url: String = "",
        content: String? = null,
        mime: String? = null,
    ) = Card(
        id = Uuid.random(),
        collectionId = collectionId,
        cardSectionId = null,
        kind = kind,
        title = title,
        url = url,
        favicon = null,
        content = content,
        thumb = null,
        mime = mime,
        blobSha = null,
        orderKey = "a",
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun skillCard(source: SkillSource, prompt: String?) =
        card(CardKind.SKILL, title = "Сводка", url = skillUrl(source), content = prompt)

    @Test
    fun `a skill reads back its source and prompt`() {
        val def = parseSkill(skillCard(SkillSource.FETCH, "Summarise these"))
        assertEquals(SkillSource.FETCH, def?.source)
        assertEquals("Summarise these", def?.prompt)
    }

    @Test
    fun `a card that is not a skill is not one`() {
        assertNull(parseSkill(card(CardKind.LINK, url = "https://example.com")))
    }

    @Test
    fun `a skill without a prompt is not runnable`() {
        assertNull(parseSkill(skillCard(SkillSource.SECTION, prompt = "   ")))
        assertNull(parseSkill(skillCard(SkillSource.SECTION, prompt = null)))
    }

    @Test
    fun `an unknown source falls back to the section`() {
        // A skill synced from a newer client naming a source this one does not know still runs — over
        // the section, which needs nothing but the app itself.
        val def = parseSkill(card(CardKind.SKILL, url = "skill:whatever-is-next", content = "do it"))
        assertEquals(SkillSource.SECTION, def?.source)
    }

    @Test
    fun `a skill runs over the cards of its own section only`() {
        val groupA = Uuid.random()
        val groupB = Uuid.random()
        val cards = listOf(
            card(CardKind.LINK, title = "A1", url = "https://a.example/1").copy(cardSectionId = groupA),
            card(CardKind.LINK, title = "B1", url = "https://b.example/1").copy(cardSectionId = groupB),
            card(CardKind.NOTE, title = "loose", content = "ungrouped").copy(cardSectionId = null),
        )
        assertEquals(listOf("A1"), sectionCards(cards, groupA).map { it.title })
        assertEquals(listOf("loose"), sectionCards(cards, null).map { it.title })
    }

    @Test
    fun `only http links are fetched, deduped, and capped`() {
        val cards = buildList {
            add(card(CardKind.NOTE, content = "not a link"))
            add(card(CardKind.SKILL, url = skillUrl(SkillSource.FETCH), content = "p"))
            add(card(CardKind.LINK, url = "https://a.example/1"))
            add(card(CardKind.LINK, url = "https://a.example/1")) // duplicate
            add(card(CardKind.LINK, url = "chrome://settings")) // not http
            repeat(FETCH_MAX_DOCS + 5) { add(card(CardKind.LINK, url = "https://b.example/$it")) }
        }
        val urls = linksToFetch(cards)
        assertEquals(FETCH_MAX_DOCS, urls.size)
        assertEquals("https://a.example/1", urls.first())
        assertTrue(urls.none { it.startsWith("chrome://") })
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `section context skips the skill card and stays within budget`() {
        val cards = buildList {
            add(card(CardKind.SKILL, title = "Сводка", url = skillUrl(SkillSource.SECTION), content = "p"))
            add(card(CardKind.LINK, title = "Kotlin", url = "https://kotlinlang.org"))
            add(card(CardKind.NOTE, title = "Idea", content = "ship the thing"))
            repeat(2000) { add(card(CardKind.LINK, title = "L$it", url = "https://x.example/$it")) }
        }
        val context = sectionContext(cards)
        assertTrue("Kotlin" in context && "https://kotlinlang.org" in context)
        assertTrue("ship the thing" in context)
        assertTrue("Сводка" !in context) // the skill does not describe itself
        assertTrue(context.length <= SKILL_CONTEXT_BUDGET)
    }

    @Test
    fun `fetched context names an empty page but quotes only ones with text`() {
        val context = fetchedContext(
            listOf(
                FetchedDoc("Article", "https://news.example/a", "the body of the article"),
                FetchedDoc("Blocked", "https://news.example/b", "   "),
            ),
        )
        assertTrue("Article" in context && "the body of the article" in context)
        assertTrue("Blocked" in context) // named, so the model knows it was there
        assertTrue(context.length <= SKILL_CONTEXT_BUDGET)
    }
}
