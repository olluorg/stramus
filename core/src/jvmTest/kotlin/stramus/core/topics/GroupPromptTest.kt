package stramus.core.topics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a model's grouping has to survive before it reaches the sidebar.
 *
 * The shape of the answer is held by the browser (see [groupingSchema]); the *content* is not, and every
 * case here is something a real answer did: a collection nobody offered, one collection in two groups, a
 * group of one, and — the one that matters — a bucket called "Разное" holding everything it could not
 * place, which is precisely what the prompt asks it not to do.
 */
class GroupPromptTest {

    private val offered = listOf("Kotlin Multiplatform", "System Design", "Ozon", "Вакансия Senior")

    private fun answer(vararg groups: Pair<String, List<String>>): String =
        groups.joinToString(",", prefix = """{"groups":[""", postfix = "]}") { (name, members) ->
            """{"name":"$name","collections":[${members.joinToString(",") { "\"$it\"" }}]}"""
        }

    @Test
    fun `a sound answer places every collection it names`() {
        val groups = groupsFromAnswer(
            answer(
                "Работа" to listOf("Kotlin Multiplatform", "System Design"),
                "Покупки" to listOf("Ozon", "Вакансия Senior"),
            ),
            offered,
        )
        assertEquals("Работа", groups["Kotlin Multiplatform"])
        assertEquals("Работа", groups["System Design"])
        assertEquals("Покупки", groups["Ozon"])
    }

    @Test
    fun `a heap called the rest of them is refused`() {
        val groups = groupsFromAnswer(
            answer(
                "Работа" to listOf("Kotlin Multiplatform", "System Design"),
                "Разное" to listOf("Ozon", "Вакансия Senior"),
            ),
            offered,
        )
        assertEquals(mapOf("Kotlin Multiplatform" to "Работа", "System Design" to "Работа"), groups)
        assertTrue("Вакансия Senior" !in groups, "what it could not place is left for the addresses to place")
    }

    @Test
    fun `a collection nobody offered is not invented into existence`() {
        val groups = groupsFromAnswer(
            answer("Работа" to listOf("Kotlin Multiplatform", "Мои документы", "System Design")),
            offered,
        )
        assertEquals(setOf("Kotlin Multiplatform", "System Design"), groups.keys)
    }

    @Test
    fun `one collection in two groups belongs to the first`() {
        val groups = groupsFromAnswer(
            answer(
                "Работа" to listOf("Kotlin Multiplatform", "System Design"),
                "Учёба" to listOf("System Design", "Ozon"),
            ),
            offered,
        )
        assertEquals("Работа", groups["System Design"])
        assertTrue("Ozon" !in groups, "what is left of that group is one collection, which is no group")
    }

    @Test
    fun `a group of one is not a group`() {
        assertTrue(groupsFromAnswer(answer("Работа" to listOf("Kotlin Multiplatform")), offered).isEmpty())
    }

    @Test
    fun `nonsense in place of an answer places nothing`() {
        assertTrue(groupsFromAnswer("не json", offered).isEmpty())
        assertTrue(groupsFromAnswer("""{"groups":"нет"}""", offered).isEmpty())
        assertTrue(groupsFromAnswer(answer("" to listOf("Ozon", "System Design")), offered).isEmpty())
    }

    @Test
    fun `the question names every collection and the language to answer in`() {
        val prompt = groupingPrompt(offered, language = "Russian")
        offered.forEach { assertTrue(it in prompt, "«$it» is not in the question") }
        // The app's language, not the folders': the answer is a heading in a sidebar where every other
        // heading follows what the user chose.
        assertTrue("in Russian" in prompt, prompt)
    }
}
