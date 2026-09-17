package stramus.core.topics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The title grouping, on the cases a real window is full of.
 *
 * Most of these are failures that actually happened, each one having produced a collection somebody would
 * have had to delete by hand: fifteen tabs under "OZON", a job board's own slogan joined to a photo studio
 * because both mention the town, "Москве работа" as a name for three vacancies.
 */
class TitleTopicsTest {

    private var next = 0

    private fun tab(title: String, url: String) = TitledTab(next++, title, url)

    private fun titles(topics: List<TitleTopic>, tabs: List<TitledTab>, name: String): List<String> {
        val topic = topics.firstOrNull { it.title == name } ?: return emptyList()
        return topic.tabIds.map { id -> tabs.first { it.id == id }.title }
    }

    @Test
    fun `a site's own name never joins or names anything`() {
        val tabs = listOf(
            tab("Набор оправок для подшипников купить на OZON", "https://www.ozon.ru/product/1"),
            tab("Набор оправок для сальников купить на OZON", "https://www.ozon.ru/product/2"),
            tab("Нейроскакалка купить на OZON", "https://www.ozon.ru/product/3"),
            tab("Надувной шар купить на OZON", "https://www.ozon.ru/product/4"),
        )
        val topics = titleTopics(tabs)
        assertTrue(topics.none { it.title.contains("OZON", ignoreCase = true) }, "got $topics")
        assertEquals(2, topics.single().tabIds.size, "only the two about оправки go together")
    }

    @Test
    fun `front pages are not grouped and not offered`() {
        val tabs = listOf(
            tab("OZON маркетплейс – миллионы товаров по выгодным ценам", "https://www.ozon.ru/"),
            tab("OZON маркетплейс – миллионы товаров по выгодным ценам", "https://www.ozon.ru/?abt=1"),
            tab("Работа в Йошкар-Оле, поиск персонала и публикация вакансий", "https://yoshkar-ola.hh.ru/?hhtmFrom=main"),
        )
        assertTrue(titleTopics(tabs).isEmpty(), "front pages have nothing to say")
        assertTrue(isFrontPage("https://www.ozon.ru/?abt=1"))
        assertTrue(isFrontPage("https://github.com"))
        assertTrue(!isFrontPage("https://duckduckgo.com/?q=rust"), "a search at the root is not a front page")
        assertTrue(!isFrontPage("https://github.com/olluorg/korm"))
    }

    @Test
    fun `what the user typed names the group`() {
        val tabs = listOf(
            tab("Интернет-магазин Wildberries: широкий ассортимент товаров", "https://www.wildberries.ru/catalog/0/search.aspx?search=%D0%9D%D0%B0%D0%B1%D0%BE%D1%80+%D0%BE%D0%BF%D1%80%D0%B0%D0%B2%D0%BE%D0%BA"),
            tab("Набор оправок для выпрессовки подшипников", "https://www.ozon.ru/product/1"),
            tab("Набор оправок для установки сальников", "https://www.ozon.ru/product/2"),
        )
        val topic = titleTopics(tabs).single()
        assertEquals("Набор оправок", topic.title, "the shop's slogan is no name; the search phrase is")
        assertEquals(3, topic.tabIds.size, "the search page belongs with what was searched for")
    }

    @Test
    fun `a name is never a pair of words nobody wrote side by side`() {
        val tabs = listOf(
            tab("Вакансия Tech Lead Java в Москве, работа в компании Цифра", "https://hh.ru/vacancy/1"),
            tab("Вакансия Senior Java Backend Developer в Москве, работа в компании Сбер", "https://hh.ru/vacancy/2"),
            tab("Вакансия Архитектор в Москве, работа в компании Орбита", "https://hh.ru/vacancy/3"),
        )
        val topic = titleTopics(tabs).single()
        assertEquals(3, topic.tabIds.size)
        assertTrue(topic.title.startsWith("Вакансия"), "a comma is not a space: got «${topic.title}»")
    }

