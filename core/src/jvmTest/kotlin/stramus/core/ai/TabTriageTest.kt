@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The triage's own reasoning, with no model and no browser under it.
 *
 * What is worth testing here is not that a good answer is accepted — it is that a bad one does no
 * harm. The model these run against in life is a small on-device one, and it will number a tab that
 * is not there, number one twice, write a sentence where a name was asked for, or answer with a
 * number. Each of those is a case below, and each has the same expected outcome: the tab is left
 * unassigned for the user to place, never placed somewhere invented.
 */
class TabTriageTest {

    private val workId = Uuid.random()
    private val vacanciesId = Uuid.random()
    private val funId = Uuid.random()
    private val collections = listOf(
        TriageCollection(workId, "Работа", "Дела", listOf(TriageSection(vacanciesId, "Вакансии"))),
        TriageCollection(funId, "Развлечение", "Личное"),
    )

    private fun tab(id: Int, url: String, title: String = "t$id") = TriageTab(id, title, url)

    private fun batch(vararg tabs: TriageTab) = TabBatch("hh.ru", tabs.toList())

    /** The sidebar sections a new collection may be created in. */
    private val groups = listOf("Дела", "Личное")

    /** `planForBatch` against the fixtures above — the arguments the run would pass it. */
    private fun plan(answer: String, batch: TabBatch, into: List<TriageCollection> = collections) =
        planForBatch(answer, batch, into, groups)

    private fun answer(vararg items: String) = """{"tabs":[${items.joinToString(",")}]}"""

    private fun item(tab: Int, collection: String, section: String? = null) =
        if (section == null) """{"tab":$tab,"collection":"$collection"}"""
        else """{"tab":$tab,"collection":"$collection","section":"$section"}"""

    @Test
    fun `groups tabs by site, in the order the window holds them`() {
        val groups = preGroup(
            listOf(
                tab(1, "https://kotlinlang.org/docs"),
                tab(2, "https://news.ycombinator.com/"),
                tab(3, "https://www.kotlinlang.org/api"),
            ),
        )
        assertEquals(listOf("kotlinlang.org", "news.ycombinator.com"), groups.map { it.host })
        // `www.` is not a different site: hostOf strips it, so both Kotlin tabs are one group.
        assertEquals(listOf(1, 3), groups[0].tabs.map { it.id })
    }

    @Test
    fun `the same page in two tabs is one entry, and the first tab is the one kept`() {
        val groups = preGroup(
            listOf(
                tab(1, "https://kotlinlang.org/docs"),
                tab(2, "https://kotlinlang.org/docs"),
                tab(3, "https://kotlinlang.org/api"),
            ),
        )
        // Tab 2 would have been a second identical card, and a second identical row to read.
        assertEquals(listOf(1, 3), groups.single().tabs.map { it.id })
    }

    @Test
    fun `a batch never spans two sites, and a big site is simply several batches`() {
        val groups = listOf(
            TabGroup("hh.ru", (1..25).map { tab(it, "https://hh.ru/$it") }),
            TabGroup("youtube.com", listOf(tab(99, "https://youtube.com/1"))),
        )
        val batches = batches(groups)
        assertEquals(listOf("hh.ru", "hh.ru", "hh.ru", "youtube.com"), batches.map { it.host })
        assertEquals(listOf(10, 10, 5, 1), batches.map { it.tabs.size })
    }

    @Test
    fun `one site's tabs may go to different collections — the point of asking per tab`() {
        val plan = planForBatch(
            answer(item(1, "Работа"), item(2, "Развлечение")),
            batch(tab(1, "https://hh.ru/vacancy"), tab(2, "https://hh.ru/blog")),
            collections,
            groups,
        )
        assertEquals(listOf("Работа", "Развлечение"), plan.map { it.collectionTitle })
        assertEquals(listOf(workId, funId), plan.map { it.collectionId })
    }

    @Test
    fun `an existing collection and section are matched however the model spelled them`() {
        val plan = planForBatch(
            answer(item(1, "работа", "вакансии")),
            batch(tab(1, "https://hh.ru/1")),
            collections,
            groups,
        )
        // Both keep their own spelling, not the model's.
        assertEquals(TriageAssignment(1, "Работа", workId, "Вакансии", vacanciesId, "Дела"), plan.single())
    }

    @Test
    fun `a section that does not exist yet is one to create, under its own collection`() {
        val plan = plan(answer(item(1, "Работа", "Резюме")), batch(tab(1, "https://hh.ru/1")), collections)
        assertEquals(TriageAssignment(1, "Работа", workId, "Резюме", null, "Дела"), plan.single())
    }

    @Test
    fun `a section is matched only among its own collection's sections`() {
        // "Вакансии" exists, but in "Работа" — naming it under another collection is a new section
        // there, not a move of that one.
        val plan = plan(answer(item(1, "Развлечение", "Вакансии")), batch(tab(1, "https://hh.ru/1")), collections)
        assertEquals(TriageAssignment(1, "Развлечение", funId, "Вакансии", null, "Личное"), plan.single())
    }

