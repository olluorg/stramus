package stramus.server

import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import java.time.Duration as JavaDuration

class FaviconCacheRow : Entity() {
    var host by FaviconCache.host
    var mime by FaviconCache.mime
    var body by FaviconCache.body
    var fetchedAt by FaviconCache.fetchedAt
}

/**
 * One row per host, and the *only* thing this server keeps about icons.
 *
 * There is no user column, and its absence is the point. The clients ask for an icon anonymously, so a row
 * says "somebody, once, had a link to this host" and nothing more — the same sentence for a host asked
 * about by one person and by a thousand. Correlating that back to an account would need a column that is
 * deliberately not here.
 *
 * [body] is null for a host that was looked up and genuinely has no icon. That negative answer is worth
 * storing precisely because it is expensive: without it, every load of a page full of iconless links walks
 * the whole fetch chain again, for every one of them, forever.
 */
object FaviconCache : Table<ServerDb, FaviconCacheRow>("favicon_cache", ::FaviconCacheRow) {
    val host by Column.Text().primaryKey()
    val mime by Column.Text().nullable()
    val body by Column.Text().nullable() // base64; null together with mime means "no icon exists"
    val fetchedAt by Column.Instant()

    init { host; mime; body; fetchedAt }
}

/**
 * What the server has to say about a host's icon. The three cases are not decoration — the client's chain
 * behaves differently for each, and collapsing [Absent] into [Unavailable] is exactly the bug that makes a
 * proxy pointless (see `Favicon.kt`, `IconSourceKind.AUTHORITATIVE`).
 */
sealed interface FaviconResult {
    /** The icon, as bytes and the MIME type they came under. */
    data class Found(val mime: String, val bytes: ByteArray) : FaviconResult {
        // ByteArray equality is identity, which would make two equal results unequal; the data class is
        // here for the constructor, not for comparison, and this keeps the compiler from warning about it.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Looked, and there is no icon. The client stops here and draws its own letter tile. */
    data object Absent : FaviconResult

