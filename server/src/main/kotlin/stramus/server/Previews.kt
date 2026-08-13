package stramus.server

import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import stramus.protocol.LinkPreview
import java.time.Duration as JavaDuration

class PreviewCacheRow : Entity() {
    var urlHash by PreviewCache.urlHash
    var title by PreviewCache.title
    var description by PreviewCache.description
    var image by PreviewCache.image
    var fetchedAt by PreviewCache.fetchedAt
}

/**
 * One row per page looked at, and — as with [FaviconCache] — no user column.
 *
 * The key is a *hash* of the address rather than the address itself, and that is the whole difference
 * between this table and the icon cache. An icon row names a host: "somebody, once, had a link to
 * example.com". A preview row would otherwise name a page, which is a far more particular thing to know
 * about a stranger. Hashed, the row can still be found by a caller who already has the URL — which is
 * every legitimate caller, since they are asking about a link they hold — and cannot be read back into a
 * list of pages by anyone who does not.
 *
 * It is not a secret-keeping measure and does not pretend to be one: an address can be guessed and
 * checked against a hash. It is the difference between a table that reads as a browsing history and one
 * that does not.
 *
 * All three fields null means the page was fetched and says nothing about itself. That negative is worth
 * keeping for the same reason [FaviconCache]'s is: without it, a collection full of pages with no tags
 * re-fetches every one of them on every load, forever.
 */
object PreviewCache : Table<ServerDb, PreviewCacheRow>("preview_cache", ::PreviewCacheRow) {
    val urlHash by Column.Text().primaryKey()
    val title by Column.Text().nullable()
    val description by Column.Text().nullable()
    val image by Column.Text().nullable() // an address, never bytes — see LinkPreview
    val fetchedAt by Column.Instant()

    init { urlHash; title; description; image; fetchedAt }
}

/** What the server has to say about a page, in the same three shapes [FaviconResult] uses, and for the same reasons. */
sealed interface PreviewResult {
    /** The page describes itself, and this is what it said. */
    data class Found(val preview: LinkPreview) : PreviewResult

    /** Looked, and the page says nothing about itself. The client stops asking and draws the card as it always did. */
    data object Absent : PreviewResult

    /** Nothing could be reached. Worth trying again later, so nothing is cached. */
    data object Unavailable : PreviewResult
}

/**
 * A page's own description of itself — `og:title`, `og:description`, `og:image` — fetched here rather
 * than in the browser.
 *
 * **Signed-in callers only**, and that is the single decision this whole file hangs on. The clients could
 * fetch the page themselves, which would need `<all_urls>` in the extension manifest and would put the
 * request on the user's own address — telling every site they have saved a link to it. Fetched from here
 * instead, the sites see this server. What that costs is that *this* server learns the address, and an
 * address is a much more particular thing than the host [FaviconService] learns. For a signed-in user it
 * is nothing new: their cards, URLs and all, are already in `sync_rows`, put there by the same person for
 * the same purpose. For everyone else it would be a real disclosure — so for everyone else this endpoint
 * does not exist, and a signed-out client never asks it anything.
 *
 * Nothing is stored that could be traced back to who asked: see [PreviewCache].
 *
 * Only the head of the document is read ([ServerConfig.maxPreviewBytes]), because that is where the tags
 * are, and only text is accepted — a link to a 300 MB video file is a link like any other and must not
 * become 300 MB of this server's traffic.
 */