    @Test
    fun `no section is a real answer, and the common one`() {
        val plan = plan(answer(item(1, "Развлечение")), batch(tab(1, "https://hh.ru/1")), collections)
        assertEquals(TriageAssignment(1, "Развлечение", funId, null, null, "Личное"), plan.single())
    }

    @Test
    fun `a name that matches nothing is a collection to create`() {
        val plan = plan(answer(item(1, "Papers")), batch(tab(1, "https://hh.ru/1")), collections)
        assertEquals(TriageAssignment(1, "Papers", null, null, null, null), plan.single())
    }

    @Test
    fun `a tab that is not in the batch is dropped rather than guessed at`() {
        val plan = planForBatch(
            answer(item(9, "Работа"), item(0, "Работа"), item(1, "Работа")),
            batch(tab(1, "https://hh.ru/1")),
            collections,
            groups,
        )
        assertEquals(listOf(1), plan.map { it.tabId })
    }

    @Test
    fun `the same tab twice is the model repeating itself, and the first answer stands`() {
        val plan = planForBatch(
            answer(item(1, "Работа"), item(1, "Развлечение")),
            batch(tab(1, "https://hh.ru/1")),
            collections,
            groups,
        )
        assertEquals(workId, plan.single().collectionId)
    }

    @Test
    fun `a tab the answer never mentions is left for the user`() {
        val plan = plan(answer(item(1, "Работа")), batch(tab(1, "https://hh.ru/1"), tab(2, "https://hh.ru/2")))
        assertEquals(listOf(1), plan.map { it.tabId })
    }

    @Test
    fun `an answer that is not json at all is no plan, not a crash`() {
        val one = batch(tab(1, "https://hh.ru/1"))
        assertTrue(plan("Sure! Here is the plan:", one, collections).isEmpty())
        assertTrue(plan("", one, collections).isEmpty())
        assertTrue(plan("""{"tabs":"nope"}""", one, collections).isEmpty())
    }

    @Test
    fun `values of the wrong type are not values`() {
        val one = batch(tab(1, "https://hh.ru/1"))
        // Without the isString check this would arrive as a collection named "3".
        assertTrue(plan("""{"tabs":[{"tab":1,"collection":3}]}""", one, collections).isEmpty())
        // ...and without the number check, a tab numbered "1" as a string would sneak past.
        assertTrue(plan("""{"tabs":[{"tab":"1","collection":"Работа"}]}""", one, collections).isEmpty())
    }

    @Test
    fun `a new collection is created in the group the model named, if that is a group at all`() {
        val answered = """{"tabs":[{"tab":1,"collection":"Электроника","group":"Личное"}]}"""
        assertEquals("Личное", plan(answered, batch(tab(1, "https://avito.ru/1"))).single().groupTitle)

        // A group that is not one is no group: the caller falls back rather than creating a section.
        val invented = """{"tabs":[{"tab":1,"collection":"Электроника","group":"Придумал"}]}"""
        assertNull(plan(invented, batch(tab(1, "https://avito.ru/1"))).single().groupTitle)
    }

    @Test
    fun `an existing collection keeps the group it is already in, whatever the model says`() {
        val answered = """{"tabs":[{"tab":1,"collection":"Работа","group":"Личное"}]}"""
        // "Работа" lives in "Дела" and the model does not get to move it.
        assertEquals("Дела", plan(answered, batch(tab(1, "https://hh.ru/1"))).single().groupTitle)
    }

    @Test
    fun `the model is told it may decline a tab rather than guess at it`() {
        val prompt = batchPrompt(batch(tab(1, "https://avito.ru/1")), collections, groups)
        assertTrue("leave that tab out of your answer" in prompt)
        assertTrue("Do not guess" in prompt)
        // ...and the groups it may create a collection in are named.
        assertTrue("one of: Дела, Личное" in prompt)
    }

    // --- What survives being asked twice. See `agreed`: this is where "the model must not file a tab
    // it cannot place" stops being a request in the prompt and becomes a rule.

    private fun placed(tabId: Int, collection: String, section: String? = null) =
        TriageAssignment(tabId, collection, null, section, null, null)

    @Test
    fun `a tab both answers place the same way is placed`() {
        val plan = agreed(listOf(placed(1, "Работа")), listOf(placed(1, "Работа")))
        assertEquals(listOf(placed(1, "Работа")), plan)
    }

    @Test
    fun `a tab the two answers disagree about is placed nowhere`() {
        // The graphics card listing: "Развлечение" one run, "Электроника" the next. Neither is an
        // answer — the model was guessing, and this is how that becomes visible without asking it.
        assertTrue(agreed(listOf(placed(1, "Развлечение")), listOf(placed(1, "Электроника"))).isEmpty())
    }

    @Test
    fun `a tab only one answer mentions is placed nowhere`() {
        assertTrue(agreed(listOf(placed(1, "Работа")), listOf(placed(2, "Работа"))).isEmpty())
        assertTrue(agreed(listOf(placed(1, "Работа")), emptyList()).isEmpty())
    }

    @Test
    fun `agreement is about the name, not its spelling`() {
        assertEquals(listOf(placed(1, "Работа")), agreed(listOf(placed(1, "Работа")), listOf(placed(1, "работа"))))
    }

