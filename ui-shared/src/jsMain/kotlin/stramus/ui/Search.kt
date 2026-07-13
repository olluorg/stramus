@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.Collection
import stramus.core.platform.CapturedTab
import stramus.core.platform.HistoryEntry
import stramus.core.repo.UsageStat
import kotlin.math.ln
import kotlin.uuid.ExperimentalUuidApi

/** How many rows the dropdown offers at most, actions aside. Beyond that it is a list, not a choice. */
private const val MAX_HITS = 8

/** Top sites shown when nothing has been typed yet. */
private const val MAX_TOP_SITES = 6

/**
 * How many rows each source may take of [MAX_HITS] before the rest is filled by score alone. Without
 * this a query matching thirty visited pages would push every open tab and every saved card off a
 * list of eight — and the pages the user *kept* are the ones they meant.
 */
private val QUOTAS = mapOf(
    HitSource.TABS to 2,
    HitSource.CARDS to 3,
    HitSource.HISTORY to 3,
    HitSource.COLLECTIONS to 2,
    HitSource.SITES to 2,
)

/** Query parameters that identify a campaign, not a page: two links differing only in these are one. */
private val TRACKING_PARAMS = listOf("utm_", "fbclid", "gclid", "yclid", "msclkid", "mc_eid", "_hsenc")

/** Where a plain query goes when the user just wants the web. */
internal fun webSearchUrl(query: String): String =
    "https://www.google.com/search?q=${encodeURIComponent(query)}"

/**
 * The one identity of a page: lowercase host without `www.`, no scheme, no fragment, no trailing
 * slash, no tracking parameters. It is the key of the usage table and how a card, an open tab and a
 * visited page are recognised as the same thing — so it must not depend on which of the three the
 * link happened to arrive from.
 *
 * The path keeps its case: a host is case-insensitive, a path very often is not.
 */
internal fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim().substringBefore('#')
    if (trimmed.isBlank()) return ""
    val afterScheme = if ("://" in trimmed) trimmed.substringAfter("://") else trimmed
    val path = afterScheme.substringBefore('?')
    val query = afterScheme.substringAfter('?', "")

    val slash = path.indexOf('/')
    val host = (if (slash < 0) path else path.take(slash)).lowercase().removePrefix("www.")
    val rest = (if (slash < 0) "" else path.substring(slash)).trimEnd('/')
    val keptParams = query.split('&')
        .filter { param -> param.isNotBlank() && TRACKING_PARAMS.none { param.startsWith(it) } }

    return buildString {
        append(host)
        append(rest)
        if (keptParams.isNotEmpty()) {
            append('?')
            append(keptParams.joinToString("&"))
        }
    }
}

/**
 * Whether what was typed is an address rather than a search: a scheme, or something shaped like a
 * host — one word, a dot in the middle of it, no spaces. "kotlin.coroutines" is not a host in any
 * meaningful sense, but neither is it a plausible search; guessing "open it" for anything dotted is
 * how browsers have always done it, and the web-search row is right underneath either way.
 */
internal fun looksLikeUrl(query: String): Boolean {
    val q = query.trim()
    if (q.isBlank() || ' ' in q) return false
    if (q.startsWith("http://") || q.startsWith("https://")) return true
    if (q.startsWith("localhost")) return true
    val host = q.substringBefore('/').substringBefore('?')
    val dot = host.indexOf('.')
    return dot > 0 && dot < host.length - 1 && !host.endsWith(".")
}

/** What [looksLikeUrl] typed into the box actually opens. */
internal fun asUrl(query: String): String {
    val q = query.trim()
    return if ("://" in q) q else "https://$q"
}

/** The kind of thing a row stands for — its glyph, and which group it lands in. */
enum class HitSource { ACTION, TABS, CARDS, HISTORY, COLLECTIONS, SITES }

/**
 * One row of the search dropdown. Everything the box can offer is one of these: something already
 * open, something saved, something visited, somewhere to go, or something to ask.
 *
 * [score] is what orders them (see [scoreOf]); [key] is both the React key and the identity the
 * keyboard selection is held by, so a row that survives a re-render keeps the selection.
 */
sealed interface Hit {
    val key: String
    val title: String
    val subtitle: String
    val score: Double

    /** The page behind the row, if there is one: what the favicon is drawn from, and what usage counts. */
    val url: String?
        get() = null
}

/** A tab already open in the browser: activating the row jumps to it rather than opening it again. */
data class TabHit(val tab: CapturedTab, override val score: Double) : Hit {
    override val key = "tab:${tab.id}"
    override val title = tab.title.ifBlank { hostOf(tab.url) }
    override val subtitle = hostOf(tab.url)
    override val url = tab.url
}

