package stramus.core.topics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a topic goes for somebody who has been using stramus for a while.
 *
 * The cases that matter are the refusals. Joining the wrong collection scatters a person's tabs into a
 * place they did not pick, and they have to find and undo it; refusing to join costs one extra collection
 * beside the right one, which is a drag away from being fixed.
 */
class ExistingMatchTest {

    private val kalina = KnownCollection(
        id = "c1",
        title = "Лада Калина",
        cardTitles = listOf(
            "Ремонт двигателя Лады Калины",
            "Фары для Лада Калина БИ ЛЕД линзы",
            "Сальник распредвала калина 8кл",
        ),
        sections = listOf(
            KnownSection("s1", "Электрика", listOf("Фары БИ ЛЕД линзы", "Проводка приборной панели")),
            KnownSection("s2", "Двигатель", listOf("Прокладка клапанной крышки", "Сальник распредвала")),
        ),
    )

    private val kotlin = KnownCollection(
        id = "c2",
        title = "Kotlin",
        cardTitles = listOf("Kotlin Multiplatform ORM", "Kotlin coroutines guide"),
    )

    private val known = listOf(kalina, kotlin)

    @Test
    fun `a topic joins the collection it reads like`() {
        val place = matchExisting(
            topicTitle = "Калина замена топливной трубки",
            tabTitles = listOf("Диагностика ошибки P0171 на Калине", "Ремонт двигателя Лады Калины"),
            known = known,
        )
        assertEquals("c1", place?.collectionId)
        assertEquals("Лада Калина", place?.collectionTitle)
    }

    @Test
    fun `and lands under the divider the user made for it`() {
        val place = matchExisting(
            topicTitle = "Прокладка клапанной крышки течет лада",
            tabTitles = listOf("Течь прокладки клапанной крышки", "Сальник распредвала калина"),
            known = known,
        )
        assertEquals("c1", place?.collectionId)
        assertEquals("Двигатель", place?.sectionTitle)
        assertEquals("s2", place?.sectionId)
    }

    @Test
    fun `a topic with nothing in common stays a new collection`() {
        assertNull(
            matchExisting(
                topicTitle = "Секатор электрический",
                tabTitles = listOf("Секатор аккумуляторный для сада", "Секатор электрический 45 мм"),
                known = known,
            ),
        )
    }

    @Test
    fun `one word in common is not enough`() {
        // "Калина" the berry against "Калина" the car: one shared word, and nothing else agrees.
        assertNull(
            matchExisting(
                topicTitle = "Калина протертая с сахаром",
                tabTitles = listOf("Рецепт морса", "Ягоды на зиму"),
                known = known,
            ),
        )
    }

    @Test
    fun `a collection with no dividers takes the topic ungrouped`() {
        val place = matchExisting(
            topicTitle = "Kotlin Multiplatform",
            tabTitles = listOf("kormium: Kotlin Multiplatform ORM", "Kotlin coroutines guide"),
            known = known,
        )
        assertEquals("c2", place?.collectionId)
        assertNull(place?.sectionTitle)
    }

    @Test
    fun `nothing to match against, nothing to match`() {
        assertNull(matchExisting("Что угодно", listOf("Хоть что"), emptyList()))
        assertNull(matchExisting("", emptyList(), known))
    }
}
