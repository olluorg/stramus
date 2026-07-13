package stramus.ui

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import stramus.core.repo.UsageRepository
import stramus.core.repo.UsageStat
import kotlin.time.Clock

/**
 * What the user actually uses: how often, and how recently, each page has been opened *from stramus*.
 * It is what puts the pages they live in at the top of the search, and what an empty search box shows
 * — their top sites — before a single character is typed.
 *
 * The table behind it ([UsageRepository]) is read once on start and then held here, in memory, for the
 * session: the search consults it on every keystroke, for every candidate on screen, and a database
 * round-trip per candidate per keystroke is not a thing a search box can afford. Writes go both ways
 * at once — the map is updated immediately, so the next keystroke already ranks with it, and the row
 * is persisted in the background.
 */
private val stats = mutableMapOf<String, UsageStat>()

/**
 * Frecency summed per host, rebuilt lazily. A page of a much-used site starts out ahead of a page of
 * a site never opened — see [frecencyOf] — and computing that from [stats] on every candidate of
 * every keystroke would be a full scan each time. Dropped whenever [stats] changes.
 */
private var hostTotals: Map<String, Double>? = null

private var repository: UsageRepository? = null
private val usageScope = MainScope()

/** How much of a host's frecency rubs off on a page of that host the user has not opened before. */
private const val HOST_SHARE = 0.3

/** Load what the user has opened before into memory, so the first keystroke already ranks by it. */
internal suspend fun initUsageIndex(repo: UsageRepository) {
    repository = repo
    runCatching { repo.all().forEach { stats[it.url] = it } }
    hostTotals = null
}

/**
 * Count one opening of [url]. Every way out of stramus goes through here — a card followed, a tab
 * switched to, a visited page reopened, an address typed into the search box — because the ranking is
 * only worth anything if it sees all of them.
 */
internal fun recordUse(url: String, title: String) {
    val key = normalizeUrl(url)
    if (key.isBlank()) return
    val existing = stats[key]
    stats[key] = UsageStat(
        url = key,
        title = title.ifBlank { existing?.title ?: key },
        host = key.substringBefore('/'),
        hits = (existing?.hits ?: 0) + 1,
        lastUsedAt = Clock.System.now(),
    )
    hostTotals = null
    repository?.let { repo -> usageScope.launch { runCatching { repo.record(key, title) } } }
}

/** Drop a page from the ranking — the "×" on a top site the user does not want offered again. */
internal fun forgetUse(url: String) {
    val key = normalizeUrl(url)
    if (stats.remove(key) == null) return
    hostTotals = null
    repository?.let { repo -> usageScope.launch { runCatching { repo.forget(key) } } }
}

/**
 * How much a page opened [hits] times, last of them at `lastUsedAt`, is worth now. Recency is what
 * separates the site someone used every day last year from the one they use every day this week, so
 * it is a multiplier on the count rather than a term beside it: an old habit fades however deep it
 * once was, and a fortnight of daily use outranks it.
 */
private fun frecency(stat: UsageStat): Double {
    val days = (Clock.System.now() - stat.lastUsedAt).inWholeDays
    val weight = when {
        days <= 1 -> 1.0
        days <= 3 -> 0.8
        days <= 7 -> 0.6
        days <= 30 -> 0.4
        days <= 90 -> 0.2
        else -> 0.1
    }
    return stat.hits * weight
}

private fun hostTotals(): Map<String, Double> = hostTotals ?: stats.values
    .groupBy { it.host }
    .mapValues { (_, pages) -> pages.sumOf { frecency(it) } }
    .also { hostTotals = it }

/**
 * What [url] is worth to the ranking: the page's own frecency, plus a share ([HOST_SHARE]) of what the
 * *rest* of its host is worth. A first-time page of a site opened daily is not a stranger — it should
 * outrank a page of a site never visited — but it is not the daily page either, hence only a share.
 */
internal fun frecencyOf(url: String): Double {
    val key = normalizeUrl(url)
    if (key.isBlank()) return 0.0
    val own = stats[key]?.let { frecency(it) } ?: 0.0
    val host = hostTotals()[key.substringBefore('/')] ?: 0.0
    return own + HOST_SHARE * (host - own).coerceAtLeast(0.0)
}

/** The user's top sites, most-used first: what an empty search box offers. */
internal fun topSites(limit: Int): List<UsageStat> =
    stats.values.sortedByDescending { frecency(it) }.take(limit)
