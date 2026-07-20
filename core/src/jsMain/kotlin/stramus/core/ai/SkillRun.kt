package stramus.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import stramus.core.model.Card
import stramus.core.platform.AiAssistant
import stramus.core.platform.ContentFetch

/**
 * A skill running: the pages being read, then the answer being written.
 *
 * A fetching skill spends its first seconds on the network, not the model, and a spinner that says
 * nothing for that whole time reads as a hang. So the reading is reported page by page — the model
 * only starts once there is something to read — and then the answer streams the same way the chat's
 * does: each value is the whole answer so far, for a UI that simply redraws the latest.
 */
sealed interface SkillStep {

    /** [done] of [total] pages fetched. Emitted only for a [SkillSource.FETCH] skill, before the model. */
    data class Fetching(val done: Int, val total: Int) : SkillStep

    /** The answer as it is written — the whole of it each time, so the log is a redraw, not an append. */
    data class Answer(val text: String) : SkillStep
}

/**
 * Run [def] over [cards] — the cards of the skill's own section — and stream what happens. The context
 * is built first — the section as it stands, or the pages behind its links fetched through
 * [contentFetch] — and then the model is asked [SkillDef.prompt] with that context under it, in a
 * session framed by [systemPrompt].
 *
 * A [SkillSource.FETCH] skill with no [contentFetch] (the web app, which cannot read another origin)
 * falls back to the section's own text rather than failing — the same fallback a section with no links
 * at all takes. So a skill always has *something* to run over, and the difference the host makes is how
 * much, not whether.
 *
 * The model is the browser's own and may need downloading on first use, which is why
 * [onDownloadProgress] hangs off the same call as in the chat. Cancelling the collection — the user
 * closed the window — stops the fetch or the generation and gives the session back.
 */
fun runSkill(
    ai: AiAssistant,
    def: SkillDef,
    cards: List<Card>,
    contentFetch: ContentFetch?,
    systemPrompt: String,
    onDownloadProgress: (Double) -> Unit = {},
): Flow<SkillStep> = flow {
    val context = when (def.source) {
        SkillSource.SECTION -> sectionContext(cards)
        SkillSource.FETCH -> {
            val urls = if (contentFetch == null) emptyList() else linksToFetch(cards)
            if (urls.isEmpty()) {
                // Nothing to fetch — no links, or nowhere to fetch from. The section's own text is
                // still a thing to run over, and a better answer than silence.
                sectionContext(cards)
            } else {
                val titles = cards.associate { it.url to it.title }
                val docs = mutableListOf<FetchedDoc>()
                urls.forEachIndexed { index, url ->
                    emit(SkillStep.Fetching(index, urls.size))
                    // One page that would not load is one page, not the run: it is named in the context
                    // (see [fetchedContext]) and the rest are still read.
                    val text = runCatching { contentFetch!!.fetch(url) }.getOrNull().orEmpty()
                    docs += FetchedDoc(titles[url] ?: url, url, text)
                }
                emit(SkillStep.Fetching(urls.size, urls.size))
                fetchedContext(docs)
            }
        }
    }

    val session = ai.start(systemPrompt, onDownloadProgress)
    try {
        val question = buildString {
            append(def.prompt.trim())
            if (context.isNotBlank()) append("\n\n").append(context)
        }
        session.ask(question).collect { text -> emit(SkillStep.Answer(text)) }
    } finally {
        session.close()
    }
}
