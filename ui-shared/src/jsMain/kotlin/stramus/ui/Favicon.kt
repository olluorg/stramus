package stramus.ui

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import react.FC
import react.Props
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useState
import stramus.core.repo.CachedIcon
import stramus.core.repo.FaviconRepository
import stramus.core.url.hostOf
import web.cssom.ClassName
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** The global `fetch`, enough of it to ask for an icon and get a promise back. */
private external fun fetch(url: String, options: dynamic): dynamic

/**
 * The favicon cache. A card stores only the *URL* of its icon, which is worth nothing the moment the
 * network — or the icon service — is unavailable: the card is then drawn blank. So the bytes behind
 * that URL are kept, as a `data:` URI, in the [FaviconRepository] (a SQLite table), keyed by host.
 *
 * The cache is mirrored in memory for the session: it is read once on start ([initFaviconCache]).
 * An icon fetched less than [ICON_MAX_AGE] ago is simply used — a page of saved links is a page of
 * hosts whose icons were settled long ago, and asking the icon services about every one of them on
 * every load is a great deal of network for an answer that has not changed. What is fetched, then, is
 * what is missing or old: [IconSources.sourcesFor] is walked in order and the first icon whose bytes can
 * actually be read wins. Anything cached survives all of them failing, which is the point of caching.
 */
private val cached = mutableMapOf<String, CachedIcon>()

/** Hosts whose fetch is running: the second card of a host waits on the first one's fetch, not its own. */
private val inFlight = mutableMapOf<String, Deferred<String?>>()

/**
 * How a host's chain ended, for the hosts where it ended badly. Asking again would fail again, once per
 * card — but *which* way it failed decides what is drawn, so this is not merely a set of names.
 */
private enum class Outcome {
    /** Somebody authoritative said there is no icon. Nothing left to try; draw the letter tile. */
    ABSENT,

    /** Nothing could be reached. There may well be an icon; a display-only source is still worth a try. */
    UNREACHABLE,
}

private val outcomes = mutableMapOf<String, Outcome>()

private var repository: FaviconRepository? = null
private val faviconScope = MainScope()

/** Bytes larger than this are not a favicon; caching them would only bloat the database. */
private const val MAX_ICON_BYTES = 150_000

/** How long a fetched icon is taken at its word before the site is asked for it again. */
private val ICON_MAX_AGE = 30.days

/**
 * How long an unreachable [IconSourceKind.AUTHORITATIVE] source is left alone before it is tried again.
 *
 * Without this, a server that is down costs one failed request *per host* on every load — a hundred cards
 * being a hundred timeouts — before the fallbacks get their turn. One failure stands in for the rest of
 * the minute, and the chain starts at the fallbacks instead.
 */
private val AUTHORITY_RETRY_AFTER = 2.minutes

private var authorityDownUntil = Clock.System.now() - AUTHORITY_RETRY_AFTER

/** Load the cached icons into memory, so the first paint of a card needs no network at all. */
internal suspend fun initFaviconCache(repo: FaviconRepository) {
    repository = repo
    runCatching { cached.putAll(repo.all()) }

    // Rows written before the source learned to recognise its own "I have no icon" reply are stand-ins
    // sitting where real icons belong, and they would sit there until they aged out. Drop them here rather
    // than let the first paint show them: by the time anything is drawn the cache is only real icons.
    val stale = cached.filter { (_, icon) -> iconSources.isBlank(icon.dataUri) }.keys.toList()
    stale.forEach { host ->
        cached.remove(host)
        runCatching { repository?.remove(host) }
    }
}

/** What a source had to say. The three cases are the whole reason a chain can be walked sensibly. */
private sealed interface Fetched {
    class Bytes(val dataUri: String) : Fetched

    /** The source answered, and the answer is that there is no icon here. */
    data object Absent : Fetched

    /** The source could not be reached at all — offline, blocked, timed out, or simply broken. */
    data object Unavailable : Fetched
}

/**
 * What a source is, as far as walking the chain is concerned.
 *
 * These are not shades of preference. Each one changes what happens next, and flattening them back into a
 * list of URLs is what made the chain unable to tell "there is no icon" from "I could not ask".
 */
enum class IconSourceKind {
    /** Readable bytes; a miss here means nothing more than "try the next one". */
    TRY,

    /**
     * As [TRY], but a definite "there is no icon" **ends the chain**: this source has already been through
     * the sources below it on the caller's behalf, and asking them again would undo the reason it exists.
     * Only a source that cannot be *reached* lets the chain go on.
     */
    AUTHORITATIVE,