    @Test
    fun `two tabs need two words in common, unless the word was typed`() {
        val shaky = listOf(
            tab("Думал, что строю бизнес — YouTube", "https://www.youtube.com/watch?v=aaaaaaaaaaa"),
            tab("Стас Ай Как Просто – Тупее Чем Вы Думали", "https://www.youtube.com/watch?v=bbbbbbbbbbb"),
        )
        assertTrue(titleTopics(shaky).isEmpty(), "one shared verb is a coincidence")

        val typed = listOf(
            tab("экстрактор М16 - купить", "https://www.ozon.ru/search/?text=%D1%8D%D0%BA%D1%81%D1%82%D1%80%D0%B0%D0%BA%D1%82%D0%BE%D1%80+%D0%9C16"),
            tab("восстановление резьбы м16 - купить", "https://www.ozon.ru/search/?text=%D0%B2%D0%BE%D1%81%D1%81%D1%82%D0%B0%D0%BD%D0%BE%D0%B2%D0%BB%D0%B5%D0%BD%D0%B8%D0%B5+%D1%80%D0%B5%D0%B7%D1%8C%D0%B1%D1%8B+%D0%BC16"),
        )
        assertEquals(2, titleTopics(typed).single().tabIds.size, "nobody types the same word twice by accident")
    }

    @Test
    fun `one topic crosses every site it is spread over`() {
        val tabs = listOf(
            tab("Первая часть гайда по System Design / Пройти интервью", "https://www.youtube.com/watch?v=ccccccccccc"),
            tab("Подготовка к System Design интервью", "https://gemini.google.com/app/1"),
            tab("System Design для начинающих. Часть 1", "https://habr.com/ru/articles/1/"),
            tab("Мультфильм. Все серии", "https://www.youtube.com/watch?v=ddddddddddd"),
        )
        val topics = titleTopics(tabs)
        assertEquals("System Design", topics.single().title)
        assertEquals(3, topics.single().tabIds.size)
    }

    @Test
    fun `a search box is read wherever the site keeps it`() {
        assertEquals("набор оправок", siteQueryOf("https://www.wildberries.ru/catalog/0/search.aspx?search=%D0%BD%D0%B0%D0%B1%D0%BE%D1%80+%D0%BE%D0%BF%D1%80%D0%B0%D0%B2%D0%BE%D0%BA"))
        assertEquals("метчик М16", siteQueryOf("https://www.ozon.ru/search/?text=%D0%BC%D0%B5%D1%82%D1%87%D0%B8%D0%BA+%D0%9C16&from_global=true"))
        assertEquals("kotlin flow", siteQueryOf("https://www.google.com/search?q=kotlin+flow"))
        assertNull(siteQueryOf("https://github.com/olluorg/korm/pull/40"))
    }

    @Test
    fun `words are cut back to a stem two forms of one word meet at`() {
        assertEquals(stem("калины"), stem("калине"))
        assertEquals(stem("оправок"), stem("оправок"))
        assertTrue(stem("лада").length >= 4, "a short word keeps its ending rather than becoming nothing")
    }

    @Test
    fun `a title keeps its topic and loses the site's decorations`() {
        // The unread counter goes always; the tail goes only when it names the site in the address's own
        // letters. "— Хабр" is not "habr" to a comparison, and survives here — the frequency rules are
        // what remove it, having seen it on every page of that site.
        assertEquals("Корутины в Kotlin — Хабр", cleanTitle("(35) Корутины в Kotlin — Хабр", "https://habr.com/ru/articles/1/"))
        assertEquals("Concurrent Queue", cleanTitle("Concurrent Queue · GitHub", "https://github.com/olluorg/korm"))
        assertEquals(
            "Сальник бака стиральной машины",
            cleanTitle("Сальник бака стиральной машины (1864224795)", "https://www.ozon.ru/product/1"),
        )
    }

