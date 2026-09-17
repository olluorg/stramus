package stramus.core.topics

import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import kotlin.js.console

/**
 * The browser's own model asked to put [collections] into a few broad groups — see [groupingPrompt] for
 * what it is asked and what an answer has to survive.
 *
 * Only a model that is already on the machine is asked: [AiAvailability.DOWNLOADABLE] means several hundred
 * megabytes fetched before the first answer, and nobody agreed to that in exchange for tidier sidebar
 * headings. Anything that goes wrong — no model, a refusal, a mangled answer — comes back as an empty map,
 * and the caller falls back to what the addresses say (see [siteGroupFor]).
 */
suspend fun modelGroups(
    ai: AiAssistant?,
    systemPrompt: String,
    collections: List<String>,
    /** The app's own language, in English, for the question to name — see [groupingPrompt]. */
    language: String,
): Map<String, String> {
    // Said out loud at every exit, because every one of them looks the same from outside — an empty map
    // and a plan grouped by site — and the difference between "this browser has no model" and "the model
    // answered nonsense" is the difference between a missing feature and a broken one.
    if (ai == null) {
        console.log("[topics] model: none in this browser")
        return emptyMap()
    }
    if (collections.size < 2) {
        console.log("[topics] model: not asked — ${collections.size} collection(s), nothing to group")
        return emptyMap()
    }
    val availability = runCatching { ai.availability() }.getOrNull()
    if (availability != AiAvailability.AVAILABLE) {
        console.log("[topics] model: not asked — availability is $availability (never downloaded for this)")
        return emptyMap()
    }
    val session = runCatching { ai.start(systemPrompt) }.getOrNull()
    if (session == null) {
        console.log("[topics] model: could not open a session")
        return emptyMap()
    }
    return try {
        val answer = runCatching { session.askJson(groupingPrompt(collections, language), groupingSchema()) }
        val text = answer.getOrNull()
        if (text == null) {
            console.log("[topics] model: asked, and it failed to answer (${answer.exceptionOrNull()?.message})")
            return emptyMap()
        }
        val groups = groupsFromAnswer(text, collections)
        if (groups.isEmpty()) console.log("[topics] model: answered, but nothing in it survived checking — $text")
        groups
    } finally {
        session.close()
    }
}