    /**
     * Cannot be read, only shown. A service that sends no CORS headers — google's, for one — is invisible
     * to `fetch` and perfectly visible in an `<img>`, so it can never be cached and is worth nothing except
     * as the last thing to point an `<img>` at when everything else has failed.
     */
    DISPLAY_ONLY,
}

/** One place a site's icon might come from, and what its answers mean. */
data class IconSource(val url: String, val kind: IconSourceKind = IconSourceKind.TRY)

/**
 * Where a site's icon may be read from, in order of preference.
 *
 * The two implementations differ in more than speed. The web app has no browser store to ask and starts at
 * the server ([NetworkIcons]); the extension has the browser's own favicon store
 * ([stramus.ext.ChromeIcons]) — the icons of pages this browser has already visited, on this machine — and
 * so a visited site never leaves the machine at all.
 *
 * Neither asks a public icon service first. Doing so tells that service which hosts the user has saved, one
 * request at a time; the server fetches them instead, and only when it cannot be reached does the chain fall
 * through to the services directly.
 */
interface IconSources {
    /** Icon sources to try for [pageUrl], best first. [stored] is the icon URL saved with the card, if any. */
    fun sourcesFor(pageUrl: String, stored: String?): List<IconSource>

    /**
     * Whether [dataUri] is this source's own stand-in for "I do not know this host" rather than an icon.
     *
     * Some sources answer for *every* host, icon or no icon: Chrome's favicon store hands back its grey
     * document for a page it has never seen, with a perfectly successful status. Taken at face value that
     * stand-in is cached for a month, drawn in place of the real icon, and survives the user finally
     * visiting the site. Sources that do this recognise their own; the rest say no and mean it.
     */
    fun isBlank(dataUri: String): Boolean = false
}

/**
 * The chain for a page with no browser behind it to ask: the card's own icon URL, then the server, then —
 * only if the server cannot be reached — the icon services, which is the one case where a third party
 * learns a host from the user's own address rather than from ours.
 */
val NetworkIcons: IconSources = object : IconSources {
    override fun sourcesFor(pageUrl: String, stored: String?): List<IconSource> {
        val host = hostOf(pageUrl)
        return listOfNotNull(
            // The site's own icon, saved with the link. Rarely readable cross-origin, free to try.
            stored?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { IconSource(it) },
            IconSource(faviconProxyUrl(host), IconSourceKind.AUTHORITATIVE),
            IconSource("https://favicone.com/$host?s=64"),
            IconSource("https://www.google.com/s2/favicons?domain=$host&sz=64", IconSourceKind.DISPLAY_ONLY),
        ).distinctBy { it.url }
    }
}

/** The server's icon endpoint for [host] — the same server the app syncs with. */
fun faviconProxyUrl(host: String): String =
    "${serverBaseUrl().trimEnd('/')}/v1/favicon?host=${encodeURIComponent(host)}"

private var iconSources: IconSources = NetworkIcons

/**
 * Hand the icons over to the platform's own source. Called by `App` from its props before anything is
 * drawn; the web app leaves it alone and keeps [NetworkIcons].
 */
internal fun installIconSources(sources: IconSources) {
    if (sources !== iconSources) {
        iconSources = sources
        // What was fetched from the previous source is not what this one would return.
        outcomes.clear()
    }
}

/**
 * The icon to show for [host]: the cached bytes while they are still fresh, otherwise the bytes a
 * fetch brings back — and, when every source fails (offline, dead service, no icon at all), whatever
 * was cached before. Null only when the icon is unknown and cannot be fetched: the caller then falls
 * back to the placeholder, or to a display-only source, depending on *why* (see [Outcome]).
 *
 * Cards of the same host share one fetch rather than each making their own, and no more than
 * [fetchSlots] of them run at a time.
 */
private suspend fun iconFor(pageUrl: String, host: String, stored: String?): String? {
    // Checked on the way in rather than only at start-up: the extension learns what its browser's stand-in
    // looks like from a probe that may still have been in flight when the cache was read, so an entry that
    // looked like an icon then can be recognised for what it is now.
    val hit = cached[host]?.takeUnless { iconSources.isBlank(it.dataUri) } ?: run {
        if (cached.remove(host) != null) runCatching { repository?.remove(host) }
        null
    }
    if (hit != null && Clock.System.now() - hit.updatedAt < ICON_MAX_AGE) return hit.dataUri
    if (host in outcomes) return hit?.dataUri

    val fetch = inFlight.getOrPut(host) {
        faviconScope.async {
            try {
                fetchSlots.withPermit { walkChain(pageUrl, host, stored) }
            } finally {
                inFlight.remove(host)
            }
        }
    }
    return fetch.await()
}