    @Test
    fun `agreeing on the collection but not the divider keeps the collection and drops the divider`() {
        // The agreed half of an answer is still an answer, and ungrouped is a normal place for a card.
        val plan = agreed(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме")))
        assertEquals(listOf(placed(1, "Работа", null)), plan)
        // ...including where only one of them named a divider at all.
        assertEquals(
            listOf(placed(1, "Работа", null)),
            agreed(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа"))),
        )
    }

    @Test
    fun `a divider both answers name survives with its collection`() {
        val plan = agreed(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "вакансии")))
        assertEquals(listOf(placed(1, "Работа", "Вакансии")), plan)
    }

    @Test
    fun `the ids the first answer resolved are the ones kept`() {
        // `agreed` returns the first answer's own rows, so the collection and section ids that
        // `planForBatch` matched are carried through rather than rebuilt from a title.
        val one = TriageAssignment(1, "Работа", workId, "Вакансии", vacanciesId, "Дела")
        assertEquals(listOf(one), agreed(listOf(one), listOf(placed(1, "Работа", "Вакансии"))))
    }

    @Test
    fun `a name is a name`() {
        assertEquals("Kotlin", cleanName("Kotlin"))
        assertEquals("Kotlin", cleanName("  \"Kotlin\"  "))
        assertEquals("Kotlin", cleanName("`Kotlin`"))
        // The model answered with a sentence about the name rather than the name.
        assertNull(cleanName("These tabs all appear to be about the Kotlin programming language, so I would"))
        assertNull(cleanName(""))
        assertNull(cleanName(null))
    }

    @Test
    fun `a collection carries its sidebar group as an attribute, never as a heading over it`() {
        // Two bugs, one line. Without the group at all, the model read "Поиск" as searching in
        // general and put a job ad in "Повышение квалификации". With the group as a *heading* over
        // indented collections, it read the levels off by one and answered with the collection
        // "Работа" and the section "Поиск" — inventing both. Flat, every line is a collection.
        val sidebar = listOf(
            TriageCollection(Uuid.random(), "Повышение квалификации", "Работа", listOf(TriageSection(null, "Курсы"))),
            TriageCollection(Uuid.random(), "Поиск", "Работа"),
            TriageCollection(Uuid.random(), "Развлечение", "Личное"),
        )
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), sidebar, groups)
        assertTrue(""""Поиск" (group: Работа)""" in prompt)
        assertTrue(""""Повышение квалификации" (group: Работа; sections: Курсы)""" in prompt)
        assertTrue(""""Развлечение" (group: Личное)""" in prompt)
        // No group ever appears as a line of its own — that is what taught the model to answer with one.
        assertTrue("Работа:" !in prompt && "Личное:" !in prompt)
        // ...and the model is told what the group is for, and what it is not for.
        assertTrue("never a group name" in prompt)
    }

    @Test
    fun `a collection is described by what is in it, not by its name alone`() {
        // The bug this fixes: "Поиск" in the group "Работа" is a job search, but shown only the name
        // the model filed a graphics card someone was shopping for under it — buying is a kind of
        // searching too. Its contents leave nothing to misread.
        val withCards = listOf(
            TriageCollection(
                Uuid.random(), "Поиск", "Работа",
                examples = listOf("Вакансия Kotlin developer — hh.ru", "Отклик на вакансию", "Третья"),
            ),
        )
        val prompt = batchPrompt(batch(tab(1, "https://avito.ru/1", "Rtx 5060 TI купить")), withCards, groups)
        assertTrue("""already here: "Вакансия Kotlin developer — hh.ru", "Отклик на вакансию")""" in prompt)
        // Only a couple are quoted — the line is context, not an inventory.
        assertTrue("Третья" !in prompt)
        // ...and the model is told which of the two to trust.
        assertTrue("Judge a collection by what is in it, not by its name" in prompt)
    }

    @Test
    fun `a collection with nothing in it yet is described without an empty contents list`() {
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), listOf(TriageCollection(Uuid.random(), "Поиск", "Работа")), groups)
        assertTrue(""""Поиск" (group: Работа)""" in prompt)
        assertTrue("already here" !in prompt)
    }

    @Test
    fun `the collection list is described within a budget, however many the user has`() {
        val many = (1..200).map {
            TriageCollection(Uuid.random(), "Collection number $it", "Group $it", examples = listOf("Card $it"))
        }
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), many, groups)
        assertTrue(prompt.length < 2200)
        // Whatever is cut, the tabs and the ask itself always survive.
        assertTrue("1. t1" in prompt)
        assertTrue("For every tab" in prompt)
    }

    @Test
    fun `the summary names where a site's tabs ended up, and stops at its budget`() {
        val groups = (1..200).map { TabGroup("site$it.com", listOf(tab(it, "https://site$it.com/", "Title $it"))) }
        val prompt = summaryPrompt(groups, mapOf("site1.com" to setOf("Работа", "Развлечение")))
        assertTrue("site1.com [Работа, Развлечение]" in prompt)
        assertTrue(prompt.length < 2000)
        assertTrue("what was this session about" in prompt)
    }
}