/** A saved card. [collectionTitle] is where it lives — which is most of what the user needs to place it. */
data class CardHit(
    val card: Card,
    val collectionTitle: String,
    override val score: Double,
) : Hit {
    override val key = "card:${card.id}"
    override val title = card.title
    override val subtitle = collectionTitle
    override val url = card.url.takeIf { card.kind == CardKind.LINK }
}

/** A page from the browser's history (extension only). */
data class HistoryHit(val entry: HistoryEntry, override val score: Double) : Hit {
    override val key = "history:${entry.url}"
    override val title = entry.title.ifBlank { hostOf(entry.url) }
    override val subtitle = hostOf(entry.url)
    override val url = entry.url
}

/** A page from the user's own usage: often opened, but no longer a tab and perhaps never a card. */
data class SiteHit(val stat: UsageStat, override val score: Double) : Hit {
    override val key = "site:${stat.url}"
    override val title = stat.title
    override val subtitle = stat.host
    override val url = stat.url
}

/** A collection: the row selects it in the sidebar instead of opening anything. */
data class CollectionHit(val collection: Collection, override val score: Double) : Hit {
    override val key = "collection:${collection.id}"
    override val title = collection.title
    override val subtitle = ""
}

/** The address the user typed: open it. Offered first, since nothing else can be what they meant. */
data class OpenUrlHit(val target: String, val query: String) : Hit {
    override val key = "open:$target"
    override val title = query
    override val subtitle = hostOf(target)
    override val score = 0.0
    override val url = target
}

/** The web. Always offered, never first unless nothing else matched. */
data class WebSearchHit(val query: String) : Hit {
    override val key = "web:$query"
    override val title = query
    override val subtitle = ""
    override val score = 0.0
}

/** The browser's built-in model. Offered only where it is actually available. */
data class AiHit(val query: String) : Hit {
    override val key = "ai:$query"
    override val title = query
    override val subtitle = ""
    override val score = 0.0
}

/** One block of the dropdown: a heading (or none, for the action rows) and its rows. */
data class HitGroup(val source: HitSource, val label: String, val hits: List<Hit>)

/**
 * How well [query] matches a thing called [title] at [url], with [body] standing in for a note's text.
 *
 * The order is what a person means by "matches": the host they typed outright, then the host they
 * started typing, then the title they started typing, then a word of it, then merely a mention of it
 * somewhere. Zero means no match at all, and the candidate is dropped — a search box that answers
 * everything answers nothing.
 */
private fun matchScore(query: String, title: String, url: String, body: String? = null): Double {
    val q = query.lowercase()
    val name = title.lowercase()
    val host = hostOf(url).lowercase()
    val address = url.lowercase()
    val tokens = q.split(' ').filter { it.isNotBlank() }

    return when {
        host == q -> 100.0
        host.startsWith(q) -> 90.0
        name.startsWith(q) -> 80.0
        name.split(' ', '-', '_', '/', '.', ':', ',', '(', '[').any { it.startsWith(q) } -> 65.0
        q in name -> 50.0
        // Several words, all of them there but not side by side: "kotlin flow" finding "Flow — Kotlin
        // docs". Worth less than a phrase match, worth much more than nothing.
        tokens.size > 1 && tokens.all { it in name || it in address } -> 45.0
        q in address -> 40.0
        body != null && q in body.lowercase() -> 25.0
        else -> 0.0
    }
}

/**
 * The final rank of a candidate: how well it matches, how much the user uses it, and what kind of
 * thing it is. Frecency enters through a logarithm — the difference between a page opened once and one
 * opened twenty times matters, the difference between two hundred and four hundred does not — so that
 * a much-used page can lift itself past a better-worded match, but never past a far better one.
 */
private fun scoreOf(match: Double, frecency: Double, bias: Double): Double =
    match + 12.0 * ln(1.0 + frecency) + bias

/** A tab is already open (switching to it is the cheapest thing the box can do); a card the user chose to keep. */
private fun biasOf(source: HitSource): Double = when (source) {
    HitSource.TABS -> 12.0
    HitSource.CARDS -> 8.0
    HitSource.COLLECTIONS -> 4.0
    else -> 0.0
}

/** The browser's own opinion of how much a page is used: visits, and addresses typed out by hand. */
private fun browserFrecency(entry: HistoryEntry): Double = 0.5 * entry.visitCount + 2.0 * entry.typedCount

/**
 * Everything the box offers for [query], grouped and ordered.
 *
 * The rules, in the order they are applied:
 *  - each source is matched and scored on its own;
 *  - the same page from two sources is one row — the open tab beats the saved card beats the visited
 *    page beats the bare usage record, because that is the order of what the user can *do* with it;
 *  - each source gets a quota of the eight rows, and what is left over goes to the best scores;
 *  - a group is placed by its best row, so whatever scored highest overall is the first row of the
 *    list — which is what Enter takes;
 *  - the actions (open this address, search the web, ask the model) come last, except an address,
 *    which comes first: someone who typed a URL meant the URL.
 *
 * An empty query is not a blank dropdown but the user's top sites — the whole point of the box being
 * on a new tab page.
 */
