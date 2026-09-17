@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.topics

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import stramus.core.ai.TabGroup
import stramus.core.ai.TriageStep
import stramus.core.ai.TriageTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The plan the topics window reads, as a sequence of steps.
 *
 * What matters here is the *order* and what survives it: the whole plan first, so the window has something
 * to show at once; then the line that says a slower question is out; then its answer. The feature was built
 * the other way round to begin with — the model asked before anything was drawn — and several seconds of
 * empty screen was the entire complaint about it.
 */
class TopicPlanTest {

    private val tools = TitleTopic("Набор оправок", listOf(1, 2), listOf("оправок"), 4.0)
    private val videos = TitleTopic("Youtube", listOf(3), emptyList(), 0.0)

    private val asking = listOf(
        TabGroup("ozon.ru", listOf(TriageTab(1, "Набор оправок", "https://ozon.ru/1"), TriageTab(2, "Оправки", "https://ozon.ru/2"))),
        TabGroup("youtube.com", listOf(TriageTab(3, "Мультфильм", "https://youtube.com/3"))),
    )

    private suspend fun steps(
        topics: List<TitleTopic> = listOf(tools, videos),
        groups: Map<String, String> = mapOf("Youtube" to "Видео"),
        places: Map<String, ExistingPlace> = emptyMap(),
        model: suspend () -> Map<String, String> = { emptyMap() },
    ) = topicPlan(topics, groups, places, asking, askingNote = "спрашиваем…", model = model).toList()

    @Test
    fun `the plan comes first, whole, and the slow question after it`() = runTest {
        val steps = steps()
        assertEquals(3, steps.size)

        val placed = steps[0] as TriageStep.Placed
        assertEquals(3, placed.assignments.size, "every tab asked about is placed in the first step")
        assertEquals(placed.total, placed.done, "there is nothing left to wait for in the plan itself")

        assertEquals("спрашиваем…", (steps[1] as TriageStep.Asking).note)
        assertTrue((steps[2] as TriageStep.Regrouped).groups.isEmpty())
    }

    @Test
    fun `a new collection takes the shelf it was given, and names itself`() = runTest {
        val placed = steps()[0] as TriageStep.Placed
        val video = placed.assignments.single { it.tabId == 3 }
        assertEquals("Youtube", video.collectionTitle)
        assertEquals("Видео", video.groupTitle)
        assertNull(video.collectionId, "nothing of that name exists yet — applying the plan would make it")
    }

    @Test
    fun `a topic that joins something existing keeps that collection's name, id and divider`() = runTest {
        val collectionId = Uuid.random()
        val sectionId = Uuid.random()
        val placed = steps(
            places = mapOf(
                "Набор оправок" to ExistingPlace(
                    collectionId = collectionId.toString(),
                    collectionTitle = "Инструменты",
                    sectionId = sectionId.toString(),
                    sectionTitle = "Съёмники",
                ),
            ),
        )[0] as TriageStep.Placed

        val joined = placed.assignments.filter { it.tabId in listOf(1, 2) }
        assertEquals(2, joined.size)
        joined.forEach { assignment ->
            assertEquals("Инструменты", assignment.collectionTitle, "a plan does not rename what the user made")
            assertEquals(collectionId, assignment.collectionId)
            assertEquals("Съёмники", assignment.sectionTitle)
            assertEquals(sectionId, assignment.sectionId)
            assertNull(assignment.groupTitle, "a collection that exists is already somewhere")
        }
    }

    @Test
    fun `a tab nobody asked about is not in the plan`() = runTest {
        val topics = listOf(TitleTopic("Что-то ещё", listOf(1, 99), listOf("что"), 1.0))
        val placed = steps(topics = topics, groups = emptyMap())[0] as TriageStep.Placed
        assertEquals(listOf(1), placed.assignments.map { it.tabId })
    }

    @Test
    fun `the model's answer arrives as its own step`() = runTest {
        val answer = mapOf("Набор оправок" to "Ремонт", "Youtube" to "Развлечения")
        assertEquals(answer, (steps(model = { answer })[2] as TriageStep.Regrouped).groups)
    }

    @Test
    fun `a model that fails costs the plan nothing`() = runTest {
        val steps = steps(model = { error("the browser said no") })
        assertEquals(3, steps.size, "the run still ends properly")
        assertTrue((steps[2] as TriageStep.Regrouped).groups.isEmpty(), "and the shelves it had stand")
    }
}
