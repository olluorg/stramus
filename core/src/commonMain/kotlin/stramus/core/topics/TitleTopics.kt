package stramus.core.topics

import stramus.core.url.hostOf

/*
 * Topics found in what the tabs are *called*, across every site at once.
 *
 * The first attempt at this read the browser instead — which tab opened which, which tab group they are
 * in, which search led there — and on a real window of 158 tabs every one of those was empty: the session
 * had been restored (openers gone), tab groups unused, and only 12 tabs could be traced back to a search.
 * What was left was "three tabs of one site", which names a collection "ozon.ru" and tells the user
 * nothing they could not see themselves.
 *
 * The same window says the answer out loud, though, in the titles: "Набор оправок для выпрессовки
 * подшипников", "подшипник indesit", "Сальник бака стиральной машины" are one repair, and "Ремонт
 * двигателя Лады Калины" in a chatbot, "kalina led фары" on a marketplace and "калина замена топливной
 * трубки" in a search are one car. Both topics cross four sites each, which is exactly what grouping by
 * site — and the model triage's own site-by-site batching — can never see.
 *
 * So: drop the sites' own front pages, strip what a site adds to every title it serves, add what the
 * address says was searched for, and join tabs that share rare words. Rare is the whole of it: "купить" is
 * in forty titles and means nothing, "оправок" is in five and means everything. The word that joins a
 * group also names it, so a name is never invented — it is the user's own word, the one they typed or the
 * one the pages they opened share.
 */

/** A tab as the title grouping reads it: what it is called and where it points. */
data class TitledTab(val id: Int, val title: String, val url: String)

/**
 * A group of tabs that share words, the words themselves, and how strongly they hold together — [weight]
 * is the shared rarity the group was formed on, which is also what orders one group against another.
 */
data class TitleTopic(val title: String, val tabIds: List<Int>, val terms: List<String>, val weight: Double)

/** A group of fewer than this is one tab with a coincidence, not a topic. */
private const val MIN_TABS = 2

/** A collection name is a name: a phrase longer than this is cut back to its words. */
const val TOPIC_NAME_LIMIT = 40

/**
 * A word in more than this share of the whole window is furniture whatever site it came from — and a word
 * in more than [HOST_SHARE] of one site's tabs is that site's own word. A site's word joins every tab of
 * that site and means nothing, which is the "collection named after a shop" this grouping exists to stop.
 *
 * [HOST_MIN] keeps the site rule off a site with only a handful of tabs, and it has to be generous: three
 * job ads from one board share "вакансия", "работа" and "Москве" across every one of that site's tabs, and
 * a rule reading shares alone cannot tell that from a shop writing its slogan everywhere. Below the floor
 * the words stand, and the site's *name* is still barred by the address rule, which needs no counting.
 */
private const val TOO_COMMON = 0.15
private const val HOST_SHARE = 0.6
private const val HOST_MIN = 5

/**
 * The fewest tabs a word may be in and still be dropped as too common. Without a floor, [TOO_COMMON] is
 * brutal in a small window: in ten tabs, 15% rounds down to one, so nothing shared by three tabs could
 * ever join them and a modest window came back as nothing but pairs.
 */
private const val SMALL_WINDOW_CEILING = 4

/**
 * How many words two tabs must share to be a topic *between the two of them*. One is a coincidence —
 * "Думал, что строю бизнес" and "Тупее Чем Вы Думали" share a verb, a LED headlight and an LED lamp share
 * three letters — and a group of two is where a single shared word does the most damage, there being
 * nothing else in it to outvote the accident. Three tabs holding one word is already a pattern.
 *
 * Unless the word is one the user *typed*: two tabs whose addresses both carry "м16" in the search box are
 * two halves of one errand, however little else they have in common. Nobody types a word by accident, and
 * that is the difference between "м16" and the "думал" two video titles happened to share.
 */
private const val PAIR_TERMS = 2

/** A word shorter than this is noise in both languages; "м16" and "18v" are exactly at the line. */
private const val MIN_TERM = 3

/** How much of a tab's own weight it must share with a group to join it. */
private const val JOIN_SHARE = 0.34

