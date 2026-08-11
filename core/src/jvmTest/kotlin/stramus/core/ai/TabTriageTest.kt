@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        planForBatch(answer, batch.tabs, into, groups)

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
    fun `selectByBudget takes as many tabs as fit, in the order given`() {
        val tabs = (1..50).map { tab(it, "https://site$it.com/", title = "Tab number $it") }
        // Each line is roughly 25-27 characters; a budget of 300 comfortably fits some but not all 50.
        val selected = selectByBudget(tabs, budget = 300)
        assertTrue(selected.size in 1..49)
        assertEquals(tabs.take(selected.size), selected)
    }

    @Test
    fun `selectByBudget always takes at least one tab, even over budget — a run must make progress`() {
        val tabs = listOf(tab(1, "https://example.com/", title = "A title far longer than any reasonable budget allows for"))
        val selected = selectByBudget(tabs, budget = 1)
        assertEquals(tabs, selected)
    }

    @Test
    fun `selectByBudget returns everything when it all fits`() {
        val tabs = (1..5).map { tab(it, "https://site$it.com/") }
        assertEquals(tabs, selectByBudget(tabs, budget = 10_000))
    }

    @Test
    fun `trustedBatchPromptFitting spends the budget on the catalog first, tabs on whatever is left`() {
        val tabs = (1..50).map { tab(it, "https://site$it.com/", title = "Tab number $it") }
        // A budget too small for the catalog and every tab both — some tabs must be left for a follow-up.
        val (prompt, considered) = trustedBatchPromptFitting(tabs, collections, groups, totalCharBudget = 800, collectionsBudget = 400)
        assertTrue(considered.isNotEmpty())
        assertTrue(considered.size < tabs.size)
        // Every considered tab's own line actually made it into the prompt sent.
        considered.forEach { assertTrue(it.title in prompt) }
        // What was left out did not — this is the whole point: it did not merely go unanswered, it was
        // never asked about in this call at all.
        tabs.drop(considered.size).forEach { assertTrue(it.title !in prompt) }
    }

    @Test
    fun `trustedBatchPromptFitting considers every tab when the budget is generous`() {
        val tabs = (1..5).map { tab(it, "https://site$it.com/") }
        val (_, considered) = trustedBatchPromptFitting(tabs, collections, groups, totalCharBudget = 20_000, collectionsBudget = 10_000)
        assertEquals(tabs, considered)
    }

    @Test
    fun `the trusted prompt names each tab's own site, having no one host to say once for all of them`() {
        val prompt = trustedBatchPrompt(
            TabBatch("", listOf(tab(1, "https://hh.ru/1"), tab(2, "https://youtube.com/1"))),
            collections,
            groups,
        )
        assertTrue("1. [hh.ru] t1" in prompt)
        assertTrue("2. [youtube.com] t2" in prompt)
        // No shared "these are all on X" header — there is no X.
        assertTrue("These tabs are open:" in prompt)
    }

    @Test
    fun `one site's tabs may go to different collections — the point of asking per tab`() {
        val plan = planForBatch(
            answer(item(1, "Работа"), item(2, "Развлечение")),
            batch(tab(1, "https://hh.ru/vacancy"), tab(2, "https://hh.ru/blog")).tabs,
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
            batch(tab(1, "https://hh.ru/1")).tabs,
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
            batch(tab(1, "https://hh.ru/1")).tabs,
            collections,
            groups,
        )
        assertEquals(listOf(1), plan.map { it.tabId })
    }

    @Test
    fun `the same tab twice is the model repeating itself, and the first answer stands`() {
        val plan = planForBatch(
            answer(item(1, "Работа"), item(1, "Развлечение")),
            batch(tab(1, "https://hh.ru/1")).tabs,
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

    // --- Sections both answers named, but spelled differently — settled by a question of their own
    // rather than by `agreed`'s string equality. See `sectionDisagreements`, `resolveSections`.

    @Test
    fun `two answers naming a section differently is a disagreement, not a match or a drop`() {
        val disagreements = sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме")))
        val disagreement = disagreements.single()
        assertEquals(1, disagreement.one.tabId)
        assertEquals("Вакансии", disagreement.one.sectionTitle)
        assertEquals("Резюме", disagreement.otherSectionTitle)
    }

    @Test
    fun `no disagreement where the spelling matches, or only one side named a section`() {
        assertTrue(sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "вакансии"))).isEmpty())
        assertTrue(sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа"))).isEmpty())
        assertTrue(sectionDisagreements(listOf(placed(1, "Работа")), listOf(placed(1, "Работа"))).isEmpty())
        // Disagreeing on the collection itself is `agreed`'s business, not a section disagreement.
        assertTrue(sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Развлечение", "Резюме"))).isEmpty())
    }

    @Test
    fun `the adjudication prompt names the collection and both spellings`() {
        val disagreement = sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме"))).single()
        val prompt = sectionAdjudicationPrompt(listOf(disagreement))
        assertTrue("""in "Работа": "Вакансии" vs "Резюме"""" in prompt)
    }

    @Test
    fun `adjudicatedSame keeps only the tabs the model called the same place`() {
        val disagreements = sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме")))
        val answer = """{"pairs":[{"tab":1,"same":true}]}"""
        assertEquals(setOf(1), adjudicatedSame(answer, disagreements))
        assertTrue(adjudicatedSame("""{"pairs":[{"tab":1,"same":false}]}""", disagreements).isEmpty())
    }

    @Test
    fun `adjudicatedSame ignores a tab it was not asked about, and a bad answer`() {
        val disagreements = sectionDisagreements(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме")))
        // A tab number not among the disagreements — nothing to trust it about.
        assertTrue(adjudicatedSame("""{"pairs":[{"tab":9,"same":true}]}""", disagreements).isEmpty())
        // "same" as a string rather than a boolean — the same wrong-type guard as everywhere else here.
        assertTrue(adjudicatedSame("""{"pairs":[{"tab":1,"same":"true"}]}""", disagreements).isEmpty())
        assertTrue(adjudicatedSame("not json", disagreements).isEmpty())
    }

    @Test
    fun `resolveSections restores an adjudicated section, preferring whichever spelling was already existing`() {
        val existingId = Uuid.random()
        val one = TriageAssignment(1, "Работа", workId, "CV", null, "Дела") // invented spelling, matches nothing yet
        val other = TriageAssignment(1, "Работа", workId, "Резюме", existingId, "Дела") // matched an existing section
        val disagreement = SectionDisagreement(one, "Резюме", existingId)
        val agreedResult = agreed(listOf(one), listOf(other))
        // Before resolution, the disagreement dropped the section entirely.
        assertNull(agreedResult.single().sectionTitle)

        val resolved = resolveSections(agreedResult, listOf(disagreement), setOf(1))
        // The id-bearing spelling (the one that already matched a real section) wins over the invented one.
        assertEquals("Резюме", resolved.single().sectionTitle)
        assertEquals(existingId, resolved.single().sectionId)
    }

    @Test
    fun `resolveSections leaves untouched what nothing was adjudicated for`() {
        val agreedResult = agreed(listOf(placed(1, "Работа", "Вакансии")), listOf(placed(1, "Работа", "Резюме")))
        assertEquals(agreedResult, resolveSections(agreedResult, emptyList(), emptySet()))
    }

    @Test
    fun `the batch prompt asks for a section only where it genuinely fits, never merely the closest one`() {
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), collections, groups)
        assertTrue("A section must match what the tab is actually about, not merely be the closest one on offer" in prompt)
        assertTrue("leave the section out — that is the right answer far more often than forcing a weak one" in prompt)
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
    fun `a summary is a phrase, quoted or fenced or not, but not a paragraph`() {
        assertEquals("bedtime videos and podcasts", cleanSummary("bedtime videos and podcasts"))
        assertEquals("bedtime videos and podcasts", cleanSummary("\"bedtime videos and podcasts\""))
        assertNull(cleanSummary("x".repeat(200)))
        assertNull(cleanSummary(""))
        assertNull(cleanSummary(null))
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
        assertTrue("Judge a collection or a section by what is in it, not by its name" in prompt)
    }

    @Test
    fun `a collection with nothing in it yet is described without an empty contents list`() {
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), listOf(TriageCollection(Uuid.random(), "Поиск", "Работа")), groups)
        assertTrue(""""Поиск" (group: Работа)""" in prompt)
        assertTrue("already here" !in prompt)
    }

    @Test
    fun `a section is described by what is in it too, not left as a bare name`() {
        // The bug this fixes: "Для сна" said nothing about itself, and a model shown only the four
        // words of the name matched it to whatever was vaguely entertainment-shaped.
        val withSection = listOf(
            TriageCollection(
                Uuid.random(), "Развлечение", "Личное",
                sections = listOf(TriageSection(Uuid.random(), "Для сна", examples = listOf("ASMR дождь", "Подкаст на ночь"))),
            ),
        )
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), withSection, groups)
        assertTrue("Для сна (ASMR дождь; Подкаст на ночь)" in prompt)
    }

    @Test
    fun `a bare section with nothing saved in it yet is still just its name`() {
        val bare = listOf(TriageCollection(Uuid.random(), "Развлечение", "Личное", sections = listOf(TriageSection(Uuid.random(), "Для сна"))))
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), bare, groups)
        assertTrue("sections: Для сна)" in prompt)
    }

    @Test
    fun `a summary stands in for the raw titles, for a collection and for a section alike`() {
        val summarised = listOf(
            TriageCollection(
                Uuid.random(), "Развлечение", "Личное",
                examples = listOf("Кино", "Игры"),
                summary = "фильмы и видеоигры",
                sections = listOf(
                    TriageSection(Uuid.random(), "Для сна", examples = listOf("ASMR дождь"), summary = "видео и подкасты для засыпания"),
                ),
            ),
        )
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), summarised, groups)
        assertTrue("already here: фильмы и видеоигры" in prompt)
        assertTrue("Для сна (видео и подкасты для засыпания)" in prompt)
        // The summary replaces the titles — they are not also quoted alongside it.
        assertTrue("Кино" !in prompt)
        assertTrue("ASMR дождь" !in prompt)
    }

    @Test
    fun `the collection list is described within a budget, however many the user has`() {
        val many = (1..200).map {
            TriageCollection(Uuid.random(), "Collection number $it", "Group $it", examples = listOf("Card $it"))
        }
        val prompt = batchPrompt(batch(tab(1, "https://hh.ru/1")), many, groups)
        assertTrue(prompt.length < 2500)
        // Whatever is cut, the tabs and the ask itself always survive.
        assertTrue("1. t1" in prompt)
        assertTrue("For every tab" in prompt)
    }

    @Test
    fun `a trusted batch's budget is roomy enough that the same 200 collections are not all cut`() {
        // The local model's own budget test above cuts this list down to a handful — [batchPrompt]'s
        // 1500 characters was sized for a small on-device context. A cloud model's context is nothing
        // like that small, and a collection [trustedBatchPrompt] never shows it is one it cannot place a
        // tab in regardless of how well that tab actually fits — the failure a real run had before this
        // budget was split in two.
        val many = (1..200).map {
            TriageCollection(Uuid.random(), "Collection number $it", "Group $it", examples = listOf("Card $it"))
        }
        val prompt = trustedBatchPrompt(batch(tab(1, "https://hh.ru/1")), many, groups)
        assertTrue("Collection number 1\"" in prompt)
        assertTrue("Collection number 200\"" in prompt)
    }

    @Test
    fun `a collection's sections do not eat the whole catalog budget`() {
        // Sections are described one after another inside their collection, so whatever one costs is
        // multiplied by however many there are. Uncapped, four sections of quoted examples cost some
        // 3 000 characters a collection and the trusted budget held sixteen of two hundred — the very
        // starvation that budget was raised to prevent. The exact number here is not sacred; that it
        // stays in the same order of magnitude as the section-less case is.
        fun collection(i: Int, sections: Int) = TriageCollection(
            id = Uuid.random(),
            title = "Коллекция номер $i",
            inSection = "Хобби",
            sections = (1..sections).map { s ->
                TriageSection(Uuid.random(), "Секция $s", examples = (1..20).map { "Заголовок карточки номер $it, довольно длинный" })
            },
            examples = (1..20).map { "Заголовок карточки номер $it, довольно длинный" },
        )

        val many = (1..200).map { collection(it, sections = 4) }
        val prompt = trustedBatchPrompt(TabBatch("", emptyList()), many, listOf("Хобби"))
        val shown = (1..200).count { "\"Коллекция номер $it\"" in prompt }
        assertTrue(shown >= 25, "only $shown collections survived the trusted budget")
    }

    @Test
    fun `a collection the user named past the name limit is still reachable`() {
        // 47 characters — a perfectly ordinary name for someone who likes describing things, and past
        // NAME_LIMIT. Answering with it used to drop the whole placement: the length rule exists to
        // catch a model writing a sentence, and it was being applied to a name the user chose.
        val longTitle = "Статьи про Kotlin Multiplatform и корутины"
        val longId = Uuid.random()
        val into = collections + TriageCollection(longId, longTitle, "Дела")

        val plan = plan(answer(item(1, longTitle)), batch(tab(1, "https://kotlinlang.org/1")), into)

        assertEquals(1, plan.size)
        assertEquals(longTitle, plan.single().collectionTitle)
        // Matched to the real collection, not proposed as a new one of the same name.
        assertEquals(longId, plan.single().collectionId)
    }

    @Test
    fun `a section the user named past the name limit is still reachable`() {
        val longSection = "Длинное название секции про корутины и потоки"
        val sectionId = Uuid.random()
        val collectionId = Uuid.random()
        val into = listOf(
            TriageCollection(collectionId, "Kotlin", "Дела", listOf(TriageSection(sectionId, longSection))),
        )

        val plan = plan(answer(item(1, "Kotlin", longSection)), batch(tab(1, "https://kotlinlang.org/1")), into)

        assertEquals(longSection, plan.single().sectionTitle)
        assertEquals(sectionId, plan.single().sectionId)
    }

    @Test
    fun `an invented name past the limit is still refused — that is what the limit is for`() {
        // Nothing matches this, so it would have to be created. A model that answers with a sentence
        // must not get a collection named after the sentence.
        val rambling = "Это очень длинное предложение, которое модель написала вместо короткого имени"
        val plan = plan(answer(item(1, rambling)), batch(tab(1, "https://hh.ru/1")))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `the answer schema is valid for strict structured output — every property is required`() {
        // OpenAI's strict mode rejects a schema outright unless every key in `properties` is also in
        // `required`; optionality is expressed by a nullable type instead. `AiProxyService` sends this
        // with strict: true, so the two have to agree — see `batchSchema`'s own doc.
        val schema = Json.parseToJsonElement(batchSchema()).jsonObject
        val item = schema["properties"]!!.jsonObject["tabs"]!!.jsonObject["items"]!!.jsonObject
        val properties = item["properties"]!!.jsonObject.keys
        val required = item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(properties, required)
        // And the two that may be absent say so as a type, not by omission.
        listOf("section", "group").forEach { key ->
            val type = item["properties"]!!.jsonObject[key]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("string", "null"), type)
        }
    }

    @Test
    fun `a null section is read as no section, the way an absent one always was`() {
        val plan = plan(
            """{"tabs":[{"tab":1,"collection":"Работа","section":null,"group":null}]}""",
            batch(tab(1, "https://hh.ru/1")),
        )
        assertEquals("Работа", plan.single().collectionTitle)
        assertNull(plan.single().sectionTitle)
    }
}
