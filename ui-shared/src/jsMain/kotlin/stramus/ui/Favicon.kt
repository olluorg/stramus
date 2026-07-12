package stramus.ui

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useState
import stramus.core.repo.FaviconRepository
import web.cssom.ClassName
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The global `fetch`, enough of it to ask for an icon and get a promise back. */
private external fun fetch(url: String, options: dynamic): dynamic

/**
 * The favicon cache. A card stores only the *URL* of its icon, which is worth nothing the moment the
 * network — or the icon service — is unavailable: the card is then drawn blank. So the bytes behind
 * that URL are kept, as a `data:` URI, in the [FaviconRepository] (a SQLite table), keyed by host.
 *
 * The cache is mirrored in memory for the session: it is read once on start ([initFaviconCache]) and
 * every host is refreshed at most once per page load. Refreshing walks [sourcesFor] in order and
 * keeps the first icon whose bytes it can actually read — the card's own icon URL first, then the
 * icon services. Anything cached survives all of them failing, which is the point of caching at all.
 */
private val cached = mutableMapOf<String, String>()
private val attempted = mutableSetOf<String>()
private var repository: FaviconRepository? = null
private val faviconScope = MainScope()

/** Bytes larger than this are not a favicon; caching them would only bloat the database. */
private const val MAX_ICON_BYTES = 150_000

/** Load the cached icons into memory, so the first paint of a card needs no network at all. */
internal suspend fun initFaviconCache(repo: FaviconRepository) {
    repository = repo
    runCatching { cached.putAll(repo.all()) }
}

/**
 * Where the icon of [host] can come from, most specific first: the URL saved with the card or tab
 * (the site's own icon), then the two icon services. Only some of these are readable from a page —
 * Google's, for one, sends no CORS headers — so the list is a chain, not a preference: whichever
 * responds with readable bytes wins.
 */
private fun sourcesFor(host: String, stored: String?): List<String> = listOfNotNull(
    stored?.takeIf { it.startsWith("http", ignoreCase = true) },
    "https://www.google.com/s2/favicons?domain=$host&sz=64",
    "https://favicone.com/$host?s=64",
).distinct()

/**
 * Fetch [host]'s icon and refresh its cache entry, returning the icon to show — the fresh bytes, or,
 * when every source fails (offline, dead service, no icon at all), whatever was cached before.
 * Returns null only when the icon is unknown and cannot be fetched: the caller then falls back to
 * the placeholder.
 */
private suspend fun refreshFavicon(host: String, stored: String?): String? {
    if (!attempted.add(host)) return cached[host]
    for (source in sourcesFor(host, stored)) {
        val data = fetchDataUri(source) ?: continue
        cached[host] = data
        repository?.let { repo -> runCatching { repo.put(host, data) } }
        return data
    }
    return cached[host]
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

    var data by useState<String?> { cached[host] }
    var broken by useState(false)

    useEffect(host, stored) {
        // A tab can navigate under a mounted component: re-key the state on the new host first.
        data = cached[host]
        broken = false
        faviconScope.launch {
            val fresh = refreshFavicon(host, stored)
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
            else -> stored ?: faviconFor(props.url)
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
