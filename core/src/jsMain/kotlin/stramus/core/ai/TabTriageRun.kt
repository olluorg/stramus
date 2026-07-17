package stramus.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiSession

/**
 * The plan as it is written: a batch of tabs at a time, in the order the window holds them.
 *
 * A window of forty tabs is several questions to a model that answers in a second or two, so a plan
 * gathered up and handed over at the end is a minute of a spinner. These come out as they are decided
 * instead, and the user reads — and corrects — the top of the plan while the bottom is still being
 * written.
 */
sealed interface TriageStep {

    /**
     * One batch, decided. [assignments] holds only the tabs the model placed acceptably — the rest of
     * the batch stays unassigned for the user, which is not an error and does not stop the run.
     *
     * [done] of [total] is the progress to show, counted in batches.
     *
     * Which of these collections and sections are *new* is not said here: the UI has the catalog of
     * what exists and can see that for itself, and a fact derived where it is used cannot fall out of
     * step with the thing it is derived from.
     */
    data class Placed(
        val host: String,
        val done: Int,
        val total: Int,
        val assignments: List<TriageAssignment>,
    ) : TriageStep

    /** What the session was about, once every batch has been placed. Last, and only if the model wrote one. */
    data class Summarised(val text: String) : TriageStep
}

/**
 * Sort the tabs of [groups] into [known] and whatever the model has to invent — a batch at a time.
 *
 * The shape of this is what leaves it without ceilings. One session is opened, framed with
 * [systemPrompt] — that is the one that may have to download the model, which is why
 * [onDownloadProgress] hangs off it — and every batch is then asked in a *clone* of it: same framing,
 * none of the history. So the context each question sees is ten tabs and the collection list, no
 * matter how many batches came before, and a window of two hundred tabs is not a bigger question than
 * a window of six. It is only a longer wait, which is what the [Flow] is for.
 *
 * Each batch costs two questions, not one: it is asked twice and only the agreeing answers are kept
 * (see [agreed]). That is the whole run's cost doubled, and it is what a plan the user can trust is
 * made of — an answer that does not come back the same was a guess.
 *
 * The clones are sequential, and not merely because the model is one: each batch is asked against the
 * collections as they stand *including the ones invented a moment ago*, so the second jobs board joins
 * the "Работа" the first one caused instead of founding "Вакансии" beside it. Asked in parallel they
 * could not, and the plan would arrive full of near-duplicate collections — which is exactly the
 * tidying up the user wanted done for them.
 *
 * [sidebarGroups] are the sidebar groups a new collection may be put in — the model picks one, and anything
 * that is not on this list is not a group. [newCollectionsIn] is where one goes when it did not pick:
 * a fallback, not the rule it used to be, back when every invented collection landed in whichever
 * section the user happened to have open.
 *
 * Either way the invented collection is offered back to the next batch *under its group* — the group
 * is what a collection's name means (see [TriageCollection.inSection]), and an invented one shown
 * groupless would be the one collection in the list the model cannot read properly.
 *
 * [summarySystemPrompt] frames the summary, and it is a session of its own rather than another clone
 * of the base. It has to be: [systemPrompt] tells the model to answer with nothing but JSON — which is
 * what makes the batches parse — and a clone inherits that framing. Asked for prose under it, the
 * model duly answered with a JSON document *and then* the prose, and the summary arrived with a code
 * block on top of it. The two questions want opposite things from the model, so they do not share a
 * session.
 *
 * Cancelling the collection — the user closed the window — stops the run and gives back every session.
 */
fun triage(
    ai: AiAssistant,
    systemPrompt: String,
    groups: List<TabGroup>,
    known: List<TriageCollection>,
    sidebarGroups: List<String>,
    newCollectionsIn: String?,
    summarySystemPrompt: String,
    onDownloadProgress: (Double) -> Unit = {},
): Flow<TriageStep> = flow {
    val batches = batches(groups)
    val base = ai.start(systemPrompt, onDownloadProgress)
    try {
        // Grows as the run goes: what the next batch is offered to reuse, collections and the sections
        // inside them alike. See the note above — this list is the whole reason batches are in order.
        val offered = known.toMutableList()
        val placed = mutableMapOf<String, MutableSet<String>>()

        batches.forEachIndexed { index, batch ->
            // Asked twice, and only what both answers agree on is kept — see [agreed]. This is what
            // stops a tab the model cannot place from being placed anyway: an answer it is sure of
            // comes back the same, an answer it invented does not, and it is never asked to judge
            // which of those it just did.
            //
            // One batch the model choked on is one batch, not the run: a question that throws leaves
            // its tabs unassigned and the next batch is still asked. The user gets a plan with a hole
            // in it rather than an error where a plan was. And a first answer with nothing in it has
            // nothing to confirm, so the second question is not worth asking.
            val first = runCatching { ask(base, batch, offered, sidebarGroups) }.getOrNull().orEmpty()
            val second = if (first.isEmpty()) {
                emptyList()
            } else {
                runCatching { ask(base, batch, offered, sidebarGroups) }.getOrNull().orEmpty()
            }
            val assignments = agreed(first, second)

            for (assignment in assignments) {
                val existing = offered.indexOfFirst { it.title.equals(assignment.collectionTitle, ignoreCase = true) }
                if (existing < 0) {
                    // A collection the model made up. It goes into the list the next batch sees with
                    // no id — [TriageAssignment] carries that null all the way to `applyTriage`, which
                    // is what finally makes it.
                    offered += TriageCollection(
                        id = null,
                        title = assignment.collectionTitle,
                        inSection = assignment.groupTitle ?: newCollectionsIn,
                        sections = listOfNotNull(assignment.sectionTitle?.let { TriageSection(null, it) }),
                    )
                } else {
                    // The collection is known; the section under it may still be new. Offering it back
                    // is what stops the next batch spelling the same divider a second way.
                    val collection = offered[existing]
                    val section = assignment.sectionTitle
                    if (section != null && collection.sections.none { it.title.equals(section, ignoreCase = true) }) {
                        offered[existing] = collection.copy(sections = collection.sections + TriageSection(null, section))
                    }
                }
                placed.getOrPut(batch.host) { mutableSetOf() } += assignment.collectionTitle
            }
            emit(TriageStep.Placed(batch.host, index + 1, batches.size, assignments))
        }

        // A session of its own, framed for prose — see the note above. It also carries its own account
        // of the window ([summaryPrompt]), there being no session left that has seen one.
        val summary = runCatching {
            ai.start(summarySystemPrompt).use { session -> session.ask(summaryPrompt(groups, placed)).lastOrNull() }
        }.getOrNull()
        if (!summary.isNullOrBlank()) emit(TriageStep.Summarised(summary))
    } finally {
        base.close()
    }
}

/** One batch's question, in a session of its own that is given back the moment it has answered. */
private suspend fun ask(
    base: AiSession,
    batch: TabBatch,
    offered: List<TriageCollection>,
    groups: List<String>,
): List<TriageAssignment> = base.clone().use { session ->
    planForBatch(session.askJson(batchPrompt(batch, offered, groups), batchSchema()), batch, offered, groups)
}

/**
 * Run [block] with this session and give it back afterwards, whatever happens — including the user
 * closing the window mid-question, which cancels the coroutine rather than returning through it.
 * `AiSession` is not `AutoCloseable` (its `close` is not the JVM's), so this is the same idea spelled
 * out for it.
 */
private inline fun <T> AiSession.use(block: (AiSession) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