/**
 * The words that say nothing about a topic: what a shop, a video site or a search page writes into every
 * title it has. Kept deliberately short — the two frequency rules above remove most of this on their own,
 * and a list like this goes stale the moment a site changes its wording. What is here is only what is
 * common enough to hurt in a *small* window, where nothing is frequent enough to be cut by frequency.
 */
private val STOP_WORDS = setOf(
    // Russian: shopping, search and site chrome.
    "купить", "цена", "цены", "цене", "низкой", "интернет", "магазин", "магазине", "официальный", "онлайн",
    "доставка", "доставкой", "отзывы", "каталог", "товары", "товаров", "заказ", "заказы", "корзина",
    "поиск", "найти", "смотреть", "скачать", "бесплатно", "лучшие", "новый", "новые", "все", "для",
    "как", "что", "это", "или", "если", "при", "над", "под", "без", "его", "год", "года", "лет",
    "руб", "шт", "com", "www", "https", "http",
    // English.
    "the", "and", "for", "with", "from", "how", "what", "your", "you", "are", "was", "new", "top",
    "best", "free", "online", "buy", "price", "shop", "store", "search", "results", "home", "page",
    "official", "site", "web", "app", "download", "watch", "video", "review", "reviews",
)

/** The separators a site puts between what the page is and whose page it is. */
private val TITLE_TAIL = Regex("\\s*[-—–|·/]\\s*[^-—–|·/]{1,40}$")

/** An unread counter a site writes into its own title: "(35) Something". */
private val LEADING_COUNTER = Regex("^\\(\\d+\\)\\s*")

/** A shop's own article number, always in brackets at the very end: "… (1864224795)". */
private val TRAILING_ID = Regex("\\s*\\((\\d{6,})\\)\\s*$")

/**
 * What a site calls the box the user typed in — read from the address, whichever site it is.
 *
 * Only names that can mean nothing else. "p" and "s" were here and had to go: on most of the web they are
 * a page number, a section, or the region the shop thinks you are in, and reading those as a search put
 * "Москва" into the words of a bottle of hydraulic oil — which then shared a topic with a job ad in Moscow.
 */
private val QUERY_PARAMS = setOf("q", "text", "query", "search_query", "searchtext", "search", "keyword", "kw", "wd")

/**
 * One tab, read.
 *
 * [runs] are its words as they were written, in runs of words that really were *next to each other* —
 * every dropped word breaks the run. That is what a name is built from, and the reason it is not one flat
 * list: reading neighbours off the filtered list glued "в Москве, работа в компании" into the pair
 * "Москве работа", a phrase nobody wrote and no reader recognises.
 *
 * [typedPhrase] is what the address says was typed into the site's own search box — the user's own words,
 * in the form they chose, which is the best name a group can have.
 */
private class TabWords(
    val id: Int,
    val host: String,
    val runs: List<List<String>>,
    val typedPhrase: String?,
    val askedFor: Set<String>,
) {
    val written: List<String> = runs.flatten()
    val stems: List<String> = written.map { stem(it.lowercase()) }
    val distinct: Set<String> = stems.toSet()
}

/** Everything one tab says: its title, cleaned, and then what its address says was searched for. */
private fun readTab(tab: TitledTab): TabWords {
    val host = hostOf(tab.url).lowercase()
    val typed = siteQueryOf(tab.url)
    val fromTitle = keepRuns(cleanTitle(tab.title, tab.url), host)
    val fromQuery = keepRuns(typed.orEmpty(), host)
    return TabWords(
        id = tab.id,
        host = host,
        runs = fromTitle + fromQuery,
        typedPhrase = typed?.takeIf { fromQuery.isNotEmpty() },
        askedFor = fromQuery.flatten().map { stem(it.lowercase()) }.toSet(),
    )
}

/**
 * [tabs] gathered into topics by the words their titles share, strongest first.
 *
 * Every tab is reduced to its words — from the title, with the site's furniture stripped off, and from
 * whatever the address says was searched for. A word is dropped when it belongs to the window as a whole
 * ([TOO_COMMON]) or to one site ([HOST_SHARE]); what is left is weighted by how few tabs hold it.
 *
 * [tooCommon], [joinShare] and [pairTerms] are the same three thresholds the constants below describe,
 * loosened or tightened by a caller that wants a coarser or finer reading of the same tabs: broad buckets
 * for the sidebar's groups, the default for collections, a strict pass for the dividers inside one.
 *
 * Groups are then grown one at a time, around the heaviest word that still joins at least [MIN_TABS] tabs,
 * and a tab joins if [joinShare] of its own weight is in the group's words. Growing around a word rather
 * than merging pairs is what keeps a group explainable: there is always an answer to "why are these
 * together", and it is on the group.
 *
 * A tab left in no group is not in the result at all; the caller shows it as unsorted.
 */
