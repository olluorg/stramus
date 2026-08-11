@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import stramus.core.ai.TRUSTED_COLLECTIONS_BUDGET
import stramus.core.ai.TriageCollection
import stramus.core.ai.TriageSection
import stramus.core.ai.TriageTab
import stramus.core.ai.batchSchema
import stramus.core.ai.planForBatch
import stramus.core.ai.trustedBatchPromptFitting
import stramus.protocol.AiInventedCollection
import stramus.protocol.AiTriageAssignment
import stramus.protocol.AiTriageRequest
import stramus.protocol.AiTriageResponse

/** The month's hundred are spent. Answered as 429 — see `App.kt`'s `StatusPages`. */
class AiQuotaException(message: String) : RuntimeException(message)

/** What every call to the cloud model is asked from, ahead of the catalog and the tabs themselves. */
private const val TRIAGE_SYSTEM_PROMPT =
    "You sort a person's open browser tabs into their existing collections, preferring an existing " +
        "match over inventing a new one, and leaving a tab out entirely when nothing genuinely fits."

/**
 * What the answer is reserved room for inside the context budget — see [AiProxyService.inputCharBudget].
 *
 * Note what this is *not*: a `max_tokens` on the request. One was sent for a while, sized per tab, and it
 * made every run measurably worse — 50 of 59 tabs placed before it, 45 of 57 after. The arithmetic looks
 * safe (sixty tokens a tab is several times what one JSON item costs) right up until the model is a
 * reasoning one, where the same ceiling covers the reasoning tokens as well and the answer is cut off
 * having barely started. Nothing here is worth that risk: the response's length is already bounded by the
 * schema and by how many tabs were asked about, and a provider's own default ceiling is sized by people
 * who know which model is behind the endpoint. This constant only keeps the *input* budget honest about
 * the fact that input and output share one context window.
 */
private const val RESERVED_RESPONSE_TOKENS = 8192

/**
 * Rough characters per token, used to turn [ServerConfig.openrouterContextTokens] into the character
 * budget `trustedBatchPromptFitting` actually works in. Conservative on purpose — mixed Cyrillic and
 * Latin text tends to tokenize closer to 2 characters a token than English's usual 4, and erring toward
 * *fewer* characters per token is the safe direction: it makes this undershoot a model's real context,
 * never overshoot it.
 */
private const val CHARS_PER_TOKEN_ESTIMATE = 2.5

/** Held back from the input budget for the answer itself — see [RESERVED_RESPONSE_TOKENS] — and for rounding. */
private const val SAFETY_MARGIN_TOKENS = 2_000

/** A floor under [ServerConfig.openrouterContextTokens], so a badly misconfigured value still asks something rather than nothing. */
private const val MIN_INPUT_TOKENS = 4_000

/**
 * At most this share of a call's input budget goes to describing the catalog — see
 * `trustedBatchPromptFitting`'s own doc. Not all of it: an account with an enormous number of collections
 * must still leave *some* room for tabs, or a call would spend its whole budget being introduced to a
 * catalog and never get to ask about anything.
 */
private const val COLLECTIONS_BUDGET_SHARE = 0.7


/**
 * The cloud model, asked on the server rather than the browser — the one way an OpenRouter key can be
 * used at all without shipping it to whoever opens devtools.
 *
 * This is not what the local model is, and does not pretend to be: it is opt-in (off by default — see
 * the client's triage setting), it is only for a signed-in account (there is no other kind of caller
 * that reaches this file, `App.kt` puts it behind `authenticate(BEARER)`), and it costs real money per
 * question, which is the entire reason [ServerConfig.aiMonthlyLimit] exists and is checked *before* the
 * call is made, not merely counted after.
 *
 * ## The catalog lives here, not in the request
 *
 * [AiTriageRequest] carries only tab titles and whatever this run has invented so far — never the
 * collections, the sections, or what is saved in them. Those are read straight out of [AiCatalogService],
 * which is to say out of this same account's own synced rows: the server already has them for sync, and
 * reading them here is both cheaper (nothing about the sidebar has to be re-sent on every batch) and more
 * private (a batch of ten tab titles, not the shape of the whole collection).
 *
 * ## The cache
 *
 * [AiCache] is checked before anything else — before the quota, before OpenRouter is even asked. Its key
 * is a hash of the *built* prompt and its schema, and the prompt is built from the account's live catalog
 * — so a renamed section, a newly saved card, or a plan that invents a collection is automatically a
 * different question, without this cache ever being told the catalog changed. A hit costs the account
 * nothing — not a request to OpenRouter, not one of the month's hundred — which is the whole point:
 * asking the same thing about an unchanged window of tabs twice, because a plan was opened, closed
 * without applying, and opened again, should be free the second time.
 */