    /** Nothing could be reached. The client falls through to the icon services on its own. */
    data object Unavailable : FaviconResult
}

/**
 * Site icons, fetched here rather than in the browser.
 *
 * The clients could ask google.com or favicone.com themselves — the web app used to, and that is the whole
 * problem: asking a public icon service about a host tells it that whoever is asking has that host saved.
 * One request per host, from the user's own address, is a readable list of somebody's bookmarks handed to a
 * third party. Fetched from here instead, those services see one server asking about a host, with no way to
 * tell which user wanted it or whether anyone wanted it twice.
 *
 * What it costs is that *this* server learns the host. For a signed-in user that is nothing new — their
 * cards, URLs and all, are already in `sync_rows`. For everyone else it is a real disclosure, and the answer
 * is the shape of [FaviconCache]: anonymous, unlogged, and keyed by nothing but the host.
 *
 * The chain runs best-first: the site's own icon (which is the correct one, and which no third party is
 * involved in), then the services, which are a guess about a site rather than the site's own answer.
 */
class FaviconService(
    private val db: SuspendDatabase<ServerDb>,
    private val config: ServerConfig,
) {

    private val http: HttpClient = HttpClient.newBuilder()
        // Never automatically: a redirect is a URL the caller did not ask for, and following one without
        // re-running the address check below is how a guard against private addresses is walked around.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(JavaDuration.ofSeconds(5))
        .build()

    /** Outbound fetches happen at most this many at a time, so this never becomes somebody's load generator. */
    private val outbound = Semaphore(8)

    /** Hosts being fetched right now: a page of two hundred cards is not two hundred fetches of one host. */
    private val inFlight = mutableMapOf<String, CompletableDeferred<FaviconResult>>()
    private val inFlightLock = Mutex()

    /**
     * The icon for [host], from the cache when it is still fresh and from the network when it is not.
     *
     * [host] is whatever a client sent, which is to say untrusted: [isFetchableHost] is what stands between
     * this and a caller using the server to probe addresses only the server can reach.
     */
    /**
     * [allowFetch] is asked only when the answer is not already here — it is the caller's rate limit, and
     * rationing *misses* is what matters: a hit costs a row read, while a miss makes this server go and
     * fetch a host somebody else chose. Refused, the call comes back [FaviconResult.Unavailable], which
     * sends the client around us to the icon services rather than leaving it with no icon at all.
     */
    suspend fun iconFor(rawHost: String, allowFetch: () -> Boolean = { true }): FaviconResult {
        val host = rawHost.trim().lowercase().removePrefix("www.")
        if (!isWellFormedHost(host)) return FaviconResult.Absent

        cached(host)?.let { return it }
        if (!allowFetch()) return FaviconResult.Unavailable

        // One fetch per host, however many callers are waiting on it.
        val (deferred, isOwner) = inFlightLock.withLock {
            val existing = inFlight[host]
            if (existing != null) existing to false else CompletableDeferred<FaviconResult>().also { inFlight[host] = it } to true
        }
        if (!isOwner) return deferred.await()

        val result = try {
            // Re-check under the in-flight claim: another caller may have finished between the read above
            // and here, and refetching what was just cached is the stampede this is here to prevent.
            cached(host) ?: outbound.withPermit { fetchAndStore(host) }
        } catch (_: Throwable) {
            FaviconResult.Unavailable
        } finally {
            inFlightLock.withLock { inFlight.remove(host) }
        }
        deferred.complete(result)
        return result
    }

    /** The stored answer for [host], if there is one and it has not gone stale. */
    private suspend fun cached(host: String): FaviconResult? {
        val row = db.suspendAutocommit { FaviconCache.findOne { where { FaviconCache.host eq host } } } ?: return null
        val age = Clock.System.now() - row.fetchedAt
        val body = row.body
        return when {
            // A negative answer is trusted for less long than a positive one: a site that has no icon today
            // may well have one next month, and nothing tells us when it does.
            body == null -> if (age < config.faviconNegativeTtl) FaviconResult.Absent else null
            age < config.faviconTtl -> FaviconResult.Found(row.mime ?: "image/png", Base64.getDecoder().decode(body))
            else -> null
        }
    }

    /**
     * Walk the chain and keep what it finds.
     *
     * The distinction that matters is between "every source said there is no icon" and "no source could be
     * reached": the first is an answer, cached and handed to the client as [FaviconResult.Absent]; the
     * second is a failure of this server, cached as nothing at all, and told to the client as
     * [FaviconResult.Unavailable] so it can go around us.
     */
    private suspend fun fetchAndStore(host: String): FaviconResult {
        if (!isFetchableHost(host)) {
            // Not reachable and never will be — a private address, or a name that does not resolve. That is
            // an answer, not a failure, and worth caching so it is not resolved again on every load.
            store(host, null)
            return FaviconResult.Absent
        }

        var anythingBroke = false
        for (source in sourcesFor(host)) {
            when (val outcome = fetchImage(source.url)) {
                is FaviconResult.Found -> {
                    // A service that answers for *every* host answers for hosts it knows nothing about too,
                    // with a stand-in globe. Cached, that globe would sit where the real icon belongs for a
                    // month; drawn, it is worse than the letter tile the client would draw instead.
                    if (!isSourcePlaceholder(source, outcome.bytes)) {
                        store(host, outcome)
                        return outcome
                    }
                }

                FaviconResult.Absent -> Unit
                FaviconResult.Unavailable -> anythingBroke = true
            }
        }

        // Every source was asked and none of them had it. Say so, and remember saying so.
        if (!anythingBroke) {
            store(host, null)
            return FaviconResult.Absent
        }
        return FaviconResult.Unavailable
    }

    private suspend fun store(host: String, found: FaviconResult.Found?) {
        val row = FaviconCacheRow().apply {
            this.host = host
            this.mime = found?.mime
            this.body = found?.let { Base64.getEncoder().encodeToString(it.bytes) }
            this.fetchedAt = Clock.System.now()
        }
        runCatching {
            db.suspendTransaction {
                FaviconCache.deleteWhere { where { FaviconCache.host eq host } }
                FaviconCache.insert(row)
            }
        }
    }

    /** Where a host's icon may be read from, best first. */
    private fun sourcesFor(host: String): List<IconOrigin> = listOf(
        // The site's own answer, and the only one that is not a guess. No third party is involved, and the
        // site learns only that somebody asked for its icon — which is what a browser visiting it does anyway.
        IconOrigin("https://$host/favicon.ico"),
        // favicone answers 200 with a fixed grey stand-in for a host it does not know, so the only way to
        // tell that apart from a real icon is to know what the stand-in looks like — hence the probe.
        IconOrigin("https://favicone.com/$host?s=64", sentinelKey = "favicone"),
        // Google needs no probe: it redirects to gstatic, which answers **404** — with an image body, a
        // letter tile generated from the domain — when it has no real icon. The status is the signal, and
        // the body is different for every host, so comparing bytes would match nothing anyway. That 404 is
        // read as [FaviconResult.Absent] below, which is what ends the chain and lets the client draw its
        // own letter tile rather than cache Google's.
        IconOrigin("https://www.google.com/s2/favicons?domain=$host&sz=64"),
    )

    /**
     * A source, and whether it is one of those that answers for every host whether it knows it or not.
     * [sentinelKey] names the probe that finds out what its "I do not know this host" reply looks like.
     */
    private data class IconOrigin(val url: String, val sentinelKey: String? = null)

    /** The bytes a source hands back for a host that certainly has no icon, learned once and remembered. */
    private val sentinels = mutableMapOf<String, ByteArray?>()
    private val sentinelLock = Mutex()

    private suspend fun isSourcePlaceholder(source: IconOrigin, bytes: ByteArray): Boolean {
        val key = source.sentinelKey ?: return false
        val sentinel = sentinelLock.withLock {
            if (sentinels.containsKey(key)) {
                sentinels[key]
            } else {
                // `.invalid` is reserved by RFC 2606 and resolves nowhere, so whatever comes back for it is
                // the source's stand-in rather than anybody's icon.
                val probeUrl = source.url.replace(hostInUrl(source.url), "probe-${System.nanoTime()}.invalid")
                when (val probe = fetchImage(probeUrl)) {
                    // A probe that could not reach the service tells us nothing. The key is left unset so the
                    // next call tries again, rather than filtering nothing forever over one bad minute.
                    is FaviconResult.Unavailable -> null
                    is FaviconResult.Found -> probe.bytes.also { sentinels[key] = it }
                    is FaviconResult.Absent -> null.also { sentinels[key] = null }
                }
            }
        } ?: return false
        return sentinel.contentEquals(bytes)
    }

    /** The host inside a source URL, so a probe can be built by swapping it for one that does not exist. */
    private fun hostInUrl(url: String): String =
        url.substringAfter("://").substringBefore('/').let { authority ->
            // favicone puts the host in the path and google in a query parameter, so the host to replace is
            // whichever of the two the template actually carries.
            when {
                "domain=" in url -> url.substringAfter("domain=").substringBefore('&')
                authority == "favicone.com" -> url.substringAfter("favicone.com/").substringBefore('?')
                else -> authority
            }
        }

    /**
     * GET [url] and return it if it is an image.
     *
     * Redirects are followed by hand, up to [MAX_HOPS], because every hop is a new address and every new
     * address has to pass [isFetchableHost] again — a site that answers `/favicon.ico` with a redirect to
     * `http://127.0.0.1/` would otherwise have this server fetch it.
     */
    private suspend fun fetchImage(url: String, hops: Int = MAX_HOPS): FaviconResult = withContext(Dispatchers.IO) {
        var current = url
        repeat(hops) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return@withContext FaviconResult.Absent
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if ((scheme != "https" && scheme != "http") || host.isNullOrBlank()) return@withContext FaviconResult.Absent
            if (!isFetchableHost(host)) return@withContext FaviconResult.Absent

            val request = HttpRequest.newBuilder(uri)
                .timeout(JavaDuration.ofSeconds(5))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*")
                .GET()
                .build()

            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
                .getOrElse { return@withContext FaviconResult.Unavailable }

            val status = response.statusCode()
            when {
                status in 300..399 -> {
                    val location = response.headers().firstValue("location").orElse(null)
                    response.body().close()
                    current = location?.let { runCatching { uri.resolve(it).toString() }.getOrNull() }
                        ?: return@withContext FaviconResult.Absent
                }

                status == 200 -> {
                    val mime = response.headers().firstValue("content-type").orElse("")
                        .substringBefore(';').trim().lowercase()
                    // A host that answers every path with its 200 "page not found" is common enough that the
                    // status alone means little; what makes this an icon is that it arrived as one.
                    if (!mime.startsWith("image/")) {
                        response.body().close()
                        return@withContext FaviconResult.Absent
                    }
                    val bytes = response.body().use { it.readNBytes(config.maxFaviconBytes + 1) }
                    return@withContext when {
                        bytes.isEmpty() || bytes.size > config.maxFaviconBytes -> FaviconResult.Absent
                        else -> FaviconResult.Found(mime, bytes)
                    }
                }

                // 5xx is the site having a bad day rather than having no icon; anything else (404 above all)
                // is an answer, and a settled one.
                status >= 500 -> {
                    response.body().close()
                    return@withContext FaviconResult.Unavailable
                }

                else -> {
                    response.body().close()
                    return@withContext FaviconResult.Absent
                }
            }
        }
        FaviconResult.Absent
    }

