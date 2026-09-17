package stramus.core.topics

import stramus.core.url.hostOf

/*
 * The level above a collection: what the user was *doing*, read from where they were doing it.
 *
 * The words in the titles give exactly one level — they are what makes "Калина", "М16" and "Набор оправок"
 * three good collections, and they can never make those three one group, because the four topics of an
 * afternoon of car repair share no word at all. A level above needs an abstraction over topics, and there
 * are only two places to get one without a model: the user's own sidebar, and the sites themselves.
 *
 * This is the second. Shops are shopping, code hosts are code, a job board is a job hunt — a coarse,
 * boring claim, and at *this* level that is the right kind of claim: a sidebar group is meant to be broad.
 * It is not the "collection named ozon.ru" mistake wearing a hat: that named a *topic* after a shop, which
 * told the user nothing they could not see; this names the shelf the topic sits on.
 *
 * The one rule that keeps it honest is [NEUTRAL]: a search engine and a chatbot are not a category, they
 * are where the user asked about something that lives elsewhere. Google and Gemini do not vote, or every
 * afternoon of asking questions becomes a group called "Помощники" and the actual subject is lost.
 *
 * The list is maintained by hand and is therefore always a little out of date — which is why an unknown
 * site simply has no group rather than a wrong one, and why the group a collection ends up in is a
 * proposal the user can change on the spot in the plan.
 */

/** The shelves. The words for them belong to the UI, which knows the language; there are none here. */
enum class SiteGroup { SHOPPING, CODE, VIDEO, JOBS, READING, DOCS }

/**
 * Hosts, matched as the tail of a site's address, so one line covers a site's every subdomain and regional
 * domain: "ozon.ru" catches www.ozon.ru, "hh.ru" catches yoshkar-ola.hh.ru, "amazon." catches every
 * country Amazon sells in.
 */
private val SITES: List<Pair<String, SiteGroup>> = listOf(
    // Shops and marketplaces.
    "ozon.ru" to SiteGroup.SHOPPING,
    "wildberries.ru" to SiteGroup.SHOPPING,
    "market.yandex.ru" to SiteGroup.SHOPPING,
    "avito.ru" to SiteGroup.SHOPPING,
    "aliexpress." to SiteGroup.SHOPPING,
    "amazon." to SiteGroup.SHOPPING,
    "ebay." to SiteGroup.SHOPPING,
    "dns-shop.ru" to SiteGroup.SHOPPING,
    "citilink.ru" to SiteGroup.SHOPPING,
    "vseinstrumenti.ru" to SiteGroup.SHOPPING,
    "lamoda.ru" to SiteGroup.SHOPPING,
    "exist.ru" to SiteGroup.SHOPPING,
    "autodoc.ru" to SiteGroup.SHOPPING,
    "emex.ru" to SiteGroup.SHOPPING,
    "leroymerlin.ru" to SiteGroup.SHOPPING,
    "etsy.com" to SiteGroup.SHOPPING,
    // Code.
    "github.com" to SiteGroup.CODE,
    "gitlab.com" to SiteGroup.CODE,
    "bitbucket.org" to SiteGroup.CODE,
    "codeberg.org" to SiteGroup.CODE,
    "stackoverflow.com" to SiteGroup.CODE,
    "npmjs.com" to SiteGroup.CODE,
    "pypi.org" to SiteGroup.CODE,
    "crates.io" to SiteGroup.CODE,
    "maven.org" to SiteGroup.CODE,
    "kotlinlang.org" to SiteGroup.CODE,
    "developer.mozilla.org" to SiteGroup.CODE,
    "developer.chrome.com" to SiteGroup.CODE,
    "jetbrains.com" to SiteGroup.CODE,
    // Video.
    "youtube.com" to SiteGroup.VIDEO,
    "youtu.be" to SiteGroup.VIDEO,
    "rutube.ru" to SiteGroup.VIDEO,
    "vimeo.com" to SiteGroup.VIDEO,
    "twitch.tv" to SiteGroup.VIDEO,
    "kinopoisk.ru" to SiteGroup.VIDEO,
    "ivi.ru" to SiteGroup.VIDEO,
    "netflix.com" to SiteGroup.VIDEO,
    // The job hunt.
    "hh.ru" to SiteGroup.JOBS,
    "career.habr.com" to SiteGroup.JOBS,
    "rabota.sber.ru" to SiteGroup.JOBS,
    "greenhouse.io" to SiteGroup.JOBS,
    "linkedin.com" to SiteGroup.JOBS,
    "getmatch.ru" to SiteGroup.JOBS,
    "superjob.ru" to SiteGroup.JOBS,
    "rabota.ru" to SiteGroup.JOBS,
    // Reading.
    "habr.com" to SiteGroup.READING,
    "medium.com" to SiteGroup.READING,
    "dev.to" to SiteGroup.READING,
    "opennet.ru" to SiteGroup.READING,
    "vc.ru" to SiteGroup.READING,
    "news.ycombinator.com" to SiteGroup.READING,
    "3dnews.ru" to SiteGroup.READING,
    // Documents and storage.
    "docs.google.com" to SiteGroup.DOCS,
    "drive.google.com" to SiteGroup.DOCS,
    "disk.yandex.ru" to SiteGroup.DOCS,
    "docs.yandex.ru" to SiteGroup.DOCS,
    "notion.so" to SiteGroup.DOCS,
    "dropbox.com" to SiteGroup.DOCS,
)

/**
 * Sites that are never a group of their own: a search engine and a chatbot are where a question was asked,
 * and the question was about something kept somewhere else entirely. Half of a car repair happens in a
 * chat window, and filing it under the chat window would be filing it under nothing.
 */