fun titleTopics(
    tabs: List<TitledTab>,
    tooCommon: Double = TOO_COMMON,
    joinShare: Double = JOIN_SHARE,
    pairTerms: Int = PAIR_TERMS,
): List<TitleTopic> {
    val live = tabs.filter { it.url.isNotBlank() && !isFrontPage(it.url) }.distinctBy { it.url }
    if (live.size < MIN_TABS) return emptyList()

    val read = live.map { readTab(it) }
    val byId = read.associateBy { it.id }
    val tabsPerHost = read.groupingBy { it.host }.eachCount()
    val holders = mutableMapOf<String, MutableSet<Int>>()
    read.forEach { tab -> tab.distinct.forEach { holders.getOrPut(it) { mutableSetOf() } += tab.id } }

    val ceiling = (live.size * tooCommon).toInt().coerceAtLeast(SMALL_WINDOW_CEILING)
    val weightOf = holders
        .filterValues { it.size in MIN_TABS..ceiling }
        .filterNot { (_, ids) -> ownedByOneSite(ids, byId, tabsPerHost) }
        .mapValues { (_, ids) -> 1.0 + kotlin.math.ln(live.size.toDouble() / ids.size) }

    val remaining = read.associate { tab -> tab.id to tab.distinct.filter { it in weightOf } }.toMutableMap()
    val topics = mutableListOf<TitleTopic>()

    while (true) {
        // The heaviest word still joining enough tabs: its rarity times how many tabs it would gather.
        val seed = weightOf.keys
            .map { stem -> stem to remaining.count { (_, stems) -> stem in stems } }
            .filter { it.second >= MIN_TABS }
            .maxByOrNull { (stem, holding) -> weightOf.getValue(stem) * holding }
            ?.first
            ?: break

        val core = remaining.filterValues { seed in it }.keys.toMutableSet()
        val terms = core.flatMap { remaining.getValue(it) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= MIN_TABS }
            .keys
            .toMutableSet()
        terms += seed

        // Tabs that did not hold the seed word but hold enough of the group's other words.
        remaining.forEach { (id, stems) ->
            if (id in core || stems.isEmpty()) return@forEach
            val own = stems.sumOf { weightOf.getValue(it) }
            val shared = stems.filter { it in terms }.sumOf { weightOf.getValue(it) }
            if (own > 0 && shared / own >= joinShare) core += id
        }

        core.forEach { remaining.remove(it) }
        if (core.size < MIN_TABS) continue
        val members = core.map { byId.getValue(it) }
        // Three tabs of one page ("VK Почта" three times) are one page, not a topic. The duplicates are
        // dealt with where duplicates belong — the triage window's own pre-step.
        if (members.map { it.written.joinToString(" ").lowercase() }.distinct().size < MIN_TABS) continue
        // Two tabs holding one word between them — see [PAIR_TERMS], and the exception for a word both
        // of them were found by typing.
        if (members.size == MIN_TABS) {
            val between = members[0].distinct.filter { it in members[1].distinct && it in weightOf }
            val typed = between.any { it in members[0].askedFor && it in members[1].askedFor }
            if (!typed && between.size < pairTerms) continue
        }

        val named = terms.filter { stem -> members.count { stem in it.distinct } >= members.size / 2 }
            .sortedByDescending { weightOf.getValue(it) }
            .ifEmpty { listOf(seed) }
        topics += TitleTopic(
            title = nameOf(members, named.toSet()),
            tabIds = live.filter { it.id in core }.map { it.id },
            terms = named.take(3),
            weight = named.take(2).sumOf { weightOf.getValue(it) } * core.size,
        )
    }
    return topics.sortedByDescending { it.weight }
}