/**
 * How many icons are fetched at once. A collection of a few hundred links is a few hundred hosts, and
 * a page that asks for all of them at the same moment gets a slower answer for every one of them —
 * and looks, to an icon service, like something worth rate-limiting.
 */
private val fetchSlots = Semaphore(6)

private suspend fun walkChain(pageUrl: String, host: String, stored: String?): String? {
    var reachedNothing = false

    for (source in iconSources.sourcesFor(pageUrl, stored)) {
        // Nothing to fetch here — it exists only to be pointed at when the rest has failed.
        if (source.kind == IconSourceKind.DISPLAY_ONLY) continue
        // Known to be down a moment ago. Skipping it costs a stale icon for at most a couple of minutes;
        // not skipping it costs a timeout per host on every load for as long as the outage lasts.
        if (source.kind == IconSourceKind.AUTHORITATIVE && Clock.System.now() < authorityDownUntil) continue

        when (val answer = fetchIcon(source.url)) {
            is Fetched.Bytes -> {
                // A source that answers for every host answers for the ones it knows nothing about too.
                if (iconSources.isBlank(answer.dataUri)) continue
                cached[host] = CachedIcon(answer.dataUri, Clock.System.now())
                repository?.let { repo -> runCatching { repo.put(host, answer.dataUri) } }
                return answer.dataUri
            }

            Fetched.Absent -> if (source.kind == IconSourceKind.AUTHORITATIVE) {
                // It went through the fallbacks for us and came back with nothing. Repeating that walk from
                // here would hand the very hosts to the icon services that going through it was meant to keep
                // from them — and would get the same answer.
                outcomes[host] = Outcome.ABSENT
                return cached[host]?.dataUri
            }

            Fetched.Unavailable -> {
                if (source.kind == IconSourceKind.AUTHORITATIVE) {
                    authorityDownUntil = Clock.System.now() + AUTHORITY_RETRY_AFTER
                }
                reachedNothing = true
            }
        }
    }

    outcomes[host] = if (reachedNothing) Outcome.UNREACHABLE else Outcome.ABSENT
    return cached[host]?.dataUri
}

/**
 * GET [url] and say what came back.
 *
 * A cross-origin icon without CORS headers rejects the fetch, and so does being offline; the two are
 * indistinguishable from here, and both are [Fetched.Unavailable]. That is the safe way round — an
 * [IconSourceKind.AUTHORITATIVE] source read as unavailable costs a fall-through to the fallbacks, while one
 * read as [Fetched.Absent] would end the chain on what was really a network error.
 */
private suspend fun fetchIcon(url: String): Fetched = suspendCoroutine { continuation ->
    var settled = false
    val done: (Fetched) -> Unit = { value ->
        if (!settled) {
            settled = true
            continuation.resume(value)
        }
    }
    runCatching {
        fetch(url, js("({ credentials: 'omit', redirect: 'follow' })")).then(
            { response: dynamic ->
                val status = (response.status as? Number)?.toInt() ?: 0
                when {
                    // The server is there and saying it could not find out. Not an answer about the icon.
                    status >= 500 -> done(Fetched.Unavailable)
                    // 204 is the server's "there is no icon"; 404 and the rest are somebody else's.
                    status == 204 || response.ok != true -> done(Fetched.Absent)
                    else -> readAsDataUri(response.blob()) { data ->
                        done(if (data != null) Fetched.Bytes(data) else Fetched.Absent)
                    }
                }
            },
            { _: dynamic -> done(Fetched.Unavailable) },
        )
    }.onFailure { done(Fetched.Unavailable) }
}

/**
 * Read [url] as a `data:` URI, or null if it cannot be read or is not an image.
 *
 * Public because a platform's [IconSources] needs it to find out what its own "I do not know this host"
 * reply looks like — see [IconSources.isBlank].
 */
suspend fun readIconDataUri(url: String): String? = (fetchIcon(url) as? Fetched.Bytes)?.dataUri

private fun readAsDataUri(blobPromise: dynamic, done: (String?) -> Unit) {
    blobPromise.then(
        { blob: dynamic ->
            val size = (blob.size as? Number)?.toDouble() ?: 0.0
            if (size <= 0.0 || size > MAX_ICON_BYTES) {
                done(null)
            } else {
                val reader = FileReader()
                // A source that 200s with an HTML error page reads fine but is no icon — hence the MIME check.
                reader.onload = { done(reader.result?.takeIf { it.startsWith("data:image/") }) }
                reader.onerror = { done(null) }
                reader.readAsDataURL(blob)
            }
        },
        { _: dynamic -> done(null) },
    )
}

