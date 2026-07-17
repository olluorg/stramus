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
import stramus.core.url.hostOf

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
 * What taking a row actually does. This is the unit the box learns the user's habits in ([habitShareOf]):
 * someone who asks the model ten times a day is asking it again, and someone who never has is not, so
 * the rows are ranked by which of these the user keeps coming back to — see [habitBias].
 *
 * [id] is what the count is stored under, so these names outlive a rename of the enum.
 */
enum class HitAction(val id: String) {
    SWITCH_TAB("tab"),
    OPEN_CARD("card"),
    OPEN_HISTORY("history"),
    OPEN_SITE("site"),
    OPEN_COLLECTION("collection"),
    OPEN_URL("url"),
    WEB_SEARCH("web"),
    ASK_AI("ai"),
}

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

    /** What Enter on this row does — and what the box counts, so that it learns to offer it sooner. */
    val action: HitAction

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
    override val action = HitAction.SWITCH_TAB
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
    override val action = HitAction.OPEN_CARD
}

/** A page from the browser's history (extension only). */
data class HistoryHit(val entry: HistoryEntry, override val score: Double) : Hit {
    override val key = "history:${entry.url}"
    override val title = entry.title.ifBlank { hostOf(entry.url) }
    override val subtitle = hostOf(entry.url)
    override val url = entry.url
    override val action = HitAction.OPEN_HISTORY
}

/** A page from the user's own usage: often opened, but no longer a tab and perhaps never a card. */
data class SiteHit(val stat: UsageStat, override val score: Double) : Hit {
    override val key = "site:${stat.url}"
    override val title = stat.title
    override val subtitle = stat.host
    override val url = stat.url
    override val action = HitAction.OPEN_SITE
}

/** A collection: the row selects it in the sidebar instead of opening anything. */
data class CollectionHit(val collection: Collection, override val score: Double) : Hit {
    override val key = "collection:${collection.id}"
    override val title = collection.title
    override val subtitle = ""
    override val action = HitAction.OPEN_COLLECTION
}

/** The address the user typed: open it. Offered first, since nothing else can be what they meant. */
data class OpenUrlHit(val target: String, val query: String) : Hit {
    override val key = "open:$target"
    override val title = query
    override val subtitle = hostOf(target)
    override val score = 0.0
    override val url = target
    override val action = HitAction.OPEN_URL
}

/**
 * The web. Always offered — and offered high up for the user who mostly comes here to search the web,
 * which is what [score] carries (see [actionScoreOf]).
 */
data class WebSearchHit(val query: String, override val score: Double = 0.0) : Hit {
    override val key = "web:$query"
    override val title = query
    override val subtitle = ""
    override val action = HitAction.WEB_SEARCH
}

/**
 * The question, put to whichever assistant the user chose (see [AiProvider]) — the built-in model
 * here, or one of the web chats over there. The built-in one is offered only where it can answer.
 */