    @Test
    fun `what no topic wanted is gathered by site, where there is enough of it`() {
        val tabs = listOf(
            tab("Мультфильм. Все серии", "https://www.youtube.com/watch?v=aaaaaaaaaaa"),
            tab("Страшные истории на ночь", "https://www.youtube.com/watch?v=bbbbbbbbbbb"),
            tab("Apex Legends ранкед", "https://www.youtube.com/watch?v=ccccccccccc"),
            tab("Тупее чем вы думали", "https://www.youtube.com/watch?v=ddddddddddd"),
            tab("YouTube", "https://www.youtube.com/"),
            tab("Публикации / Моя лента", "https://habr.com/ru/feed/"),
            tab("Про n8n", "https://habr.com/ru/articles/2/"),
        )
        val found = siteTopics(tabs, taken = emptySet())
        assertEquals(1, found.size, "two tabs from a site are two tabs, not a collection: $found")
        assertEquals("Youtube", found.single().title)
        assertEquals(4, found.single().tabIds.size, "the front page is not offered, here as everywhere")
    }

    @Test
    fun `a tab a topic already took is not offered twice`() {
        val tabs = (1..5).map { tab("Товар $it", "https://www.ozon.ru/product/$it") }
        val taken = tabs.take(3).map { it.id }.toSet()
        assertTrue(siteTopics(tabs, taken).isEmpty(), "two left is below the floor")
        assertEquals(5, siteTopics(tabs, taken = emptySet()).single().tabIds.size)
    }

    @Test
    fun `a search engine and a chatbot are never a collection of their own`() {
        val tabs = listOf(
            tab("система дизайн - Google Search", "https://www.google.com/search?q=a"),
            tab("php массивы - Google Search", "https://www.google.com/search?q=b"),
            tab("Ремонт двигателя - Google Gemini", "https://gemini.google.com/app/1"),
            tab("Боль в спине - Google Gemini", "https://gemini.google.com/app/2"),
            tab("ChatGPT - Health", "https://chatgpt.com/c/1"),
            tab("Снятие втулок ГБЦ", "https://chatgpt.com/c/2"),
        )
        assertTrue(siteTopics(tabs, taken = emptySet()).isEmpty(), "asking is not keeping")
    }

    @Test
    fun `one brand's several services do not become one collection`() {
        // Drive, the Calendar and the Web Store are not "Google" to anybody who has them open, and four
        // unrelated pages under that heading is what naming by the domain alone produced.
        val tabs = listOf(
            tab("Коммуналка.xlsx", "https://drive.google.com/file/1"),
            tab("Week of September 7", "https://calendar.google.com/week"),
            tab("stramus — tab collections", "https://chromewebstore.google.com/detail/1"),
            tab("Extensions", "https://chrome.google.com/webstore"),
        )
        assertTrue(siteTopics(tabs, taken = emptySet()).isEmpty(), "one tab each, and no two of them alike")
    }

    @Test
    fun `a site is named the way people say it`() {
        assertEquals("Ozon", siteName("https://www.ozon.ru/product/1"))
        assertEquals("HH", siteName("https://yoshkar-ola.hh.ru/vacancy/1"))
        assertEquals("Github", siteName("https://github.com/olluorg/korm"))
        // Under an umbrella the service is part of the name; elsewhere the subdomain is not.
        assertEquals("Yandex Market", siteName("https://market.yandex.ru/search"))
        assertEquals("Google Drive", siteName("https://drive.google.com/file/1"))
    }

    @Test
    fun `a name is never longer than a name`() {
        val long = "Набор оправок для выпрессовки подшипников, втулок и сальников, 52 предмета"
        assertTrue(clipName(long).length <= TOPIC_NAME_LIMIT)
        assertEquals("Набор оправок", clipName("Набор оправок"))
    }

    @Test
    fun `an address gives its query back as it was typed`() {
        assertEquals("метчик М16", percentDecode("%D0%BC%D0%B5%D1%82%D1%87%D0%B8%D0%BA+%D0%9C16"))
        assertEquals("100%", percentDecode("100%"))
    }

    @Test
    fun `runs of words stop at punctuation and at dropped words`() {
        assertEquals(
            listOf(listOf("Вакансия", "Tech", "Lead", "Java"), listOf("Москве"), listOf("работа"), listOf("компании", "Цифра")),
            keepRuns("Вакансия Tech Lead Java в Москве, работа в компании Цифра", "hh.ru"),
        )
    }
}
