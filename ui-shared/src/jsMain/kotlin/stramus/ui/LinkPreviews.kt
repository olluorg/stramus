package stramus.ui

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import stramus.core.repo.CachedPreview
import stramus.core.repo.LinkPreviewRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * What saved pages say about themselves — `og:title`, `og:description`, `og:image` — as far as the app
 * is concerned: a cache in front of one question put to the server (`GET /v1/preview`).
 *
 * The shape is [Favicon]'s, and for the same reasons: read into memory once on start so the first
 * hover needs no network, kept in the database so a new tab does not ask again, one request per page
 * however many cards ask at once, and a hard ceiling on how many run together.
 *
 * Two things it does that the icon cache does not, both following from what a page preview *is*:
 *
 * - **How much is asked follows where the previews are shown**, which is the user's own choice (see
 *   [CardPreviews]). On hover, one page is asked about — the card under the pointer — and crossing a
 *   grid on the way to the sidebar asks about nothing at all; set to *always*, every link on screen
 *   wears its picture, so every link on screen is asked about ([prefetchPreviews]). The second is a
 *   great deal more traffic, for the user and for the sites, and it is what the setting says it is.
 * - **Only while signed in.** [installPreviewSource] is handed a way to ask, or null, and null means
 *   this whole file quietly does nothing. See `StramusApi.preview`, which explains why.
 */
private val cached = mutableMapOf<String, CachedPreview>()

/** Pages being asked about right now: two cards of the same address wait on one question. */
private val inFlight = mutableMapOf<String, Deferred<CachedPreview?>>()

private var repository: LinkPreviewRepository? = null
private val previewScope = MainScope()

/**
 * How to ask. Null when there is nobody to ask — signed out, or the setting is off — and the null is
 * what makes every path below a no-op rather than a failed request.
 */
private var source: (suspend (String) -> CachedPreview?)? = null

/**
 * How many pages are asked about at once. Lower than the icons' six: this is one server, each answer
 * may cost it a fetch of somebody else's page, and a user cannot point at four cards at a time anyway
 * — the ceiling only matters when a pointer is dragged across a grid.
 */
private val askSlots = Semaphore(3)

/** How long an answer is taken at its word. Matches the server's own `previewTtl`. */
private val PREVIEW_MAX_AGE = 14.days

/**
 * How long the server is left alone after it could not answer at all.
 *
 * Without it, a server that is down costs a failed request per card hovered, for as long as the outage
 * lasts. With it, one failure stands in for the rest of the minute and the cards behave exactly as they
 * do with the setting off — which is to say, like ordinary cards.
 */
private val RETRY_AFTER = 2.minutes
private var downUntil = Clock.System.now() - RETRY_AFTER

/** Load the cached previews into memory, so a hover on a card asked about yesterday draws at once. */
internal suspend fun initLinkPreviewCache(repo: LinkPreviewRepository) {
    repository = repo
    runCatching { cached.putAll(repo.all()) }
}

/**
 * Hand over the way to ask, or null to stop asking — called by `App` whenever the setting or the
 * session changes.
 *
 * It does *not* throw the cache away, and that is deliberate: a page has just as many previews to draw
 * from the cache while signed out as it did before, and this runs on every start — where the session is
 * not resumed until a moment after the first render, so a clear here would empty the cache on every
 * single load. Forgetting is [forgetPreviews], which the setting itself calls.
 */
internal fun installPreviewSource(ask: (suspend (String) -> CachedPreview?)?) {
    source = ask
}

/**
 * Throw the whole cache away, on disk as well as in memory — what turning the setting *off* means.
 *
 * The rows are the record of which pages were asked about, and a user who switches this off is entitled
 * to expect both that the asking has stopped and that what it produced has gone. Merely signing out is
 * not that: the cards stay, so what is cached about them stays too.
 */
internal fun forgetPreviews() {
    cached.clear()
    inFlight.clear()
    repository?.let { repo -> previewScope.launch { runCatching { repo.clear() } } }
    // The cards wearing pictures are wearing them off the map just emptied.
    onChanged?.invoke()
}

/** What is already known about [url], without asking anybody. Null when nothing is. */
internal fun knownPreview(url: String): CachedPreview? = cached[url]

/**
 * Told whenever an answer lands, so that whatever draws from [knownPreview] can draw again.
 *
 * A hover preview needs none of this — it asks and awaits in the component that shows it — but a card
 * wearing its picture reads the cache during a render that has already happened by the time the answer
 * arrives. React's own batching means a screenful of answers costs one redraw, not one each.
 */
internal fun onPreviewsUpdated(listener: (() -> Unit)?) {
    onChanged = listener
}

private var onChanged: (() -> Unit)? = null

/**
 * Ask about every one of [urls] that is not already known, all at once — what a screen of cards wearing
 * their pictures needs, as against a hover, which asks about the one card under the pointer.
 *
 * The questions go on [previewScope] rather than the caller's, so scrolling to another collection while
 * they are out does not throw away answers already paid for; [askSlots] is what keeps "all at once" from
 * meaning literally that.
 */
internal suspend fun prefetchPreviews(urls: List<String>) {
    if (source == null) return
    coroutineScope {
        urls.distinct().forEach { url -> launch { runCatching { pagePreview(url) } } }
    }
}

/**
 * What [url] says about itself: the cached answer while it is fresh, otherwise the one the server
 * gives. Null when there is nothing to show — nobody to ask, nothing to say, or nothing reachable.
 *
 * A page that turned out to say nothing is remembered saying nothing ([CachedPreview.isEmpty]), so the
 * question is not put again on every hover for the next fortnight.
 */
internal suspend fun pagePreview(url: String): CachedPreview? {
    val ask = source ?: return null

    val hit = cached[url]
    if (hit != null && Clock.System.now() - hit.updatedAt < PREVIEW_MAX_AGE) return hit.takeUnless { it.isEmpty }
    if (Clock.System.now() < downUntil) return hit?.takeUnless { it.isEmpty }

    val question = inFlight.getOrPut(url) {
        previewScope.async {
            try {
                askSlots.withPermit { askServer(url, ask) }
            } finally {
                inFlight.remove(url)
            }
        }
    }
    return question.await()
}

private suspend fun askServer(url: String, ask: suspend (String) -> CachedPreview?): CachedPreview? {
    val answer = runCatching { ask(url) }.getOrElse {
        // Offline, signed out under us, the server having a moment. Nothing is cached — an outage is not
        // an answer about the page — and the cards go back to being ordinary cards for a couple of minutes.
        downUntil = Clock.System.now() + RETRY_AFTER
        return cached[url]?.takeUnless { it.isEmpty }
    }

    // Null is the server's 204: it read the page and the page describes itself in none of the three ways.
    // Kept, as an empty row, precisely because it is an answer — see [CachedPreview].
    val settled = answer ?: CachedPreview(null, null, null, Clock.System.now())
    cached[url] = settled
    repository?.let { repo -> runCatching { repo.put(url, settled) } }
    onChanged?.invoke()
    return settled.takeUnless { it.isEmpty }
}