data class AiHit(val query: String, val provider: AiProvider, override val score: Double = 0.0) : Hit {
    override val key = "ai:${provider.id}:$query"
    override val title = query
    override val subtitle = ""
    override val action = HitAction.ASK_AI
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
 * The final rank of a candidate: how well it matches, how much the user uses that *page*, and how much
 * they use that *kind of row*. Frecency enters through a logarithm — the difference between a page
 * opened once and one opened twenty times matters, the difference between two hundred and four hundred
 * does not — so that a much-used page can lift itself past a better-worded match, but never past a far
 * better one.
 */
private fun scoreOf(match: Double, frecency: Double, source: HitSource, action: HitAction): Double =
    match + 12.0 * ln(1.0 + frecency) + biasOf(source) + habitBias(action)

/** A tab is already open (switching to it is the cheapest thing the box can do); a card the user chose to keep. */
private fun biasOf(source: HitSource): Double = when (source) {
    HitSource.TABS -> 12.0
    HitSource.CARDS -> 8.0
    HitSource.COLLECTIONS -> 4.0
    else -> 0.0
}

/**
 * How much of what the user does here is this kind of row, worth up to [HABIT_WEIGHT] points to it.
 *
 * It is a share of their activity rather than a count of it ([habitShareOf]), so what it says is
 * "this user asks the model more often than they open cards" and not "this user has been here a long
 * time" — a count would lift every row of every kind together and order nothing. Bounded, and small
 * next to the match itself: a habit moves a row past its neighbours, it does not put the wrong answer
 * above the right one.
 */
private fun habitBias(action: HitAction): Double = HABIT_WEIGHT * habitShareOf(action)

/** What a row of the kind the user *always* takes is worth, on the scale of [matchScore]. */
private const val HABIT_WEIGHT = 35.0

/**
 * What the web-search and ask-the-model rows are worth. They match nothing — the query *is* the row —
 * so there is no [matchScore] to rank them by, only the habit: [ACTION_BASE] alone leaves them at the
 * bottom of the list, where they have always been, and a user who lives in one of them lifts it (by up
 * to [HABIT_WEIGHT]) past the weaker matches, and past the other of them.
 */
private fun actionScoreOf(action: HitAction): Double = ACTION_BASE + habitBias(action)

private const val ACTION_BASE = 20.0

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
 *  - the web-search and ask-the-model rows are scored too — by the user's habit alone, since they match
 *    nothing (see [actionScoreOf]) — and take their place among the rest rather than under them;
 *  - a group is placed by its best row, so whatever scored highest overall is the first row of the
 *    list — which is what Enter takes;
 *  - an address is the exception, and comes first whatever else is there: someone who typed a URL
 *    meant the URL.
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
    aiProvider: AiProvider,
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
        if (match <= 0.0) {
            null
        } else {
            TabHit(tab, scoreOf(match, frecencyOf(tab.url), HitSource.TABS, HitAction.SWITCH_TAB))
        }
    }
    val cardHits = cards.mapNotNull { card ->
        val match = matchScore(q, card.title, card.url, card.content)
        if (match <= 0.0) {
            null
        } else {
            CardHit(
                card = card,
                collectionTitle = collectionTitles[card.collectionId.toString()].orEmpty(),
                score = scoreOf(match, frecencyOf(card.url), HitSource.CARDS, HitAction.OPEN_CARD),
            )
        }
    }
    val historyHits = history.mapNotNull { entry ->
        val match = matchScore(q, entry.title, entry.url)
        if (match <= 0.0) {
            null
        } else {
            val frecency = frecencyOf(entry.url) + browserFrecency(entry)
            HistoryHit(entry, scoreOf(match, frecency, HitSource.HISTORY, HitAction.OPEN_HISTORY))
        }
    }
    val siteHits = topSites(Int.MAX_VALUE).mapNotNull { stat ->
        val match = matchScore(q, stat.title, stat.url)
        if (match <= 0.0) {
            null
        } else {
            SiteHit(stat, scoreOf(match, frecencyOf(stat.url), HitSource.SITES, HitAction.OPEN_SITE))
        }
    }
    val collectionHits = collections.mapNotNull { collection ->
        val match = matchScore(q, collection.title, "")
        if (match <= 0.0) {
            null
        } else {
            CollectionHit(collection, scoreOf(match, 0.0, HitSource.COLLECTIONS, HitAction.OPEN_COLLECTION))
        }
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

    // The web and the model are ranked beside everything else rather than parked underneath it: for the
    // user who searches the web from here all day, or asks the model all day, that is the answer, and
    // the list would be lying to put a page they once visited above it. Their own order is the same
    // question again — whichever of the two the user reaches for more is the one offered first.
    val actions = buildList<Hit> {
        add(WebSearchHit(q, actionScoreOf(HitAction.WEB_SEARCH)))
        // A web chat is always there to ask; the browser's own model only where the browser has one.
        if (aiAvailable) add(AiHit(q, aiProvider, actionScoreOf(HitAction.ASK_AI)))
    }.sortedByDescending { it.score }

    // The best row overall is the first row of the list, so Enter always takes the best answer.
    val ranked = (found + HitGroup(HitSource.ACTION, "", actions))
        .sortedByDescending { group -> group.hits.first().score }
    val address = if (looksLikeUrl(q)) listOf(HitGroup(HitSource.ACTION, "", listOf(OpenUrlHit(asUrl(q), q)))) else emptyList()

    return address + ranked
}