internal fun buildHits(
    query: String,
    tabs: List<CapturedTab>,
    cards: List<Card>,
    history: List<HistoryEntry>,
    collections: List<Collection>,
    collectionTitles: Map<String, String>,
    aiAvailable: Boolean,
    strings: Strings,
): List<HitGroup> {
    val q = query.trim()
    if (q.isBlank()) {
        val sites = topSites(MAX_TOP_SITES).map { SiteHit(it, frecencyOf(it.url)) }
        return if (sites.isEmpty()) emptyList() else listOf(HitGroup(HitSource.SITES, strings.hitsTopSites, sites))
    }

    val tabHits = tabs.mapNotNull { tab ->
        val match = matchScore(q, tab.title, tab.url)
        if (match <= 0.0) null else TabHit(tab, scoreOf(match, frecencyOf(tab.url), biasOf(HitSource.TABS)))
    }
    val cardHits = cards.mapNotNull { card ->
        val match = matchScore(q, card.title, card.url, card.content)
        if (match <= 0.0) {
            null
        } else {
            CardHit(
                card = card,
                collectionTitle = collectionTitles[card.collectionId.toString()].orEmpty(),
                score = scoreOf(match, frecencyOf(card.url), biasOf(HitSource.CARDS)),
            )
        }
    }
    val historyHits = history.mapNotNull { entry ->
        val match = matchScore(q, entry.title, entry.url)
        if (match <= 0.0) {
            null
        } else {
            HistoryHit(entry, scoreOf(match, frecencyOf(entry.url) + browserFrecency(entry), 0.0))
        }
    }
    val siteHits = topSites(Int.MAX_VALUE).mapNotNull { stat ->
        val match = matchScore(q, stat.title, stat.url)
        if (match <= 0.0) null else SiteHit(stat, scoreOf(match, frecencyOf(stat.url), 0.0))
    }
    val collectionHits = collections.mapNotNull { collection ->
        val match = matchScore(q, collection.title, "")
        if (match <= 0.0) null else CollectionHit(collection, scoreOf(match, 0.0, biasOf(HitSource.COLLECTIONS)))
    }

    // One page, one row. The lists are walked in the order of what the user can do with the page, so
    // the first one to claim a URL keeps it.
    val claimed = mutableSetOf<String>()
    fun <T : Hit> dedup(hits: List<T>): List<T> = hits
        .sortedByDescending { it.score }
        .filter { hit ->
            val key = hit.url?.let { normalizeUrl(it) }
            key == null || key.isBlank() || claimed.add(key)
        }

    val bySource = mapOf(
        HitSource.TABS to dedup(tabHits),
        HitSource.CARDS to dedup(cardHits),
        HitSource.HISTORY to dedup(historyHits),
        HitSource.SITES to dedup(siteHits),
        HitSource.COLLECTIONS to dedup(collectionHits),
    )

    // Quotas first, then the best of whatever is left until the list is full.
    val taken = bySource.mapValues { (source, hits) -> hits.take(QUOTAS[source] ?: 0).toMutableList() }
    val room = MAX_HITS - taken.values.sumOf { it.size }
    if (room > 0) {
        val spare = bySource.flatMap { (source, hits) -> hits.drop(taken.getValue(source).size).map { source to it } }
        spare.sortedByDescending { (_, hit) -> hit.score }.take(room).forEach { (source, hit) ->
            taken.getValue(source) += hit
        }
    }

    val labels = mapOf(
        HitSource.TABS to strings.hitsTabs,
        HitSource.CARDS to strings.hitsCards,
        HitSource.HISTORY to strings.hitsHistory,
        HitSource.SITES to strings.hitsSites,
        HitSource.COLLECTIONS to strings.hitsCollections,
    )
    val found = taken
        .filterValues { it.isNotEmpty() }
        .map { (source, hits) -> HitGroup(source, labels.getValue(source), hits.sortedByDescending { it.score }) }
        // The best row overall is the first row of the list, so Enter always takes the best answer.
        .sortedByDescending { group -> group.hits.first().score }

    val actions = buildList<Hit> {
        add(WebSearchHit(q))
        if (aiAvailable) add(AiHit(q))
    }
    val address = if (looksLikeUrl(q)) listOf(HitGroup(HitSource.ACTION, "", listOf(OpenUrlHit(asUrl(q), q)))) else emptyList()

    return address + found + HitGroup(HitSource.ACTION, "", actions)
}