class AiProxyService(
    private val db: SuspendDatabase<ServerDb>,
    private val config: ServerConfig,
    private val catalogService: AiCatalogService,
) {
    private val http = HttpClient.newHttpClient()
    private val log = LoggerFactory.getLogger(AiProxyService::class.java)

    suspend fun triage(userId: Uuid, request: AiTriageRequest): AiTriageResponse {
        if (request.tabs.isEmpty()) return AiTriageResponse(emptyList(), emptyList())

        val catalog = catalogService.catalogFor(userId)
        val collections = withInvented(catalog.collections, request.invented)
        val tabs = request.tabs.map { TriageTab(it.id, it.title, it.url) }

        // Priority order: the system prompt (a fixed cost `trustedBatchPromptFitting` accounts for on
        // its own), then the account's whole catalog up to its share of the budget, then as many tabs —
        // in the order the caller sent them, which is the client's own doing (see `cloudTriage`, and
        // `selectByBudget` for what that ordering does and does not guarantee) — as what is left fits.
        val totalBudget = inputCharBudget()
        val collectionsBudget = minOf((totalBudget * COLLECTIONS_BUDGET_SHARE).toInt(), TRUSTED_COLLECTIONS_BUDGET)
        val (prompt, selected) = trustedBatchPromptFitting(tabs, collections, catalog.groups, totalBudget, collectionsBudget)
        val schema = batchSchema()
        val hash = promptHash(prompt, schema)

        val cached = db.suspendAutocommit {
            AiCache.findOne { where { (AiCache.userId eq userId) and (AiCache.promptHash eq hash) } }
        }

        val answer = cached?.response ?: askModel(userId, prompt, schema, hash)

        val assignments = planForBatch(answer, selected, collections, catalog.groups)
        // Counts only, at info: "kept" against "considered" is the one thing that tells "the model itself
        // placed few of these" apart from "the model placed most of them and planForBatch rejected the
        // answer" — indistinguishable from the response alone, and guessed at more than once without it.
        // Numbers say nothing about the account, so they are safe to keep on past the diagnosis.
        //
        // The answer itself goes to debug and no higher. It carries this person's own collection and
        // section names, and a server that writes those into its ordinary log has quietly undone the
        // reason the catalog is read from sync rows rather than shipped around (see the class doc).
        log.info(
            "triage: considered={} kept={} collectionsBudget={} promptLength={}",
            selected.size, assignments.size, collectionsBudget, prompt.length,
        )
        log.debug("triage raw answer: {}", answer)
        return AiTriageResponse(
            assignments = assignments.map { a ->
                AiTriageAssignment(
                    tabId = a.tabId,
                    collectionTitle = a.collectionTitle,
                    collectionId = a.collectionId?.toString(),
                    sectionTitle = a.sectionTitle,
                    sectionId = a.sectionId?.toString(),
                    groupTitle = a.groupTitle,
                )
            },
            consideredTabIds = selected.map { it.id },
        )
    }

    /**
     * How many characters of prompt one call may spend — [ServerConfig.openrouterContextTokens], minus
     * what the answer itself is allowed to cost ([MAX_RESPONSE_TOKENS]) and a safety margin, converted to
     * characters by [CHARS_PER_TOKEN_ESTIMATE]. The context window is spent on the *whole* call, input
     * and output together, which is why the answer's own ceiling has to come out of this budget rather
     * than sit beside it.
     */
    private fun inputCharBudget(): Int {
        val availableTokens = (config.openrouterContextTokens - RESERVED_RESPONSE_TOKENS - SAFETY_MARGIN_TOKENS)
            .coerceAtLeast(MIN_INPUT_TOKENS)
        return (availableTokens * CHARS_PER_TOKEN_ESTIMATE).toInt()
    }

    /** [catalog], with whatever this run has invented so far folded in — see [AiTriageRequest.invented]. */
    private fun withInvented(catalog: List<TriageCollection>, invented: List<AiInventedCollection>): List<TriageCollection> {
        if (invented.isEmpty()) return catalog
        val known = catalog.mapTo(mutableSetOf()) { it.title.trim().lowercase() }
        val fresh = invented
            .filter { it.title.trim().lowercase() !in known }
            .map { inv ->
                TriageCollection(
                    id = null,
                    title = inv.title,
                    inSection = inv.group,
                    sections = inv.sections.map { TriageSection(id = null, title = it) },
                )
            }
        return catalog + fresh
    }

    /** Checked and spent only when the cache above did not already answer the question. */
    private suspend fun askModel(userId: Uuid, prompt: String, schema: String, hash: String): String {
        val apiKey = config.openrouterApiKey.takeIf { it.isNotBlank() }
            ?: throw AccountException(501, "the cloud model is not set up on this server")

        val yearMonth = currentYearMonth()
        // Checked before the call is made, not merely counted after: a request over the limit must not
        // reach OpenRouter at all, or the limit is a number on a screen and not an actual ceiling on
        // what this account costs to run.
        val used = db.suspendAutocommit {
            AiUsage.findOne { where { (AiUsage.userId eq userId) and (AiUsage.yearMonth eq yearMonth) } }
        }?.count ?: 0
        if (used >= config.aiMonthlyLimit) {
            throw AiQuotaException("the monthly limit of ${config.aiMonthlyLimit} cloud requests has been used")
        }

        val text = callOpenRouter(apiKey, prompt, schema)

        // Counted only now, against an answer actually received: a network hiccup or a bad response from
        // OpenRouter costs this account nothing of its hundred. Cached in the same breath, so the very
        // next identical question never has to make the trip at all.
        db.suspendTransaction {
            val existing = AiUsage.findOne { where { (AiUsage.userId eq userId) and (AiUsage.yearMonth eq yearMonth) } }
            if (existing != null) {
                AiUsage.update(AiUsageRow().apply { count = existing.count + 1 }) {
                    where { (AiUsage.userId eq userId) and (AiUsage.yearMonth eq yearMonth) }
                }
            } else {
                AiUsage.insert(
                    AiUsageRow().apply {
                        this.userId = userId
                        this.yearMonth = yearMonth
                        count = 1
                    },
                )
            }
            AiCache.insert(
                AiCacheRow().apply {
                    this.userId = userId
                    promptHash = hash
                    response = text
                    createdAt = Clock.System.now()
                },
            )
        }

        return text
    }

    /** The identity of a question: the built prompt and the shape its answer must have, and nothing else. */
    private fun promptHash(prompt: String, schema: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(prompt.toByteArray())
        digest.update(0.toByte())
        digest.update(schema.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @OptIn(ExperimentalTime::class)
    private fun currentYearMonth(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return "${now.year}-${now.monthNumber.toString().padStart(2, '0')}"
    }

    /**
     * [ServerConfig.openrouterModel], or [ServerConfig.openrouterFallbackModel] if that call could not
     * be completed at all — any exception [callModel] throws, network trouble and a malformed or empty
     * answer alike. Not tried for a question the first model *answered*: a bad plan is the caller's to
     * reject and retry on its own account, the same as any other batch a model gets wrong, not something
     * this file spends a second model on.
     */
    private suspend fun callOpenRouter(apiKey: String, prompt: String, schema: String): String =
        runCatching { callModel(apiKey, prompt, schema, config.openrouterModel) }
            .getOrElse { first ->
                runCatching { callModel(apiKey, prompt, schema, config.openrouterFallbackModel) }
                    // Neither model answered — a network failure reaching [ServerConfig.openrouterUrl] is
                    // the common way this happens (a wrong URL, a proxy that is down), and it must not
                    // reach the caller as a raw `IOException`: nothing here catches that generically, and
                    // it would otherwise surface as a bare 500 with no `ApiError` body to show on screen.
                    // [second] is kept as the cause rather than folded into the message alone — [describe]
                    // covers the common network failures, but the full chain still belongs in the log for
                    // whatever it does not recognise.
                    .getOrElse { second ->
                        // [describe] already returns a complete sentence either way (an `AccountException`
                        // from [callModel] carries its own "the cloud model did not answer …"; a network
                        // failure gets one built here) — nothing is gained by prefixing it with the same
                        // words again, and it only made the DNS case above read as "did not answer:
                        // could not resolve …", which does not parse as one sentence.
                        val secondText = describe(second)
                        val firstText = describe(first)
                        val detail = if (firstText == secondText) secondText else "$secondText (the other model: $firstText)"
                        throw AccountException(502, detail, cause = second)
                    }
            }

    /**
     * A human-readable reason for a call to [ServerConfig.openrouterUrl] failing — [Throwable.message]
     * where that already says something ([callModel]'s own [AccountException]s do), but a name and the
     * configured host for the network failures that do not: a bare `ConnectException` wrapping an
     * `UnresolvedAddressException` carries no message of its own at any level, and "null" or a Java class
     * name is not something whoever is testing STRAMUS_OPENROUTER_URL can act on. This is what turned
     * "ConnectException" into "could not resolve routerai.ru — check STRAMUS_OPENROUTER_URL" the first
     * time this file needed it, and what it is here to keep doing for the next unrecognised host.
     */
    private fun describe(e: Throwable): String {
        e.message?.let { return it }
        val chain = generateSequence(e) { it.cause }.toList()
        val host = runCatching { URI(config.openrouterUrl).host }.getOrNull() ?: config.openrouterUrl
        return when {
            chain.any { it is UnknownHostException || it is UnresolvedAddressException } ->
                "could not resolve \"$host\" — check STRAMUS_OPENROUTER_URL is spelled correctly and that host is reachable from this server"
            chain.any { it is ConnectException } ->
                "could not connect to \"$host\" — it may be down, blocked, or unreachable from this server"
            chain.any { it is HttpTimeoutException } ->
                "\"$host\" did not answer in time"
            else -> e::class.simpleName ?: "unknown error"
        }
    }

    /** One request, one answer — no streaming, no history. */
    private suspend fun callModel(apiKey: String, prompt: String, schema: String, model: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", TRIAGE_SYSTEM_PROMPT) })
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                },
            )
            put(
                "response_format",
                buildJsonObject {
                    put("type", "json_schema")
                    put(
                        "json_schema",
                        buildJsonObject {
                            put("name", "answer")
                            put("strict", true)
                            put("schema", Json.parseToJsonElement(schema))
                        },
                    )
                },
            )
            // No `max_tokens` on purpose — see [RESERVED_RESPONSE_TOKENS] for what sending one cost.
        }

        val httpRequest = HttpRequest.newBuilder(URI(config.openrouterUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://stramus.space")
            .header("X-Title", "stramus")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw AccountException(502, "the cloud model did not answer (HTTP ${response.statusCode()})")
        }

        val parsed = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val content = parsed?.get("choices")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.let { it as? JsonPrimitive }?.jsonPrimitive?.content
        content ?: throw AccountException(502, "the cloud model's answer had no content")
    }
}
