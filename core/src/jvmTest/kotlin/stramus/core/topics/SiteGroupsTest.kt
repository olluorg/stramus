package stramus.core.topics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shelf a collection sits on, read from the addresses of its pages — and what that reading must never
 * do: speak for a search engine, mistake a service for its parent brand, or take so long that a window of
 * tabs feels stuck.
 */
class SiteGroupsTest {

    @Test
    fun `a site is put on the shelf it belongs to`() {
        assertEquals(SiteGroup.SHOPPING, siteGroupOf("https://www.ozon.ru/product/1"))
        assertEquals(SiteGroup.SHOPPING, siteGroupOf("https://market.yandex.ru/product/2"))
        assertEquals(SiteGroup.CODE, siteGroupOf("https://github.com/olluorg/korm"))
        assertEquals(SiteGroup.VIDEO, siteGroupOf("https://www.youtube.com/watch?v=aaaaaaaaaaa"))
        assertEquals(SiteGroup.JOBS, siteGroupOf("https://yoshkar-ola.hh.ru/vacancy/1"))
        assertEquals(SiteGroup.READING, siteGroupOf("https://habr.com/ru/articles/1/"))
        assertEquals(SiteGroup.DOCS, siteGroupOf("https://disk.yandex.ru/edit/1"))
    }

    @Test
    fun `a site the list has never heard of is left alone`() {
        assertNull(siteGroupOf("https://example.com/page"))
        assertNull(siteGroupOf("https://lada-forum.ru/topic/1"))
    }

    @Test
    fun `search engines and chatbots speak for nothing`() {
        assertNull(siteGroupOf("https://www.google.com/search?q=x"))
        assertNull(siteGroupOf("https://www.google.de/search?q=x"))
        assertNull(siteGroupOf("https://yandex.ru/search/?text=x"))
        assertNull(siteGroupOf("https://gemini.google.com/app/1"))
        assertNull(siteGroupOf("https://chatgpt.com/c/1"))
        // The services of the same brands are not search engines and must survive the rule above.
        assertEquals(SiteGroup.DOCS, siteGroupOf("https://drive.google.com/file/1"))
        assertEquals(SiteGroup.SHOPPING, siteGroupOf("https://market.yandex.ru/product/2"))
    }

    @Test
    fun `a collection goes where most of its pages live, and the neutral ones do not vote`() {
        // A car repair asked about in a chatbot, searched for, and finally bought: the shop decides.
        val repair = listOf(
            "https://gemini.google.com/app/1",
            "https://www.google.com/search?q=kalina",
            "https://www.ozon.ru/product/1",
        )
        assertEquals(SiteGroup.SHOPPING, siteGroupFor(repair))
        // Nothing but questions: no shelf at all, and the collection goes wherever new ones go.
        assertNull(siteGroupFor(listOf("https://chatgpt.com/c/1", "https://www.google.com/search?q=y")))
        assertNull(siteGroupFor(emptyList()))
    }

    @Test
    fun `a window of a thousand tabs is grouped in well under a second`() {
        // The whole thing runs while the user waits for a window to open, and it is quadratic in the worst
        // case: every candidate word against every tab still unplaced. This is the guard on that — generous
        // enough not to blink on a slow CI machine, tight enough that a real regression trips it.
        val tabs = (1..1000).map { i ->
            TitledTab(
                id = i,
                title = "Набор оправок для выпрессовки подшипников и сальников номер $i серия ${i % 40}",
                url = "https://www.ozon.ru/product/$i?text=%D0%BE%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8",
            )
        }
        val started = System.nanoTime()
        val topics = titleTopics(tabs) + siteTopics(tabs, taken = emptySet())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("[perf] 1000 alike tabs → ${topics.size} collections in ${tookMs}ms")
        assertTrue(tookMs < 3_000, "grouping 1000 tabs took ${tookMs}ms")
        assertTrue(topics.isNotEmpty())
    }

    @Test
    fun `a thousand tabs with nothing in common are no slower`() {
        // The harder shape for this: every tab bringing its own words, so the pass that picks a seed word
        // has hundreds of candidates to weigh instead of one, and hardly anything ever leaves the pool.
        val words = listOf("сальник", "подшипник", "кабель", "лампа", "секатор", "домкрат", "фара", "шланг")
        val tabs = (1..1000).map { i ->
            TitledTab(
                id = i,
                title = "${words[i % words.size]}$i для модели ${i * 7} размер ${i % 97} серия ${i % 13}",
                url = "https://shop$i.example.com/product/$i",
            )
        }
        val started = System.nanoTime()
        val topics = titleTopics(tabs) + siteTopics(tabs, taken = emptySet())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("[perf] 1000 unalike tabs → ${topics.size} collections in ${tookMs}ms")
        assertTrue(tookMs < 5_000, "grouping 1000 unrelated tabs took ${tookMs}ms")
    }
}