/**
 * Whether the tabs holding a word are so nearly all of one site's tabs that the word is the site's own —
 * its name, its wording, the heading it puts on every page. Only for a site with [HOST_MIN] tabs or more:
 * below that, "most of this site's tabs" is one tab.
 */
private fun ownedByOneSite(ids: Set<Int>, byId: Map<Int, TabWords>, tabsPerHost: Map<String, Int>): Boolean {
    val (host, held) = ids.groupingBy { byId.getValue(it).host }.eachCount().maxByOrNull { it.value } ?: return false
    val onThatHost = tabsPerHost[host] ?: return false
    return onThatHost >= HOST_MIN && held.toDouble() / onThatHost >= HOST_SHARE
}

/**
 * What the group is called, in order of how much the user had to do with it:
 *
 * 1. Something they typed. A search phrase is the topic in their own words and in the form they wrote it —
 *    "набор оправок", "системный дизайн" — where every phrase lifted out of a page title arrives in
 *    whatever case the sentence around it needed ("Москве работа").
 * 2. The pair of neighbouring words that turns up in most of the group's titles — "System Design",
 *    "Блок питания" — neighbouring in the title as *written*, not in the filtered list of words.
 * 3. The heaviest single word, in the spelling it was most often written in.
 */
private fun nameOf(members: List<TabWords>, terms: Set<String>): String {
    typedName(members, terms)?.let { return it }

    val pairs = mutableMapOf<String, MutableMap<String, Int>>()
    members.forEach { tab ->
        tab.runs.forEach { run ->
            run.zipWithNext().forEach { (first, second) ->
                if (stem(first.lowercase()) !in terms && stem(second.lowercase()) !in terms) return@forEach
                // "kormium/kormium" is one word said twice, not a name made of two.
                if (first.equals(second, ignoreCase = true)) return@forEach
                // Counted by hand rather than with `merge`, which is the JVM's own and does not exist
                // where this actually runs — common code compiles to JS too.
                val written = "$first $second"
                val spellings = pairs.getOrPut(written.lowercase()) { mutableMapOf() }
                spellings[written] = (spellings[written] ?: 0) + 1
            }
        }
    }
    val best = pairs.filterValues { spellings -> spellings.values.sum() >= MIN_TABS }
        .maxByOrNull { (_, spellings) -> spellings.values.sum() }
        ?.value
        ?.maxByOrNull { it.value }
        ?.key
    if (best != null) return clipName(best.replaceFirstChar { it.uppercaseChar() })

    val surface = mutableMapOf<String, MutableMap<String, Int>>()
    members.forEach { tab ->
        tab.written.forEachIndexed { i, word ->
            val spellings = surface.getOrPut(tab.stems[i]) { mutableMapOf() }
            spellings[word] = (spellings[word] ?: 0) + 1
        }
    }
    // The word to call the group by is the one most of it holds — not the rarest one, which is all that
    // weight measures: "вакансия" runs through all three job ads, "java" is in two of them and names the
    // third wrongly. Of words held equally often, the longer is the more telling: "kormium", not "issue".
    //
    // And the candidates are not only the words the group was *joined* by. A word the site writes into
    // most of its pages cannot join anything — it would gather the whole site (see [HOST_SHARE]) — but it
    // can still say what the group is: "вакансия" is every job board's word, and it is also exactly what
    // these three tabs are. The site's *name* stays barred, by the same rule as everywhere else: a word
    // that is part of the address is never a topic, however often it is written.
    val shared = members.map { it.distinct }.reduce { held, next -> held intersect next }
    // Held by most; then the word the titles *open* with; then the longest.
    //
    // The middle test is what tells "вакансия" from "компания" — both in all three job ads, and the
    // longer of them is the wrong one. A page names its subject first and qualifies it afterwards
    // ("Вакансия Tech Lead Java в Москве, работа в компании Цифра"), so the opening word is the subject.
    // It is asked as "opens with", not "appears early": "Add FOR UPDATE, NOWAIT, and SKIP LOCKED support
    // … · Issue #162 · kormium/kormium" puts "locked" before "kormium" while being about neither of the
    // first two words, and there the question simply finds nothing and length settles it.
    fun opensWith(word: String): Int = members.count { it.stems.firstOrNull() == word }
    val stem = (terms + shared)
        .filterNot { word -> members.any { namesSite(word, it.host) } }
        .maxWithOrNull(
            compareBy<String> { s -> members.count { s in it.distinct } }
                .thenBy { opensWith(it) }
                .thenBy { it.length },
        )
        ?: return ""
    val word = surface[stem]?.maxByOrNull { it.value }?.key ?: stem
    return clipName(word.replaceFirstChar { it.uppercaseChar() })
}