class PreviewService(
    private val db: SuspendDatabase<ServerDb>,
    private val config: ServerConfig,
) {

    private val http: HttpClient = HttpClient.newBuilder()
        // By hand, for the same reason as in [FaviconService]: every hop is a new address, and every new
        // address has to pass [isFetchableHost] again before this server is pointed at it.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(JavaDuration.ofSeconds(5))
        .build()

    /** Outbound fetches happen at most this many at a time, so this never becomes somebody's load generator. */
    private val outbound = Semaphore(8)

    /** Pages being fetched right now: two devices opening the same collection are not two fetches of each card. */
    private val inFlight = mutableMapOf<String, CompletableDeferred<PreviewResult>>()
    private val inFlightLock = Mutex()

    /**
     * What [rawUrl] says about itself, from the cache when it is fresh and from the network when it is not.
     *
     * [allowFetch] is asked only on a miss, exactly as in [FaviconService.iconFor]: a hit costs a row read,
     * while a miss makes this server go and fetch a page somebody else chose.
     */
    suspend fun previewFor(rawUrl: String, allowFetch: () -> Boolean = { true }): PreviewResult {
        val url = normaliseUrl(rawUrl) ?: return PreviewResult.Absent
        val key = hashOf(url)

        cached(key)?.let { return it }
        if (!allowFetch()) return PreviewResult.Unavailable

        val (deferred, isOwner) = inFlightLock.withLock {
            val existing = inFlight[key]
            if (existing != null) existing to false else CompletableDeferred<PreviewResult>().also { inFlight[key] = it } to true
        }
        if (!isOwner) return deferred.await()

        val result = try {
            // Re-check under the in-flight claim: another caller may have finished between the read above
            // and here, and refetching what was just cached is the stampede this is here to prevent.
            cached(key) ?: outbound.withPermit { fetchAndStore(url, key) }
        } catch (_: Throwable) {
            PreviewResult.Unavailable
        } finally {
            inFlightLock.withLock { inFlight.remove(key) }
        }
        deferred.complete(result)
        return result
    }

    private suspend fun cached(key: String): PreviewResult? {
        val row = db.suspendAutocommit { PreviewCache.findOne { where { PreviewCache.urlHash eq key } } } ?: return null
        val age = Clock.System.now() - row.fetchedAt
        val preview = LinkPreview(row.title, row.description, row.image)
        return when {
            // A page that said nothing may well say something next week — an article gets its tags added,
            // a site is rebuilt — so the negative is trusted for less long than the answer.
            preview.isEmpty() -> if (age < config.previewNegativeTtl) PreviewResult.Absent else null
            age < config.previewTtl -> PreviewResult.Found(preview)
            else -> null
        }
    }

    private suspend fun fetchAndStore(url: String, key: String): PreviewResult {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
        if (host == null || !isFetchableHost(host)) {
            // Not reachable and never will be — a private address, or a name that does not resolve. An
            // answer rather than a failure, and worth caching so it is not resolved again on every load.
            store(key, LinkPreview())
            return PreviewResult.Absent
        }

        return when (val page = fetchHead(url)) {
            is PageResult.Unavailable -> PreviewResult.Unavailable
            is PageResult.NotHtml -> {
                // A PDF, an image, a download. It is a perfectly good card and simply has no tags; saying so
                // and remembering it is what keeps the client from asking about it again on every load.
                store(key, LinkPreview())
                PreviewResult.Absent
            }

            is PageResult.Html -> {
                val preview = parseOpenGraph(page.body, page.finalUrl)
                store(key, preview)
                if (preview.isEmpty()) PreviewResult.Absent else PreviewResult.Found(preview)
            }
        }
    }

    private suspend fun store(key: String, preview: LinkPreview) {
        val row = PreviewCacheRow().apply {
            this.urlHash = key
            this.title = preview.title
            this.description = preview.description
            this.image = preview.image
            this.fetchedAt = Clock.System.now()
        }
        runCatching {
            db.suspendTransaction {
                PreviewCache.deleteWhere { where { PreviewCache.urlHash eq key } }
                PreviewCache.insert(row)
            }
        }
    }

    /** What came back from a page: its head as text, something that was never text at all, or nothing. */
    private sealed interface PageResult {
        /** [finalUrl] is where the redirects ended, and it is what a relative `og:image` resolves against. */
        data class Html(val body: String, val finalUrl: String) : PageResult
        data object NotHtml : PageResult
        data object Unavailable : PageResult
    }

    /**
     * GET [url] and return as much of it as [ServerConfig.maxPreviewBytes] allows.
     *
     * The cap is not only politeness: it is the ceiling on what a hostile address can make this server read
     * into memory, and the tags being in `<head>` means a page truncated at 128 KB has already handed over
     * everything that is wanted from it.
     */
    private suspend fun fetchHead(url: String, hops: Int = MAX_HOPS): PageResult = withContext(Dispatchers.IO) {
        var current = url
        repeat(hops) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return@withContext PageResult.NotHtml
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if ((scheme != "https" && scheme != "http") || host.isNullOrBlank()) return@withContext PageResult.NotHtml
            if (!isFetchableHost(host)) return@withContext PageResult.NotHtml

            val request = HttpRequest.newBuilder(uri)
                .timeout(JavaDuration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                // Plain text on the wire: the alternative is decompressing a stranger's bytes here, and a
                // gzip bomb is a cheap way to turn a 128 KB cap into a great deal more than 128 KB.
                .header("Accept-Encoding", "identity")
                .GET()
                .build()

            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
                .getOrElse { return@withContext PageResult.Unavailable }

            val status = response.statusCode()
            when {
                status in 300..399 -> {
                    val location = response.headers().firstValue("location").orElse(null)
                    response.body().close()
                    current = location?.let { runCatching { uri.resolve(it).toString() }.getOrNull() }
                        ?: return@withContext PageResult.NotHtml
                }

                status == 200 -> {
                    val contentType = response.headers().firstValue("content-type").orElse("")
                    val mime = contentType.substringBefore(';').trim().lowercase()
                    if (mime != "text/html" && mime != "application/xhtml+xml") {
                        response.body().close()
                        return@withContext PageResult.NotHtml
                    }
                    val bytes = response.body().use { it.readNBytes(config.maxPreviewBytes) }
                    return@withContext PageResult.Html(decodeHtml(bytes, contentType), current)
                }

                // 5xx is the site having a bad day rather than having no tags; anything else — a 404, a 403
                // from a bot check — is an answer, and a settled one.
                status >= 500 -> {
                    response.body().close()
                    return@withContext PageResult.Unavailable
                }

                else -> {
                    response.body().close()
                    return@withContext PageResult.NotHtml
                }
            }
        }
        PageResult.NotHtml
    }

    private companion object {
        const val MAX_HOPS = 3

        /** Honest about who is asking, and points at the page that explains why — as [FaviconService] does. */
        const val USER_AGENT = "stramus-preview/1.0 (+https://stramus.space/privacy.html)"
    }
}

