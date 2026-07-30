package stramus.core.platform

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.js.Promise
import kotlin.js.json

/**
 * The browser's built-in model (Chrome's Prompt API: `LanguageModel.availability()`, `.create()`,
 * `session.promptStreaming()`), reached through `dynamic` rather than an `external` declaration —
 * a declared global that is not there throws on first mention, and "is it there at all" is precisely
 * the question this file exists to answer.
 */
private fun languageModel(): dynamic = js("(typeof LanguageModel !== 'undefined' ? LanguageModel : null)")

/**
 * The languages Chrome's built-in model will attest its output in. Asking for anything else is refused
 * at `create()`, and asking for nothing at all is answered with a console *error* — visible to the user
 * on `chrome://extensions`, which is not a place an extension should be leaving complaints.
 */
private val ATTESTED_OUTPUT_LANGUAGES = setOf("de", "en", "es", "fr", "ja")

/**
 * What to declare the answers will be in, best first.
 *
 * The interface language is what the questions and the system prompt are written in, so it is what the
 * answers will come back in — but Russian, which this app otherwise speaks, is not on Chrome's list.
 * English is the second candidate rather than the only one because declaring it does not *make* the
 * model answer in English; it is a claim about the output, and the honest claim is made first, for the
 * browsers that accept it.
 *
 * The list ends in `null`: a session with nothing declared. That is today's behaviour, console error
 * and all — worth keeping as the last rung, because an assistant that complains is better than an
 * assistant that refuses to open.
 */
private fun outputLanguages(): List<String?> {
    val ui = document.documentElement?.getAttribute("lang")?.take(2)?.lowercase()
    return listOfNotNull(ui?.takeIf { it in ATTESTED_OUTPUT_LANGUAGES }, "en", null).distinct()
}

/**
 * The model, if this browser has one. Null in every browser that does not (and in Chrome without the
 * hardware for it), which is what keeps the AI out of the UI where it cannot work: the search box
 * offers "ask the model" only when this returns something.
 */
fun builtInAi(): AiAssistant? = if (languageModel() == null) null else BuiltInAi

private object BuiltInAi : AiAssistant {

    // The Prompt API names no model, so this names the one Chrome ships behind it. It is what the
    // settings page tells the user is answering them.
    override val name = "Gemini Nano"

    // The strings this returns have changed across Chrome versions ("readily" became "available",
    // "after-download" became "downloadable"), so both vocabularies are read, and anything unknown is
    // taken as usable — the create() call is the real test, and it is guarded.
    override suspend fun availability(): AiAvailability {
        val api = languageModel() ?: return AiAvailability.UNAVAILABLE
        val state = runCatching { api.availability().unsafeCast<Promise<String>>().await() }.getOrNull()
        return when (state) {
            null, "unavailable", "no" -> AiAvailability.UNAVAILABLE
            "downloadable", "after-download" -> AiAvailability.DOWNLOADABLE
            "downloading" -> AiAvailability.DOWNLOADING
            else -> AiAvailability.AVAILABLE
        }
    }

    override suspend fun start(systemPrompt: String, onDownloadProgress: (Double) -> Unit): AiSession {
        val api = languageModel() ?: error("no built-in model in this browser")

        fun options(outputLanguage: String?): dynamic {
            val options: dynamic = js("({})")
            options.initialPrompts = arrayOf(json("role" to "system", "content" to systemPrompt))
            // Fired only on the first session on this machine, while the model itself is fetched;
            // `loaded` is a fraction of one.
            options.monitor = { monitor: dynamic ->
                monitor.addEventListener("downloadprogress") { event: dynamic ->
                    onDownloadProgress((event.loaded as? Number)?.toDouble() ?: 0.0)
                }
            }
            if (outputLanguage != null) {
                options.expectedOutputs =
                    arrayOf(json("type" to "text", "languages" to arrayOf(outputLanguage)))
            }
            return options
        }

        // Each rung is tried in turn; a Chrome that refuses the language falls to the next one rather
        // than to no assistant at all. The last one carries no declaration and cannot be refused for
        // this reason, so the loop always has an answer.
        val candidates = outputLanguages()
        for ((index, language) in candidates.withIndex()) {
            val created = runCatching {
                api.create(options(language)).unsafeCast<Promise<dynamic>>().await()
            }
            // Held in a val rather than chained: `getOrNull()` on a Result<dynamic> is dynamic, and a
            // `?.let { }` on it would be resolved as a member call on the JS object, not as stdlib.
            val session = created.getOrNull()
            if (session != null) return BuiltInSession(session)
            // The last rung failing is a real failure — no model, no room, no permission — and belongs
            // to the caller, who already guards this call.
            if (index == candidates.lastIndex) created.getOrThrow()
        }
        error("unreachable: the candidate list ends in a session with nothing declared")
    }
}

private class BuiltInSession(private val session: dynamic) : AiSession {

    override fun ask(question: String): Flow<String> = flow {
        val reader = session.promptStreaming(question).getReader()
        var answer = ""
        try {
            while (true) {
                val chunk = reader.read().unsafeCast<Promise<dynamic>>().await()
                if (chunk.done.unsafeCast<Boolean?>() == true) break
                val text = chunk.value.unsafeCast<String?>() ?: continue
                // Chrome has streamed the answer both ways across versions: as the piece just written,
                // and as everything written so far. Either is recognised, and what this Flow emits is
                // always the whole answer so far — which is what a redraw needs.
                answer = if (answer.isNotEmpty() && text.startsWith(answer)) text else answer + text
                emit(answer)
            }
        } finally {
            // The question was abandoned (the panel closed, another question typed): stop generating.
            runCatching { reader.cancel() }
        }
    }

    override suspend fun askJson(question: String, schema: String): String {
        val options: dynamic = js("({})")
        // The schema is carried as text so that `core`'s common code — where the schemas are written —
        // needs no JS object to describe one; this is the one place that has to be JS anyway.
        options.responseConstraint = JSON.parse<Any>(schema)
        return session.prompt(question, options).unsafeCast<Promise<String>>().await()
    }

    override suspend fun clone(): AiSession =
        BuiltInSession(session.clone().unsafeCast<Promise<dynamic>>().await())

    override fun close() {
        runCatching { session.destroy() }
    }
}