private val NEUTRAL = setOf(
    "ya.ru",
    "duckduckgo.com",
    "html.duckduckgo.com",
    "bing.com",
    "search.brave.com",
    "ecosia.org",
    "startpage.com",
    "kagi.com",
    "search.yahoo.com",
    "gemini.google.com",
    "chatgpt.com",
    "claude.ai",
    "grok.com",
    "x.ai",
    "perplexity.ai",
    "copilot.microsoft.com",
    "you.com",
)

/**
 * The search engines that have a domain per country: "google.de", "yandex.com.tr". Matched at the *front*
 * of the address and not at the end, which is the whole point — "gemini.google.com" and "docs.google.com"
 * end in google.com without being a search engine, and an end-matched list quietly swallowed every Yandex
 * and Google service the user has open. It cost a test to notice, and it would have cost the user their
 * Yandex Disk tabs.
 */
private val NEUTRAL_PREFIXES = listOf("google.", "yandex.")

/** Tabs of one site that no topic wanted are worth a collection only once there are this many. */
private const val SITE_MIN = 4

/**
 * The site's own name, as a collection called after it would be written: "www.ozon.ru" → "Ozon",
 * "market.yandex.ru" → "Yandex", "yoshkar-ola.hh.ru" → "HH".
 *
 * The label before the domain ending, not the whole address: it is the name people use for the place, and
 * it puts a shop's several subdomains under one heading. Two letters or three are an abbreviation and go
 * up in full ("HH", "VK"); anything longer takes a capital and no more — nobody can know from an address
 * that YouTube has a capital T.
 */
fun siteName(url: String): String {
    val labels = hostOf(url).lowercase().split('.').filter { it.isNotEmpty() }
    if (labels.isEmpty()) return hostOf(url)
    val brand = if (labels.size >= 2) labels[labels.size - 2] else labels[0]
    // Under an umbrella the subdomain is the product, and the brand alone is not a collection anybody
    // means: "Google" over Drive, Calendar and the Web Store says only that Google is large. Everywhere
    // else the subdomain is a region or a mirror ("yoshkar-ola.hh.ru"), and the brand is the whole name.
    val service = labels.dropLast(2).lastOrNull()
        ?.takeIf { brand in UMBRELLA_BRANDS && it !in GENERIC_SUBDOMAINS }
    return listOfNotNull(brand, service).joinToString(" ") { label ->
        if (label.length <= 3) label.uppercase() else label.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Brands whose subdomains are separate products rather than the same place in another region. */
private val UMBRELLA_BRANDS = setOf("google", "yandex", "microsoft", "amazon", "apple", "vk", "mail")

/** Subdomains that name nothing: the site itself, or its phone version. */
private val GENERIC_SUBDOMAINS = setOf("www", "m", "mobile", "web", "app")

/**
 * The tabs no topic wanted, gathered by the site they are on — [taken] being the ones already spoken for.
 *
 * The last resort, and a deliberately modest one. Grouping by site is what this whole package exists to
 * avoid *as a way of finding topics*: a site is not a subject, and "ozon.ru" as the name of a collection
 * tells the user something they could see for themselves. But once the words have had their turn, what is
 * left is genuinely miscellaneous, and on a real window that is three quarters of it. A heading that is
 * merely true — these are your YouTube tabs — beats a heap called "not sorted", and nobody is misled by
 * it, because the collection is named after exactly the thing it holds.
 *
 * Only where there are [minTabs] of them: two tabs from one site are two tabs, not a collection. A front
 * page is left out here as everywhere else.
 */
fun siteTopics(tabs: List<TitledTab>, taken: Set<Int>, minTabs: Int = SITE_MIN): List<TitleTopic> =
    tabs.asSequence()
        .filter { it.id !in taken && it.url.isNotBlank() && !isFrontPage(it.url) }
        // A pile of search results and chat sessions is not a collection. [NEUTRAL] sites are where the
        // user asked about something kept elsewhere, and a heading called "Google" over twenty of those
        // says only that they use a search engine.
        .filterNot { isNeutral(hostOf(it.url).lowercase()) }
        .distinctBy { it.url }
        // By the name, not by the address: "market.yandex.ru" and "disk.yandex.ru" are one Yandex to
        // anybody reading a sidebar, and two collections of that name would only be merged by hand later.
        .groupBy { siteName(it.url) }
        .filterValues { it.size >= minTabs }
        .map { (name, group) ->
            TitleTopic(
                title = name,
                tabIds = group.map { it.id },
                terms = emptyList(),
                // Below every topic the words found: a site is the weakest reason to put things together
                // that this package will act on, and the plan is read from the top.
                weight = 0.0,
            )
        }
        .sortedByDescending { it.tabIds.size }

/** Whether [host] is a search engine or a chatbot — see [NEUTRAL] and [NEUTRAL_PREFIXES]. */
private fun isNeutral(host: String): Boolean =
    host in NEUTRAL || NEUTRAL_PREFIXES.any { host.startsWith(it) }

/** The shelf this one page belongs on, or null for a site the list does not know — and for [NEUTRAL] ones. */
fun siteGroupOf(url: String): SiteGroup? {
    val host = hostOf(url).lowercase()
    if (isNeutral(host)) return null
    return SITES.firstOrNull { (site, _) ->
        host == site.trimEnd('.') || host.endsWith(".$site") || host.endsWith(site) || host.contains(".$site")
    }?.second
}

/**
 * The shelf a whole collection belongs on: whichever its pages agree on, the ones with nothing to say
 * ([NEUTRAL] and unknown sites) not counting. Null where none of them knows, which leaves the collection
 * where a collection goes by default — the plan says so, and the user moves it if they disagree.
 */
fun siteGroupFor(urls: List<String>): SiteGroup? =
    urls.mapNotNull { siteGroupOf(it) }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