    private companion object {
        const val MAX_HOPS = 3

        /** Honest about who is asking, and points at the page that explains why. */
        const val USER_AGENT = "stramus-favicon/1.0 (+https://stramus.space/privacy.html)"
    }
}

/**
 * How many icon fetches an address may cause, per minute, before it is told to go and ask somebody else.
 *
 * A minute-wide bucket rather than a proper sliding window: the thing being protected against is one caller
 * walking a list of hosts through this server, and for that the difference between the two is nothing. It is
 * in memory, so a restart forgives everyone — also fine, for the same reason.
 */
class MissBudget(private val perMinute: Int) {

    private val counts = mutableMapOf<String, Int>()
    private var minute = 0L

    @Synchronized
    fun take(caller: String): Boolean {
        val now = Clock.System.now().epochSeconds / 60
        if (now != minute) {
            minute = now
            counts.clear()
        }
        val used = counts.getOrElse(caller) { 0 }
        if (used >= perMinute) return false
        counts[caller] = used + 1
        return true
    }
}

/**
 * Whether [host] even looks like a public hostname — before anything is resolved, let alone fetched.
 *
 * A client is free to send nonsense, and some of that nonsense is dangerous: `localhost`, a bare IP address,
 * something with a colon or a slash smuggled into it. This is the cheap half of the check; [isFetchableHost]
 * is the half that costs a DNS lookup.
 */
