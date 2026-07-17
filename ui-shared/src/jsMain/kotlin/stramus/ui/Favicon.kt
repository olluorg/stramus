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
 * what is missing or old: [sourcesFor] is walked in order and the first icon whose bytes can actually
 * be read wins — the card's own icon URL first, then the icon services. Anything cached survives all
 * of them failing, which is the point of caching at all.
 */
private val cached = mutableMapOf<String, CachedIcon>()

/** Hosts whose fetch is running: the second card of a host waits on the first one's fetch, not its own. */
private val inFlight = mutableMapOf<String, Deferred<String?>>()

/** Hosts that came back with nothing this session. Asking again would fail again, once per card. */
private val failed = mutableSetOf<String>()

private var repository: FaviconRepository? = null
private val faviconScope = MainScope()

/** Bytes larger than this are not a favicon; caching them would only bloat the database. */
private const val MAX_ICON_BYTES = 150_000

/** How long a fetched icon is taken at its word before the site is asked for it again. */
private val ICON_MAX_AGE = 30.days

/**
 * How many icons are fetched at once. A collection of a few hundred links is a few hundred hosts, and
 * a page that asks for all of them at the same moment gets a slower answer for every one of them —
 * and looks, to an icon service, like something worth rate-limiting.
 */
private val fetchSlots = Semaphore(6)

/** Load the cached icons into memory, so the first paint of a card needs no network at all. */
internal suspend fun initFaviconCache(repo: FaviconRepository) {
    repository = repo
    runCatching { cached.putAll(repo.all()) }
}

/**
 * Where a site's icon may be read from, in order of preference. Whichever source responds with
 * readable bytes first wins, so the list is a chain rather than a choice.
 *
 * The two implementations differ in more than speed. The web app has nothing but the public icon
 * services ([NetworkIcons]), and asking them for an icon tells them which host the user has saved.
 * The extension has the browser's own favicon store ([stramus.ext.ChromeIcons]) — the icons of the
 * pages this browser has already visited, on this machine — and so it asks nobody at all.
 */
fun interface IconSources {
    /** Icon URLs to try for [pageUrl], best first. [stored] is the icon URL saved with the card, if any. */
    fun sourcesFor(pageUrl: String, stored: String?): List<String>
}

/**
 * The icon services, for a page that has no browser behind it to ask: the card's own icon URL first
 * (the site's own icon, saved with the link), then the two services. Only some of these are readable
 * from a page — Google's, for one, sends no CORS headers — hence the chain.
 */
val NetworkIcons = IconSources { pageUrl, stored ->
    val host = hostOf(pageUrl)
    listOfNotNull(
        stored?.takeIf { it.startsWith("http", ignoreCase = true) },
        "https://www.google.com/s2/favicons?domain=$host&sz=64",
        "https://favicone.com/$host?s=64",
    ).distinct()
}

private var iconSources: IconSources = NetworkIcons

/**
 * Hand the icons over to the platform's own source. Called by `App` from its props before anything is
 * drawn; the web app leaves it alone and keeps [NetworkIcons].
 */
internal fun installIconSources(sources: IconSources) {
    if (sources !== iconSources) {
        iconSources = sources
        // What was fetched from the previous source is not what this one would return.
        failed.clear()
    }
}

/**
 * The icon to show for [host]: the cached bytes while they are still fresh, otherwise the bytes a
 * fetch brings back — and, when every source fails (offline, dead service, no icon at all), whatever
 * was cached before. Null only when the icon is unknown and cannot be fetched: the caller then falls
 * back to the placeholder.
 *
 * Cards of the same host share one fetch rather than each making their own, and no more than
 * [fetchSlots] of them run at a time.
 */
private suspend fun iconFor(pageUrl: String, host: String, stored: String?): String? {
    val hit = cached[host]
    if (hit != null && Clock.System.now() - hit.updatedAt < ICON_MAX_AGE) return hit.dataUri
    if (host in failed) return hit?.dataUri

    val fetch = inFlight.getOrPut(host) {
        faviconScope.async {
            try {
                fetchSlots.withPermit {
                    for (source in iconSources.sourcesFor(pageUrl, stored)) {
                        val data = fetchDataUri(source) ?: continue
                        cached[host] = CachedIcon(data, Clock.System.now())
                        repository?.let { repo -> runCatching { repo.put(host, data) } }
                        return@withPermit data
                    }
                    failed += host
                    cached[host]?.dataUri
                }
            } finally {
                inFlight.remove(host)
            }
        }
    }
    return fetch.await()
}

/** GET [url] and return its bytes as a `data:` URI, or null if it cannot be fetched or is not an image. */
private suspend fun fetchDataUri(url: String): String? = suspendCoroutine { continuation ->
    var settled = false
    val done: (String?) -> Unit = { value ->
        if (!settled) {
            settled = true
            continuation.resume(value)
        }
    }
    // A cross-origin icon without CORS headers rejects the fetch; that is a miss, not an error.
    runCatching {
        fetch(url, js("({ credentials: 'omit', redirect: 'follow' })")).then(
            { response: dynamic ->
                if (response.ok != true) done(null) else readAsDataUri(response.blob(), done)
            },
            { _: dynamic -> done(null) },
        )
    }.onFailure { done(null) }
}

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

/** Shown when a site has no reachable icon and none was ever cached: a neutral globe. */
private val placeholderIcon: String = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9aa4b2" stroke-width="1.4">
        <circle cx="12" cy="12" r="9"/>
        <path d="M3 12h18"/>
        <path d="M12 3c2.6 2.7 2.6 15.3 0 18c-2.6-2.7-2.6-15.3 0-18z"/>
    </svg>
    """.trimIndent(),
)

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

    var data by useState<String?> { cached[host]?.dataUri }
    var broken by useState(false)

    useEffect(host, stored) {
        // A tab can navigate under a mounted component: re-key the state on the new host first.
        data = cached[host]?.dataUri
        broken = false
        // Launched on the shared scope, not this effect's: a fetch belongs to the host, not to the one
        // card that happened to ask for it first, and it outlives that card being scrolled away.
        faviconScope.launch {
            val fresh = iconFor(props.url, host, stored)
            if (fresh != null) {
                data = fresh
                broken = false
            }
        }
    }

    img {
        className = ClassName(props.className ?: "fav")
        src = when {
            broken -> placeholderIcon
            data != null -> data!!
            // Nothing cached yet: draw the best source directly while the fetch that will cache it
            // runs. It has to be a source of the *platform's* chain — in the extension the icon comes
            // from the browser's own store, and an `<img>` pointing anywhere else would be the one
            // request to an icon service the extension is built not to make.
            else -> iconSources.sourcesFor(props.url, stored).firstOrNull() ?: placeholderIcon
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
