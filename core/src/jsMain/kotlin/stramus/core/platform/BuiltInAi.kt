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
 * The options object that declares [language] as what the answers will be in — the shape Chrome
 * recognises (`expectedOutputs: [{ type, languages }]`; the `outputLanguage` of the other built-in APIs
 * is silently ignored here). A null [language] declares nothing, which is the last rung of the ladder.
 */
private fun expectedOutputs(language: String?): dynamic {
    val options: dynamic = js("({})")
    if (language != null) {
        options.expectedOutputs = arrayOf(json("type" to "text", "languages" to arrayOf(language)))
    }
    return options
}

/**
 * What to declare the answers will be in, best first.
 *
 * The interface language is what the questions and the system prompt are written in, so it is what the
 * answers will come back in — but Russian, which this app otherwise speaks, is not on Chrome's list, and
 * declaring it does not merely warn: `availability()` answers `unavailable` for it, and `create()`
 * refuses. English is declared in its place. That does not *make* the model answer in English — the
 * declaration is a claim about the output, not an instruction, and the question it is answering is still
 * written in Russian.
 *
 * The list ends in `null`: nothing declared. That is the old behaviour, console error and all — worth
 * keeping as the last rung, because an assistant that complains is better than one that refuses to open.
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
        // Asked with the declaration the session will carry, for two reasons. It is the question we
        // actually mean — "is there a model that will answer in this language?", which Chrome answers
        // differently from "is there a model?" (a language it cannot attest turns `downloadable` into
        // `unavailable`). And an undeclared call is itself a LanguageModel request, which Chrome meets
        // with a console error; this one runs on every new tab, long before anyone asks anything.
        val declared = runCatching {
            api.availability(expectedOutputs(outputLanguages().first()))
                .unsafeCast<Promise<String>>().await()
        }
        // A Chrome that will not take the argument at all still gets asked the plain question, so an
        // older browser with a model keeps its assistant.
        val state = declared.getOrNull()
            ?: runCatching { api.availability().unsafeCast<Promise<String>>().await() }.getOrNull()
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
            val options: dynamic = expectedOutputs(outputLanguage)
            options.initialPrompts = arrayOf(json("role" to "system", "content" to systemPrompt))
            // Fired only on the first session on this machine, while the model itself is fetched;
            // `loaded` is a fraction of one.
            options.monitor = { monitor: dynamic ->
                monitor.addEventListener("downloadprogress") { event: dynamic ->
                    onDownloadProgress((event.loaded as? Number)?.toDouble() ?: 0.0)
                }
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