/**
 * The name taken from what the user typed, where they typed anything that has to do with why these tabs
 * are together: the phrase that turns up most among the group's searches, and — of equally common ones —
 * whichever says most about the group, that is, holds most of the words the group was joined by. A search
 * that shares none of those words is somebody's unrelated errand in the same window, and names nothing.
 */
private fun typedName(members: List<TabWords>, terms: Set<String>): String? {
    val phrases = members.mapNotNull { it.typedPhrase }.filter { phrase ->
        phrase.split(Regex("[^\\p{L}\\p{Nd}]+")).any { stem(it.lowercase()) in terms }
    }
    if (phrases.isEmpty()) return null
    val best = phrases.groupBy { it.trim().lowercase() }
        .maxWithOrNull(
            compareBy<Map.Entry<String, List<String>>> { it.value.size }
                .thenBy { entry ->
                    entry.key.split(Regex("[^\\p{L}\\p{Nd}]+")).count { stem(it.lowercase()) in terms }
                }
                .thenBy { -it.key.length },
        )
        ?.value
        ?.first()
        ?: return null
    return clipName(best.trim().replaceFirstChar { it.uppercaseChar() })
}

/**
 * Whether [url] is a site's own front door — "ozon.ru", "youtube.com", "github.com" with nothing after it.
 *
 * Those are dropped before anything is counted, and it is the single most useful thing done here. Such a
 * tab is titled with the site's own advertisement ("OZON маркетплейс – миллионы товаров по выгодным
 * ценам"), a window holds eight of them, and every one of those titles votes for the shop's own words
 * being a topic. They are also the tabs nobody wants saved: a front page is one keystroke away.
 *
 * The parameters are cut before the path is judged — "hh.ru/?hhtmFromLabel=header" is the front page
 * however much tracking is stapled to it. But a site whose search *is* its root ("duckduckgo.com/?q=…")
 * is not a front page when something was actually searched for there.
 */
internal fun isFrontPage(url: String): Boolean {
    val afterProto = if ("://" in url) url.substringAfter("://") else url
    val path = afterProto.substringAfter('/', "").substringBefore('#').substringBefore('?').trim('/')
    return path.isEmpty() && siteQueryOf(url) == null
}

/**
 * The words of [text] worth keeping for a page on [host] — long enough, not furniture, not the site's own
 * name — in runs of words that were next to each other in [text]. A dropped word ends the run, so no pair
 * of words that the user never wrote side by side can ever be read back out of this.
 */
internal fun keepRuns(text: String, host: String): List<List<String>> {
    val runs = mutableListOf<List<String>>()
    var run = mutableListOf<String>()
    fun endRun() {
        if (run.isNotEmpty()) {
            runs += run
            run = mutableListOf()
        }
    }
    // Punctuation ends a run as surely as a dropped word does. "Вакансия Tech Lead Java в Москве, работа
    // в компании Цифра" puts "Москве" next to "работа" only for a reader who counts a comma as nothing,
    // and the pair that comes back out of that — "Москве работа" — is a phrase nobody wrote.
    text.split(Regex("[^\\p{L}\\p{Nd}\\s]+")).forEach { segment ->
        segment.split(Regex("\\s+")).forEach { word ->
            val lower = word.lowercase()
            val keep = word.length >= MIN_TERM &&
                lower !in STOP_WORDS &&
                // A word that is the site's own name is never a topic, wherever in the title it turns up.
                !namesSite(lower, host) &&
                // A long run of digits is an article number, an id, a year range — never a topic.
                !(word.all { it.isDigit() } && word.length > 4)
            if (keep) run += word else endRun()
        }
        endRun()
    }
    endRun()
    return runs
}