internal fun isWellFormedHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (host.any { it.isWhitespace() || it in ":/@?#\\[]" }) return false
    val labels = host.split('.')
    // At least two labels, so `localhost` and every other bare name is out, and a trailing label that is not
    // a number, so `93.184.216.34` is out too: an IP address is not a host anyone saved a link to.
    if (labels.size < 2) return false
    if (labels.last().all { it.isDigit() }) return false
    if (labels.last().lowercase() in setOf("local", "internal", "localhost", "invalid", "test", "example")) return false
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

/**
 * Whether [host] resolves to an address on the public internet.
 *
 * This is the server-side request forgery guard, and it is the reason this endpoint can be anonymous at all.
 * Without it, `?host=metadata.something.internal` — or any name an attacker controls that resolves to
 * 169.254.169.254 — turns this into a way to read things only the server can reach and have them handed back
 * as an image.
 *
 * It resolves the name here and the HTTP client resolves it again a moment later, so a name that changes its
 * answer between the two would slip past. Closing that properly means owning the socket, which is a great
 * deal of machinery for an icon; the exposure left is a fetch of a private address whose *bytes* still have
 * to be a valid image under [ServerConfig.maxFaviconBytes] to come back at all.
 */
internal fun isFetchableHost(host: String): Boolean {
    if (!isWellFormedHost(host)) return false
    val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
    if (addresses.isEmpty()) return false
    return addresses.none { address ->
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            // fc00::/7, IPv6's private range, which the java.net predicates above do not cover.
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
}