/**
 * Shown when a site has no reachable icon and none was ever cached: a tile in the host's own colour
 * with its first letter on it, rather than one more anonymous globe. The colour is a stable hash of
 * the host, so the same site is always the same tile and a page of iconless links reads as distinct
 * rows instead of a column of identical placeholders.
 *
 * It is an SVG `data:` URI like any other icon source, so the caller draws it into the same `<img>`
 * with no special case — a tab, a card, a history row all get it for free.
 */
private val letterPlaceholders = mutableMapOf<String, String>()

private fun letterPlaceholder(host: String): String = letterPlaceholders.getOrPut(host) {
    val letter = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    // A stable hue per host: fold the characters onto the colour wheel. Saturation and lightness are
    // fixed so every tile belongs to one family and the white letter stays readable on all of them —
    // and so the same colour works on the light and the dark theme without knowing which is on.
    val hue = host.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff } % 360
    "data:image/svg+xml;charset=utf-8," + encodeURIComponent(
        """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
            <rect width="24" height="24" rx="5" fill="hsl($hue, 58%, 52%)"/>
            <text x="12" y="12.5" text-anchor="middle" dominant-baseline="central"
                  font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
                  font-size="13" font-weight="600" fill="#ffffff">$letter</text>
        </svg>
        """.trimIndent(),
    )
}

external interface FaviconProps : Props {
    /** The page the icon stands for; its host is the cache key. */
    var url: String

    /** The icon URL saved with the card or tab, if any — the first source tried when refreshing. */
    var favicon: String?

    /** Class for the `<img>`; defaults to `fav`. */
    var className: String?
}

/**
 * A site's icon, cached. The cached bytes are drawn first — they need no network, so a saved link
 * keeps its icon offline — while a refresh runs in the background and swaps in the current icon if
 * the site's has changed. With nothing cached yet the icon is drawn straight from its source, and if
 * that too is unavailable (or the site simply has no icon), the placeholder stands in.
 */
val Favicon = FC<FaviconProps> { props ->
    val host = hostOf(props.url)
    val stored = props.favicon?.takeIf { it.isNotBlank() }
    // The stand-in for this host, drawn when it has no reachable icon of its own. Computed here so
    // every "nothing to draw" branch below reaches for the same tile.
    val fallback = letterPlaceholder(host)

    var data by useState<String?> { cached[host]?.dataUri }
    var broken by useState(false)

    // Whether the chain has finished for this host. Its own state because a chain that ends with *nothing*
    // leaves `data` exactly as it found it — null — and React, seeing an unchanged value, would not redraw:
    // the card would sit on whichever source it was optimistically pointed at instead of falling back to
    // the letter tile. This is what says "the answer is in, look at it again".
    // Seeded from what is already known: the second card of a host whose chain finished long ago should
    // draw the right thing on its first frame rather than flash the source it is about to give up on.
    var settled by useState { host in outcomes }

    useEffect(host, stored) {
        // A tab can navigate under a mounted component: re-key the state on the new host first.
        data = cached[host]?.dataUri
        broken = false
        settled = false
        // Launched on the shared scope, not this effect's: a fetch belongs to the host, not to the one
        // card that happened to ask for it first, and it outlives that card being scrolled away.
        faviconScope.launch {
            val fresh = iconFor(props.url, host, stored)
            if (fresh != null) {
                data = fresh
                broken = false
            }
            settled = true
        }
    }

    img {
        className = ClassName(props.className ?: "fav")
        val sources = iconSources.sourcesFor(props.url, stored)
        src = when {
            broken -> fallback
            data != null -> data!!
            // The chain is finished and had nothing. What is drawn depends on why: told there is no icon,
            // the letter tile is the honest answer; unable to ask at all, a display-only service is the one
            // thing left that might still have one — an `<img>` needs no CORS headers, only a URL.
            settled && outcomes[host] == Outcome.ABSENT -> fallback
            settled && outcomes[host] == Outcome.UNREACHABLE ->
                sources.firstOrNull { it.kind == IconSourceKind.DISPLAY_ONLY }?.url ?: fallback
            // Nothing cached yet: draw the best source directly while the fetch that will cache it runs.
            else -> sources.firstOrNull()?.url ?: fallback
        }
        alt = ""
        draggable = false // let the card or tab row be the drag source, not the image
        onError = {
            // The source is unreachable, or the cached bytes no longer decode: fall back to the placeholder.
            data = null
            broken = true
        }
    }
}