/**
 * Whether [word] is the name of the site at [host] — "ozon" on ozon.ru, and "яндекс" on yandex.ru too.
 *
 * The second is the whole reason this is not a substring test. A Russian site writes its name in Russian
 * and its address in English, so "яндекс" matched no host at all and went on to join a shopping list to a
 * spreadsheet and a cloud drive, all three being Yandex and nothing else. Transliterated, the word and the
 * address meet: what is compared is a prefix, since the transliteration of a name and the spelling its
 * owner registered rarely agree to the last letter ("yandeks" against "yandex").
 */
internal fun namesSite(word: String, host: String): Boolean {
    if (word.length < MIN_TERM) return false
    if (word in host) return true
    val latin = transliterate(word)
    if (latin.length < 4) return false
    val prefix = latin.take(5)
    return host.split('.', '-').any { label ->
        label.length >= 4 && (label.startsWith(prefix.take(minOf(prefix.length, label.length))) || latin in label)
    }
}

/** Cyrillic as the owner of a domain would most likely have spelled it in the address. */
private val CYRILLIC_LATIN = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
    'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
    'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
    'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
    'я' to "ya",
)

/** [word] in Latin letters, so a name written in one alphabet can be compared with an address in another. */
internal fun transliterate(word: String): String =
    word.lowercase().map { CYRILLIC_LATIN[it] ?: it.toString() }.joinToString("")

/** The title without the counter in front, the article number behind, or the site's name at the end. */
internal fun cleanTitle(title: String, url: String): String {
    val host = hostOf(url).substringBefore('.').lowercase()
    var text = title.replace(LEADING_COUNTER, "").replace(TRAILING_ID, "").trim()
    // Only when the tail really is the site talking about itself: cutting the last few words off every
    // title would take the topic with it as often as not.
    val tail = TITLE_TAIL.find(text)?.value.orEmpty().lowercase()
    if (tail.isNotEmpty() && host.isNotEmpty() && host in tail.replace(" ", "")) {
        text = text.removeRange(text.length - tail.length, text.length).trim()
    }
    return text
}

/** What was searched for on the site itself, read out of the address — any site, not only a search engine. */
internal fun siteQueryOf(url: String): String? {
    val afterProto = if ("://" in url) url.substringAfter("://") else url
    val query = afterProto.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    val found = query.split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=').lowercase()
        if ('=' in pair && name in QUERY_PARAMS) percentDecode(pair.substringAfter('=')) else null
    }
    // A search is words. A value of nothing but digits and dashes is an id, a page number or a date, and
    // whatever it is doing in that parameter, nobody typed it looking for something.
    return found.firstOrNull { it.length in 2..80 && it.any { c -> c.isLetter() } }
}

/** Russian and English endings, cut back to a stem two words of one root can meet at. */
private val ENDINGS = listOf(
    "ами", "ями", "ого", "ему", "ому", "ыми", "ими", "ей", "ой", "ый", "ий", "ая", "яя", "ое", "ее",
    "ов", "ев", "ам", "ям", "ах", "ях", "ую", "юю", "ом", "ем", "ие", "ые", "ья", "ию", "ия",
    "ing", "ed", "es", "s", "а", "я", "ы", "и", "у", "ю", "е", "о", "ь", "й",
)

/** [word] with its ending taken off, while at least four letters are left to recognise it by. */
internal fun stem(word: String): String {
    if (word.length <= 4) return word
    val ending = ENDINGS.firstOrNull { word.endsWith(it) && word.length - it.length >= 4 } ?: return word
    return word.dropLast(ending.length)
}

/** [text] cut back to [TOPIC_NAME_LIMIT] at a word boundary where there is one. */
internal fun clipName(text: String): String {
    if (text.length <= TOPIC_NAME_LIMIT) return text
    val cut = text.take(TOPIC_NAME_LIMIT)
    val space = cut.lastIndexOf(' ')
    return (if (space >= TOPIC_NAME_LIMIT / 2) cut.take(space) else cut).trimEnd(' ', ',', '.', ':', '-')
}

/** `%D0%BA` and `+` back into the text they stand for — UTF-8, as every site encodes a search box. */
internal fun percentDecode(text: String): String {
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '+' -> {
                bytes += ' '.code.toByte()
                i++
            }
            c == '%' && i + 2 < text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
                bytes += text.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            }
            else -> {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
