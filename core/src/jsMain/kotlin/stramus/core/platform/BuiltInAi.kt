package stramus.core.platform

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
        val options: dynamic = js("({})")
        options.initialPrompts = arrayOf(json("role" to "system", "content" to systemPrompt))
        // Fired only on the first session on this machine, while the model itself is fetched; `loaded`
        // is a fraction of one.
        options.monitor = { monitor: dynamic ->
            monitor.addEventListener("downloadprogress") { event: dynamic ->
                onDownloadProgress((event.loaded as? Number)?.toDouble() ?: 0.0)
            }
        }
        return BuiltInSession(api.create(options).unsafeCast<Promise<dynamic>>().await())
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
