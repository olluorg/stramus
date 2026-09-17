@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.topics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import stramus.core.ai.TabGroup
import stramus.core.ai.TriageAssignment
import stramus.core.ai.TriageStep
import kotlin.js.console
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [topics] as the plan the triage window shows, for the tabs of [asking] — the ones it is actually asking
 * about, a page already saved having been left out of them.
 *
 * The plan itself arrives whole, in one step: it was decided without asking anybody anything. What follows
 * is the one slow part — [model], the browser's own, asked about the sidebar groups with the window
 * already up and [askingNote] on screen saying so. It used to be asked *before* any of this was shown, and
 * those seconds were spent looking at a window that had not opened yet.
 *
 * [groups] is the shelf each new collection would go on, and [places] is where a topic joins something the
 * user already has — see `matchExisting`. A topic with a place keeps that collection's own name, id and
 * divider: a plan neither renames nor moves what somebody made for themselves.
 *
 * It lives in `core` rather than beside the window it feeds because it is ordinary logic over ordinary
 * types — and because `ui-shared` has nowhere to put a test.
 */
fun topicPlan(
    topics: List<TitleTopic>,
    groups: Map<String, String>,
    places: Map<String, ExistingPlace>,
    asking: List<TabGroup>,
    askingNote: String,
    model: suspend () -> Map<String, String>,
): Flow<TriageStep> = flow {
    val asked = asking.flatMap { it.tabs }.map { it.id }.toSet()
    val assignments = topics.flatMap { topic ->
        val place = places[topic.title]
        topic.tabIds.filter { it in asked }.map { id ->
            TriageAssignment(
                tabId = id,
                collectionTitle = place?.collectionTitle ?: topic.title,
                collectionId = place?.collectionId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                sectionTitle = place?.sectionTitle,
                sectionId = place?.sectionId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                // A collection that exists is already somewhere; only a new one needs a shelf.
                groupTitle = if (place == null) groups[topic.title] else null,
            )
        }
    }
    emit(TriageStep.Placed(host = "topics", done = asked.size, total = asked.size, assignments = assignments))

    emit(TriageStep.Asking(askingNote))
    val byModel = runCatching { model() }.getOrDefault(emptyMap())
    console.log(
        if (byModel.isEmpty()) {
            "[topics] groups by model: none — the shelves from the addresses stand"
        } else {
            "[topics] groups by model: ${byModel.entries.joinToString { "${it.key} → ${it.value}" }}"
        },
    )
    emit(TriageStep.Regrouped(byModel))
}