/** True when a page turned out to say nothing about itself at all. */
internal fun LinkPreview.isEmpty(): Boolean = title == null && description == null && image == null

/**
 * The address as it will be asked about and keyed by, or null when it is not one this server will fetch.
 *
 * The fragment goes because it never reaches a server anyway — `#section-2` is the same page — and
 * keeping it would split one cache row into as many rows as there are anchors on the page. The query
 * stays: for a great many sites it *is* the page.
 */
internal fun normaliseUrl(raw: String): String? {
    val trimmed = raw.trim().substringBefore('#')
    if (trimmed.isEmpty() || trimmed.length > 2048) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    val host = uri.host?.lowercase() ?: return null
    if (!isWellFormedHost(host.removePrefix("www."))) return null
    return trimmed
}

/** The cache key. See [PreviewCache] for why the address itself is not stored. */
internal fun hashOf(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * [bytes] as text, in whatever encoding the page is actually written in.
 *
 * UTF-8 is the answer for most of the web and the wrong answer for a good deal of the Russian-language
 * part of it, where windows-1251 is still served — and a title that arrives as mojibake is worse than no
 * title at all, because it is drawn on the card as if it were right. The header is believed first, then
 * the document's own `<meta charset>`, which is found by reading the bytes as Latin-1: every encoding
 * this matters for is ASCII-compatible in the tag names, so the declaration is legible before the
 * encoding it declares is known.
 */
internal fun decodeHtml(bytes: ByteArray, contentType: String): String {
    val fromHeader = charsetIn(contentType)
    if (fromHeader != null) return String(bytes, fromHeader)

    val ascii = String(bytes, Charsets.ISO_8859_1)
    val head = ascii.take(4096)
    val declared = CHARSET_META.find(head)?.groupValues?.get(1)
        ?: charsetIn(HTTP_EQUIV_META.find(head)?.groupValues?.get(1) ?: "")?.name()
    val charset = declared?.let { supportedCharset(it) } ?: Charsets.UTF_8
    return if (charset == Charsets.ISO_8859_1) ascii else String(bytes, charset)
}

private val CHARSET_META = Regex("""<meta[^>]*\bcharset\s*=\s*["']?([A-Za-z0-9_:.-]+)""", RegexOption.IGNORE_CASE)
private val HTTP_EQUIV_META = Regex(
    """<meta[^>]*http-equiv\s*=\s*["']?content-type["']?[^>]*content\s*=\s*["']([^"']*)["']""",
    RegexOption.IGNORE_CASE,
)

private fun charsetIn(contentType: String): Charset? =
    Regex("""charset\s*=\s*["']?([A-Za-z0-9_:.-]+)""", RegexOption.IGNORE_CASE)
        .find(contentType)?.groupValues?.get(1)
        ?.let { supportedCharset(it) }

private fun supportedCharset(name: String): Charset? =
    runCatching { if (Charset.isSupported(name)) Charset.forName(name) else null }.getOrNull()

/**
 * What a page says about itself, read out of its `<head>`.
 *
 * Open Graph first, then Twitter's cards, then the plain old `<title>` and `<meta name=description>` —
 * best-first, like [FaviconService]'s chain, and for the same reason: `og:title` is what the author chose
 * to show when the page is quoted elsewhere, while `<title>` is what the browser tab says, which is often
 * the same words with the site's name bolted on the end. Either is far better than nothing.
 *
 * [baseUrl] is where the redirects ended: a relative `og:image` is resolved against it, since a card
 * pointing an `<img>` at `/static/cover.png` would point at the extension's own origin.
 */
internal fun parseOpenGraph(html: String, baseUrl: String): LinkPreview {
    val head = headOf(html)

    val metas = mutableMapOf<String, String>()
    META_TAG.findAll(head).forEach { tag ->
        val attributes = attributesOf(tag.value)
        val key = (attributes["property"] ?: attributes["name"])?.lowercase() ?: return@forEach
        val content = attributes["content"]?.let(::decodeEntities)?.trim()?.ifBlank { null } ?: return@forEach
        // First one wins: a page listing several `og:image` means the first is the one it leads with.
        metas.putIfAbsent(key, content)
    }

    val title = metas["og:title"]
        ?: metas["twitter:title"]
        ?: TITLE_TAG.find(head)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()?.ifBlank { null }
    val description = metas["og:description"]
        ?: metas["twitter:description"]
        ?: metas["description"]
    val image = listOfNotNull(
        metas["og:image:secure_url"],
        metas["og:image"],
        metas["og:image:url"],
        metas["twitter:image"],
        metas["twitter:image:src"],
    ).firstNotNullOfOrNull { imageUrl(it, baseUrl) }

    return LinkPreview(
        title = title?.take(MAX_TITLE),
        description = description?.take(MAX_DESCRIPTION),
        image = image,
    )
}

/**
 * A picture address the client can actually draw, or null.
 *
 * HTTPS only, and not out of strictness: both clients are served over HTTPS (`https://stramus.space` and
 * `chrome-extension://`), so an `http:` image is mixed content that the browser blocks before it is ever
 * drawn — a broken frame instead of a card, cached for a fortnight. The host check is the same one the
 * fetches themselves pass: nobody's card should have this app quietly requesting `http://192.168.1.1/`.
 */
private fun imageUrl(raw: String, baseUrl: String): String? {
    val absolute = runCatching { URI(baseUrl).resolve(raw.trim()).toString() }.getOrNull() ?: return null
    if (absolute.length > 2048) return null
    val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (!isWellFormedHost(host.removePrefix("www."))) return null
    return absolute
}

private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ATTRIBUTE = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")

/**
 * Everything down to `</head>` — or to `<body`, for a page that never closed its head, or the whole of
 * what arrived, for one truncated at [ServerConfig.maxPreviewBytes] before either.
 *
 * Stopping here means a `<meta>` inside the body — an advertisement's, a syndicated comment's — cannot
 * claim to describe the page.
 */
private fun headOf(html: String): String {
    val end = listOf("</head", "<body")
        .mapNotNull { marker -> html.indexOf(marker, ignoreCase = true).takeIf { it >= 0 } }
        .minOrNull()
    return if (end == null) html else html.substring(0, end)
}

/** A tag's attributes, lowercased by name. Nothing here is a parser: it is the three shapes a `<meta>` comes in. */
private fun attributesOf(tag: String): Map<String, String> =
    ATTRIBUTE.findAll(tag).associate { match ->
        val raw = match.groupValues[2]
        val quoted = raw.length >= 2 && (raw.first() == '"' || raw.first() == '\'')
        match.groupValues[1].lowercase() to if (quoted) raw.substring(1, raw.length - 1) else raw
    }

/**
 * The handful of entities that actually turn up in a title — `&amp;`, a typographic dash, a non-breaking
 * space — plus the numeric forms. Not a complete table, and it does not need to be: what is left over is
 * shown as it was written, which for an unknown entity is exactly what a browser's own fallback looks like.
 */
private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: match.value

            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePoint(it) } ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

private val ENTITY = Regex("""&(#[xX]?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]{1,31});""")

/** A code point as text, refusing the ones that would put a control character into a card's title. */
private fun codePoint(value: Int): String? =
    if (value in 32..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "shy" to "", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
    "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "bull" to "•",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "euro" to "€", "pound" to "£",
)

/** Long enough for any headline anyone writes, short enough that a hostile page cannot fill the cache with one row. */
private const val MAX_TITLE = 200
private const val MAX_DESCRIPTION = 400
