@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import stramus.core.url.hostOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * Sorting a window of open tabs into collections and the sections inside them, as far as it can be
 * done without a browser: the grouping, the prompts, and — the part that matters — deciding which of
 * the model's answers is a proposal and which is nonsense.
 *
 * None of this asks the model anything; that is `triage` in the jsMain half, which is where the model
 * lives. What is here is everything an answer has to survive, which is why it is common code with
 * tests under it rather than something woven into a React effect where it could only be judged by
 * looking at it.
 *
 * The unit of decision is one *tab*. It was one site once, which was cheaper and wrong: a site is not
 * a topic. One `hh.ru` holds a job ad and an article about writing a CV, and no answer given about
 * "hh.ru" can put those in different places. So the model is asked about tabs — but in [batches], a
 * batch being some tabs of one site, because asking about forty tabs one at a time is forty waits for
 * no gain. The site still does the grouping: it is free, it is exact, and it means the tabs in a
 * batch are the ones whose titles are worth reading against each other.
 *
 * Batches are asked in order, and each is asked against the collections *as they stand including the
 * ones invented a moment ago* — so the second jobs board joins the "Работа" the first one caused
 * rather than founding "Вакансии" beside it. Asked in parallel, they could not.
 *
 * And each batch is asked *twice*, with only the answers that agree kept ([agreed]). A model this
 * small cannot tell you what it does not know — told to leave out what it is unsure of, it answers for
 * everything anyway — but it shows you, by answering differently the second time. That is the only
 * enforcement there is behind "a tab it cannot place must not be placed", and everything else here is
 * built to make its failure cheap: an unplaced tab is a row the user ticks, not a card in the wrong
 * collection.
 */

/** A tab as the triage sees it: what it is called, where it points, and how to find it again. */
data class TriageTab(val id: Int, val title: String, val url: String)

/** The tabs of one site, in the order the window holds them, one per page. */
data class TabGroup(val host: String, val tabs: List<TriageTab>)

/** Some tabs of one site — one question to the model, and one step of the plan. */
data class TabBatch(val host: String, val tabs: List<TriageTab>)

/**
 * A section inside a collection — the divider a card sits under. [id] null for one the plan has
 * invented but not yet made; null [id] means "not saved yet", never "no section".
 *
 * [examples] and [summary] are the section's own content signal, the same idea as
 * [TriageCollection.examples] one level down — a bare name like "Для сна" says nothing on its own, and
 * a model shown only the sidebar's own words for it will match on vibes rather than fact. [summary] is
 * [examples] read by the model rather than by a person, and is what [batchPrompt] shows in its place
 * once there is enough here that a couple of raw titles would not say much — see [summarizeCatalog].
 */
data class TriageSection(
    val id: Uuid?,
    val title: String,
    val examples: List<String> = emptyList(),
    val summary: String? = null,
)

/**
 * A collection a tab may be placed in: the sidebar group it lives in, and the sections it holds.
 *
 * [id] is null for one the plan has invented but not yet made — and those are put back into the list
 * the next batch is asked against, which is what keeps a run from inventing two names for one thing.
 *
 * [inSection] is the sidebar section holding it, and it is not decoration: a collection's name means
 * what its group says it means. "Поиск" on its own could be anything; "Поиск" inside "Работа" is a job
 * search, and a model shown the flat list will put a job ad in "Повышение квалификации" instead — as
 * one did. It is the cheapest context there is and it was missing.
 */
data class TriageCollection(
    val id: Uuid?,
    val title: String,
    val inSection: String?,
    val sections: List<TriageSection> = emptyList(),
    /**
     * A few things already saved here — what the collection *is*, as opposed to what it is called.
     *
     * The strongest context there is, and the last to be used. A name is a word the user chose once
     * and it can be a trap: shown "Поиск" in the group "Работа", the model filed a graphics card
     * someone was shopping for under it, because buying is a kind of searching too. Told the same
     * collection holds "Вакансия Kotlin developer" and "Отклик на вакансию", there is nothing left to
     * misread — and the user had to write none of it, having already sorted those cards by hand.
     */
    val examples: List<String> = emptyList(),
    /** [examples], read by the model into a sentence rather than quoted raw — see [TriageSection.summary]. */
    val summary: String? = null,
)

/**
 * Where one tab is proposed to go: a collection, and a section within it.
 *
 * A null [collectionId] or [sectionId] means that collection or section does not exist yet and saving
 * the plan would create it — which is why the UI marks those. [sectionTitle] null is the tab going in
 * ungrouped, which is a real answer and the common one: most collections have no sections at all.
 */
data class TriageAssignment(
    val tabId: Int,
    val collectionTitle: String,
    val collectionId: Uuid?,
    val sectionTitle: String?,
    val sectionId: Uuid?,
    /**
     * The sidebar group the collection belongs in. For one that exists, it is simply where it already
     * is. For one the plan invents, it is where creating it would put it — which is a real decision
     * and used to be made by nobody: every invented collection went into whichever section the user
     * happened to have open, so a new "Электроника" landed under "Работа". Null means the model
     * offered no group, or offered one that is not a group; the caller then falls back.
     */
    val groupTitle: String?,
)

/**
 * How many tabs are asked about at once.
 *
 * The trade the whole pipeline turns on. Bigger is fewer waits but a longer prompt, and a small model
 * given thirty titles starts answering about the first few and inventing the rest. Ten titles is a
 * paragraph — comfortably inside the context, and short enough that the answer is still about all of
 * them. A site with more tabs than this is simply more than one batch.
 */
private const val TABS_PER_ASK = 10

/** A title is quoted to the model this far and no further: the tail of a long one is never the topic. */
private const val TITLE_SAMPLE_LIMIT = 80

/** A collection or section name is a name. Anything longer is the model having written a sentence. */
private const val NAME_LIMIT = 40

/** How many of a collection's cards are quoted as what it holds, and how much of each one. */
private const val EXAMPLES_SHOWN = 2
private const val EXAMPLE_LIMIT = 44

/**
 * [EXAMPLES_SHOWN] and [EXAMPLE_LIMIT] for a *trusted* batch. Two titles clipped to 44 characters is what
 * a small on-device model's context can spare; it is not what a collection *is*, and telling a capable
 * model no more than that was the reason one run invented a second "stramus" alongside the user's own
 * "Stramus(extension)" — two clipped titles were not enough to recognise it by, and the name alone is
 * precisely what [appendCollections] tells the model not to judge by. The catalog's own budget
 * ([TRUSTED_COLLECTIONS_BUDGET]) was raised for exactly this and then went almost unspent, because the
 * lines it budgets for were still being written to the small model's measurements.
 */
private const val TRUSTED_EXAMPLES_SHOWN = 8
private const val TRUSTED_EXAMPLE_LIMIT = 80

/**
 * A ceiling on a *section's* own examples, whatever its collection is allowed.
 *
 * A collection is described once; its sections are described one after another, so whatever a section
 * costs is multiplied by however many it has. Left uncapped at [TRUSTED_EXAMPLES_SHOWN], a collection
 * with four sections cost some 3 000 characters on its own, and measuring it showed 40 000 characters of
 * catalog budget holding sixteen collections out of two hundred — the exact starvation the trusted budget
 * was raised to prevent, reintroduced from the other end. Three titles is enough to tell what a divider
 * holds; it is the collection that has to carry the argument.
 */
private const val SECTION_EXAMPLES_CAP = 3

/**
 * Above this many examples, a collection or section is described by [summarizeCatalog]'s summary
 * instead of [EXAMPLES_SHOWN] raw titles — the same threshold, on purpose: once there is more here than
 * would ever be quoted, quoting a couple is a coin flip about which two, and a summary reads all of it
 * instead. At or below it, the titles already say everything a summary would, for one fewer question.
 */
internal const val SUMMARIZE_ABOVE = EXAMPLES_SHOWN

/** How many of a collection's or section's cards are read as material for its summary. */
private const val SUMMARY_SOURCE_LIMIT = 12

/** A summary is a sentence, not a list — this is the line past which a "summary" is the model rambling. */
private const val SUMMARY_LIMIT = 120

/**
 * Roughly how much of the collection list is described to a batch. It is the one part of the prompt
 * that grows with the user rather than with the window — someone with eighty collections, each with
 * sections and cards quoted from it, would otherwise spend the whole context being introduced. What
 * does not fit is left out, and a collection the model was not shown is simply one it will not reuse
 * this run.
 *
 * Roomier than it was, because a line now carries the collection's contents and not merely its name.
 * That is the difference between a guess and an answer, so it is what the budget is spent on.
 */
private const val COLLECTIONS_BUDGET = 1500

/**
 * [COLLECTIONS_BUDGET]'s counterpart for a *trusted* batch — see [trustedBatchPrompt]. [COLLECTIONS_BUDGET]
 * was sized for the small on-device model's own context, and reusing it for a cloud model with a context
 * window in the hundreds of thousands of tokens defeated the reason cloud support exists at all: an
 * account with more than a dozen or so collections would have most of them silently missing from the
 * prompt, and a collection the model was never shown is one it cannot place a tab in, confidently correct
 * placement or not — a real run left several tabs unassigned that plainly belonged to collections that had
 * simply fallen off this budget. Input tokens are the cheap side of what a cloud call costs (see
 * `ServerConfig.openrouterModel`'s pricing note), so there is little reason to ration this as tightly.
 *
 * Only the default for callers with no better number — `AiProxyService.triage` (the one real caller)
 * works out an account's actual context room from `ServerConfig.openrouterContextTokens` and passes that
 * instead, since the account's own model choice is a better source of truth than a number fixed at
 * compile time.
 */
const val TRUSTED_COLLECTIONS_BUDGET = 40_000

/**
 * [tabs] gathered by site — sites in the order their first tab appears, one entry per page.
 *
 * The same page open in two tabs is one entry here: it would otherwise be two identical cards, and
 * two identical rows in a plan the user has to read. The first tab of the page is the one kept, so
 * the list still agrees with the tab strip the user is looking at. The others are not forgotten — the
 * caller closes tabs by URL, so the second tab of a saved page is closed with the first (see
 * `applyTriage`), which is the one thing "keep the first" must not quietly get wrong.
 */
fun preGroup(tabs: List<TriageTab>): List<TabGroup> =
    tabs.filter { it.url.isNotBlank() }
        .distinctBy { it.url }
        .groupBy { hostOf(it.url) }
        .map { (host, group) -> TabGroup(host, group) }

/**
 * The questions the run will ask, in order: each site's tabs cut into lengths the model can answer
 * about all of ([TABS_PER_ASK]).
 *
 * A batch never spans two sites even when there is room. The site is what makes the titles in a batch
 * worth reading against one another, and a batch of "three tabs from hh.ru and seven from YouTube"
 * would only invite the model to find a theme joining them.
 */
fun batches(groups: List<TabGroup>): List<TabBatch> =
    groups.flatMap { group -> group.tabs.chunked(TABS_PER_ASK).map { TabBatch(group.host, it) } }

/**
 * What the model is asked about one batch: the collections that exist and what is inside them, then
 * this batch's tabs, numbered.
 *
 * The collections are listed because the model's real job is *reuse* — a tab about Kotlin belongs in
 * the "Kotlin" the user already has, not in a second one spelled differently. Inventing a name is the
 * fallback, allowed but not encouraged, and [planForBatch] is what holds the model to the difference.
 *
 * Tabs are numbered rather than named back, so the answer can point at one in a word and cannot point
 * at a tab that is not here.
 */
fun batchPrompt(batch: TabBatch, collections: List<TriageCollection>, groups: List<String>): String = buildString {
    appendCollections(collections, groups, COLLECTIONS_BUDGET, EXAMPLES_SHOWN, EXAMPLE_LIMIT)
    append("These tabs are open on ").append(batch.host).append(":\n")
    batch.tabs.forEachIndexed { index, tab ->
        append(index + 1).append(". ")
        append(tab.title.take(TITLE_SAMPLE_LIMIT).ifBlank { tab.url.take(TITLE_SAMPLE_LIMIT) })
        append('\n')
    }
    appendPlacementInstructions(sameSite = true)
}

/**
 * [batchPrompt]'s counterpart for a *trusted* batch. The one real difference is the tab list:
 * [batchPrompt] can say "these are all on hh.ru" once, because a batch is one site by construction; a
 * trusted batch is not, so every line names its own tab's host instead.
 *
 * [collectionsBudget] defaults to [TRUSTED_COLLECTIONS_BUDGET] for callers with nothing better — see
 * [trustedBatchPromptFitting], which is what actually decides it from an account's real context room.
 */
fun trustedBatchPrompt(
    batch: TabBatch,
    collections: List<TriageCollection>,
    groups: List<String>,
    collectionsBudget: Int = TRUSTED_COLLECTIONS_BUDGET,
): String = buildString {
    appendCollections(collections, groups, collectionsBudget, TRUSTED_EXAMPLES_SHOWN, TRUSTED_EXAMPLE_LIMIT)
    append("These tabs are open:\n")
    batch.tabs.forEachIndexed { index, tab ->
        append(index + 1).append(". [").append(hostOf(tab.url)).append("] ")
        append(tab.title.take(TITLE_SAMPLE_LIMIT).ifBlank { tab.url.take(TITLE_SAMPLE_LIMIT) })
        append('\n')
    }
    appendPlacementInstructions(sameSite = false)
}

/**
 * As many of [tabs], from the front, as fit within [budget] — estimated the same way
 * [trustedBatchPrompt] renders a tab's own line, so the estimate and the actual prompt agree. [tabs] is
 * taken in the order it is given, which is the caller's to have arranged for locality: [preGroup] keeps
 * one site's tabs together, and because `groupBy` keeps a key's first-seen position, the sites themselves
 * come in the order the strip first reaches them.
 *
 * Note what that is and is not. Two tabs of one site stay adjacent; two neighbours in the strip that are
 * on *different* sites do not, since flattening runs each site's tabs out in turn. So the cut this makes
 * falls on a site boundary far more often than through the middle of a topic — which is what makes
 * cutting at all defensible — but it is site locality, not strip locality, doing the work.
 *
 * Never empty when [tabs] is not: a batch that cannot even fit one tab is a run stuck making no progress
 * at all, and a prompt that runs slightly over its budget is recoverable in a way that is not.
 */
fun selectByBudget(tabs: List<TriageTab>, budget: Int): List<TriageTab> {
    if (tabs.isEmpty()) return tabs
    val selected = mutableListOf<TriageTab>()
    var used = 0
    for ((index, tab) in tabs.withIndex()) {
        val line = "${index + 1}. [${hostOf(tab.url)}] ${tab.title.take(TITLE_SAMPLE_LIMIT).ifBlank { tab.url.take(TITLE_SAMPLE_LIMIT) }}\n"
        if (used + line.length > budget && selected.isNotEmpty()) break
        selected += tab
        used += line.length
    }
    return selected
}

/**
 * The whole of a trusted call's prompt-budgeting: fill in order of what matters most — the catalog first
 * (up to [collectionsBudget], itself capped so it cannot starve the tabs entirely — see the caller for
 * how that cap is chosen), then as many of [tabs] as fit in whatever [totalCharBudget] has left once the
 * catalog, the surrounding instructions, and the tab list's own header are accounted for.
 *
 * The measuring trick: [trustedBatchPrompt] is asked once with *no* tabs at all, which is exactly the
 * fixed cost around wherever the tab list will go — the catalog, "These tabs are open:", and the
 * placement instructions after it. What that call's length leaves of [totalCharBudget] is what
 * [selectByBudget] gets to spend, and the real prompt is then built once more, this time with the tabs it
 * chose.
 *
 * Returns the finished prompt together with the tabs it actually asked about — the caller needs both:
 * the prompt to send, and the tab ids to report back as [stramus.protocol.AiTriageResponse.consideredTabIds]
 * so whoever sent more tabs than fit knows which ones to ask about again.
 */
fun trustedBatchPromptFitting(
    tabs: List<TriageTab>,
    collections: List<TriageCollection>,
    groups: List<String>,
    totalCharBudget: Int,
    collectionsBudget: Int,
): Pair<String, List<TriageTab>> {
    val fixedCost = trustedBatchPrompt(TabBatch(host = "", tabs = emptyList()), collections, groups, collectionsBudget).length
    val tabsBudget = (totalCharBudget - fixedCost).coerceAtLeast(0)
    val selected = selectByBudget(tabs, tabsBudget)
    val prompt = trustedBatchPrompt(TabBatch(host = "", tabs = selected), collections, groups, collectionsBudget)
    return prompt to selected
}

/** The collections-and-sections preamble [batchPrompt] and [trustedBatchPrompt] both open with. */
private fun StringBuilder.appendCollections(
    collections: List<TriageCollection>,
    groups: List<String>,
    budget: Int,
    examplesShown: Int,
    exampleLimit: Int,
) {
    if (collections.isEmpty()) {
        append("The user has no collections yet. Name new ones.\n\n")
        return
    }
    // Flat, one collection per line, with the group as an attribute of it — never as a heading the
    // collections are nested under. Nesting was tried and it taught the model to read the levels off
    // by one: shown "Работа:" with "Поиск" indented beneath it, it answered with the collection
    // "Работа" and the section "Поиск" — the group became a collection and the collection became a
    // section. There is nothing to misread here: every line is a collection, and the only names inside
    // a collection are the ones after "sections:".
    append("The user's collections — one per line: the name, the sidebar group it lives in, the ")
    append("sections inside it — each said what it holds, same as the collection is — and some of ")
    append("what is already saved in the collection as a whole. Judge a collection or a section by ")
    append("what is in it, not by its name: a name can mean anything, its contents cannot. The ")
    append("group is context for the name too, and never a place to put a tab.\n")
    for (collection in collections) {
        val line = buildString {
            append("- \"").append(collection.title).append("\" (group: ").append(collection.inSection ?: "none")
            if (collection.sections.isNotEmpty()) {
                append("; sections: ")
                    .append(collection.sections.joinToString(", ") { sectionAbout(it, examplesShown.coerceAtMost(SECTION_EXAMPLES_CAP), exampleLimit) })
            }
            collectionAbout(collection, examplesShown, exampleLimit)?.let { append("; already here: ").append(it) }
            append(")\n")
        }
        if (length + line.length > budget) break
        append(line)
    }
    append("\nAnswer with a collection name from this list — never a group name. ")
    append("Only invent a new short name if a tab fits none of them")
    if (groups.isNotEmpty()) {
        append("; when you do, also give the group it belongs in, one of: ")
        append(groups.joinToString(", "))
    }
    append(".\n\n")
}

/** The tail both [batchPrompt] and [trustedBatchPrompt] end on — how to place a tab, and when not to. */
private fun StringBuilder.appendPlacementInstructions(sameSite: Boolean) {
    append("\nFor every tab, give its collection, and a section within that collection if one genuinely ")
    append("fits. ")
    if (sameSite) append("Tabs of this site may go to different collections. ")
    append("A section must match what the tab ")
    append("is actually about, not merely be the closest one on offer — reuse an existing section, even ")
    append("worded differently, only when the tab is truly about the same specific thing; invent a short ")
    append("new one only when the tab clearly needs dividing out and nothing existing is that. Where ")
    append("neither is a good match, leave the section out — that is the right answer far more often ")
    append("than forcing a weak one, and it is never a mistake.")
    // The model must be able to decline. Made to answer for every tab it will answer for every tab —
    // and an on-device model asked where a graphics card listing goes, given no collection for it,
    // will put it somewhere. Three runs put it in three different places. A tab left out costs the
    // user one click; a tab filed confidently in the wrong place costs them the trust of the whole
    // plan, which is the thing this feature is actually made of.
    append(" If you are not sure where a tab belongs, leave that tab out of your answer entirely — ")
    append("the user will place it themselves. Do not guess.")
}

/** What [batchPrompt] says a collection holds: its [TriageCollection.summary] if it has one, else a couple of raw titles. */
private fun collectionAbout(collection: TriageCollection, examplesShown: Int, exampleLimit: Int): String? = collection.summary
    ?: collection.examples.takeIf { it.isNotEmpty() }
        ?.take(examplesShown)
        ?.joinToString(", ") { "\"${it.take(exampleLimit)}\"" }

/** A section as [batchPrompt] names it — bare if it has nothing to say about itself, parenthesised if it does. */
private fun sectionAbout(section: TriageSection, examplesShown: Int, exampleLimit: Int): String {
    val about = section.summary
        ?: section.examples.takeIf { it.isNotEmpty() }
            ?.take(examplesShown)
            ?.joinToString("; ") { it.take(exampleLimit) }
    return if (about == null) section.title else "${section.title} ($about)"
}

/**
 * The shape a batch's answer must have, as the Prompt API's `responseConstraint` takes it.
 *
 * This is not politeness towards the model, it is the difference between a feature and a regex: asked
 * in prose, a small model answers in prose — with a preamble, a code fence, an apology — and none of
 * that can be told from an answer by looking at it. Constrained, what comes back is JSON of this shape
 * or the call fails, and a failure is something the UI can say out loud.
 *
 * `section` and `group` are optional in meaning but *not* by omission: they are nullable and listed in
 * `required` like everything else. That is not a style choice — OpenAI's strict structured-output mode
 * (which is how `AiProxyService` sends this, `strict: true`) rejects a schema outright unless every
 * property is required, and expresses "may be absent" as a null-able type instead. Left as it was, this
 * schema was accepted by one OpenAI-compatible proxy and would have been a 400 from OpenAI itself; worse,
 * a lenient provider that "fixes" it by making both mandatory turns "most collections have no sections"
 * into a model obliged to name one every time, which is exactly the invented-section failure the prompt
 * spends a paragraph trying to prevent. Null says the same thing omission did, and [planForBatch] already
 * reads a JSON null as "nothing here" — `contentOrNullIfNotString` sees `JsonNull` is not a string.
 *
 * The array is still not required to cover the batch. A tab the model is unsure of is a tab it is told to
 * leave out — see the end of [batchPrompt] — and one it leaves out arrives unassigned.
 */
fun batchSchema(): String =
    """
    {
      "type": "object",
      "properties": {
        "tabs": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "tab": { "type": "integer" },
              "collection": { "type": "string" },
              "section": { "type": ["string", "null"] },
              "group": { "type": ["string", "null"] }
            },
            "required": ["tab", "collection", "section", "group"],
            "additionalProperties": false
          }
        }
      },
      "required": ["tabs"],
      "additionalProperties": false
    }
    """.trimIndent()

/**
 * A batch's answer as placements — as many of them as can be trusted.
 *
 * Constraining the shape does not constrain the *content*: the schema says "an integer" and "a
 * string", and the model is free to number a tab that is not in the batch, number one twice, or put a
 * paragraph where a name goes. So nothing here is taken on the model's word — the number must be a
 * tab of this batch, the name must look like a name, and a name is matched against what exists before
 * it is allowed to be something new.
 *
 * A tab the answer does not mention, or mentions badly, is left out: it arrives unassigned and the
 * user places it. That is the shape of every failure here — the feature does less, never something
 * wrong.
 */
fun planForBatch(
    answer: String,
    tabs: List<TriageTab>,
    collections: List<TriageCollection>,
    groups: List<String>,
): List<TriageAssignment> {
    val items = runCatching {
        (Json.parseToJsonElement(answer) as? JsonObject)?.get("tabs") as? JsonArray
    }.getOrNull() ?: return emptyList()

    val taken = mutableSetOf<Int>()
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val number = (item["tab"] as? JsonPrimitive)?.intOrNullIfNotNumber() ?: return@mapNotNull null
        // The model numbered a tab that is not in this batch, or numbered one twice. Neither is a
        // placement, and neither is a near-miss worth guessing at.
        val tab = tabs.getOrNull(number - 1) ?: return@mapNotNull null
        if (!taken.add(tab.id)) return@mapNotNull null

        // An existing collection keeps its own title, not the model's spelling of it: matched
        // case-insensitively, "kotlin" must still save into "Kotlin".
        //
        // Matched *before* [cleanName] gets a say, and that order is the whole point: [NAME_LIMIT] is
        // there to reject a model that answered with a sentence where a name belonged, and a name the
        // user themselves gave a collection is not that, however long it runs. Checked the other way
        // round — as it was — an account with a collection named past the limit could never be answered
        // with at all: every tab correctly placed there was thrown away here, silently, as if the model
        // had rambled.
        val written = nameText((item["collection"] as? JsonPrimitive)?.contentOrNullIfNotString())
        val collection = written?.let { w -> collections.firstOrNull { it.title.trim().equals(w, ignoreCase = true) } }
        val collectionTitle = collection?.title ?: written?.takeIf { it.length <= NAME_LIMIT } ?: return@mapNotNull null

        // A section belongs to its collection, so it is matched only among that collection's own —
        // "Вакансии" in "Работа" is not the "Вакансии" in some other collection, and a section named
        // for a collection that is itself invented cannot exist yet either. Same order as above: an
        // existing section is named by the user, so its own length is not this function's to judge.
        val writtenSection = nameText((item["section"] as? JsonPrimitive)?.contentOrNullIfNotString())
        val section = writtenSection?.let { wanted ->
            collection?.sections?.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }
        }
        val sectionName = writtenSection?.takeIf { section != null || it.length <= NAME_LIMIT }
        // A collection that exists is already somewhere, and the model does not get to move it. Only
        // an invented one has a group to choose, and the choice must be a group that exists —
        // anything else falls back to the caller's own.
        val groupName = cleanName((item["group"] as? JsonPrimitive)?.contentOrNullIfNotString())
        TriageAssignment(
            tabId = tab.id,
            collectionTitle = collectionTitle,
            collectionId = collection?.id,
            sectionTitle = section?.title ?: sectionName,
            sectionId = section?.id,
            groupTitle = collection?.inSection ?: groupName?.let { wanted ->
                groups.firstOrNull { it.equals(wanted, ignoreCase = true) }
            },
        )
    }
}

/**
 * What two independent answers about the same batch *both* say about a tab's collection — the pairing
 * [agreed] and [sectionDisagreements] are both built from, so the two never drift apart on what counts
 * as "the same tab, agreed".
 *
 * A tab is kept only where both answers put it in the same collection. Where they disagree at all about
 * it, or where only one of them mentions the tab, it is dropped here — it arrives unassigned and the
 * user places it, which costs them one click, against a plan they cannot trust.
 */
private fun agreedCollections(
    first: List<TriageAssignment>,
    second: List<TriageAssignment>,
): List<Pair<TriageAssignment, TriageAssignment>> {
    val byTab = second.associateBy { it.tabId }
    return first.mapNotNull { one ->
        val other = byTab[one.tabId] ?: return@mapNotNull null
        if (!one.collectionTitle.equals(other.collectionTitle, ignoreCase = true)) return@mapNotNull null
        one to other
    }
}

/**
 * What two independent answers about the same batch *both* say — and nothing else.
 *
 * This is how "if the model cannot tell, it must not file the tab anywhere" is enforced rather than
 * requested. Asking nicely does not work: told to leave out what it is unsure of, a small model still
 * answers for everything, because it has no idea that it is unsure. But it does not have to know —
 * asked the same question twice it *shows* us, by answering differently. A graphics card listing went
 * to "Развлечение" one run and "Электроника" the next; a job ad goes to the same place every time.
 * Confidence is what survives being asked again, and that is a fact about the answers, not a claim by
 * the model about itself.
 *
 * Where the two agree on the collection but not the divider under it, the collection stands and the
 * divider is dropped here — that is a normal outcome, not a bug, for the two who named no section at
 * all, or named a section on one side only. Where both *did* name a section but spelled it differently,
 * that is not a disagreement so much as an open question, and it is [sectionDisagreements] that holds
 * it rather than this function settling it by string equality — see there for why.
 *
 * The price is two questions per batch instead of one. That is the whole cost of the feature doubled,
 * and it buys the only thing that makes a plan worth reading.
 */
fun agreed(first: List<TriageAssignment>, second: List<TriageAssignment>): List<TriageAssignment> =
    agreedCollections(first, second).map { (one, other) ->
        val sameSection = one.sectionTitle != null && one.sectionTitle.equals(other.sectionTitle, ignoreCase = true)
        if (sameSection) one else one.copy(sectionTitle = null, sectionId = null)
    }

/**
 * A tab where both answers agreed on the collection and both named *a* section, but not the same one by
 * spelling — [one] carries the first answer's placement (as [agreed] would keep it before dropping the
 * section), and [otherSectionTitle] / [otherSectionId] are the second answer's version of the divider.
 *
 * [otherSectionId] is non-null exactly when the second answer's spelling matched one of the collection's
 * *existing* sections — which [resolveSections] needs to prefer over a spelling that merely matches
 * nothing yet, on either side.
 */
data class SectionDisagreement(
    val one: TriageAssignment,
    val otherSectionTitle: String,
    val otherSectionId: Uuid?,
)

/**
 * The batch's tabs where [agreedCollections] agreed on a collection and both answers named a section,
 * but a different one by spelling — "Резюме" against "CV" is not the same fact as "Резюме" against
 * nothing, and treating it as one, the way plain string equality in [agreed] does, throws away the one
 * case where both answers actually tried. [sectionAdjudicationPrompt] is the question that settles it
 * instead of a string comparison.
 */
fun sectionDisagreements(first: List<TriageAssignment>, second: List<TriageAssignment>): List<SectionDisagreement> =
    agreedCollections(first, second).mapNotNull { (one, other) ->
        val a = one.sectionTitle
        val b = other.sectionTitle
        if (a == null || b == null || a.equals(b, ignoreCase = true)) return@mapNotNull null
        SectionDisagreement(one, b, other.sectionId)
    }

/**
 * What the model is asked to settle about a batch's [SectionDisagreement]s: for each tab, whether the
 * two spellings of its section are the same specific place inside the collection or two different ones.
 *
 * A tie-breaker, not a re-ask of the batch — the collection is already settled by then, so the question
 * is narrow and cheap, and it is asked at all only when there is something to settle: a batch with no
 * disagreement never pays for it.
 */
fun sectionAdjudicationPrompt(disagreements: List<SectionDisagreement>): String = buildString {
    append("Two answers about the same tabs each named a section for it, but spelled differently. For ")
    append("each pair, say whether the two names most likely mean the same specific place inside the ")
    append("collection — the same grouping worded two ways — or are genuinely two different ones.\n\n")
    disagreements.forEach { d ->
        append(d.one.tabId).append(". in \"").append(d.one.collectionTitle).append("\": \"")
        append(d.one.sectionTitle).append("\" vs \"").append(d.otherSectionTitle).append("\"\n")
    }
}

/** The shape [sectionAdjudicationPrompt]'s answer must have — one verdict per tab it was asked about. */
fun sectionAdjudicationSchema(): String =
    """
    {
      "type": "object",
      "properties": {
        "pairs": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "tab": { "type": "integer" },
              "same": { "type": "boolean" }
            },
            "required": ["tab", "same"],
            "additionalProperties": false
          }
        }
      },
      "required": ["pairs"],
      "additionalProperties": false
    }
    """.trimIndent()

/**
 * Which of [disagreements] the model's adjudication answer called the same place — everything else,
 * including a tab it did not mention or a malformed answer, is left as [agreed] already decided it: no
 * section, the same fallback a batch gets when there is nothing to adjudicate at all.
 */
fun adjudicatedSame(answer: String, disagreements: List<SectionDisagreement>): Set<Int> {
    val known = disagreements.mapTo(mutableSetOf()) { it.one.tabId }
    val items = runCatching {
        (Json.parseToJsonElement(answer) as? JsonObject)?.get("pairs") as? JsonArray
    }.getOrNull() ?: return emptySet()
    return items.mapNotNullTo(mutableSetOf()) { element ->
        val item = element as? JsonObject ?: return@mapNotNullTo null
        val tab = (item["tab"] as? JsonPrimitive)?.intOrNullIfNotNumber()?.takeIf { it in known } ?: return@mapNotNullTo null
        val same = (item["same"] as? JsonPrimitive)?.booleanOrNullIfNotBoolean() ?: return@mapNotNullTo null
        tab.takeIf { same }
    }
}

/**
 * [agreed]'s result, with a disagreement's section restored where [same] says the two spellings were the
 * same place after all.
 *
 * The restored title and id are not simply [SectionDisagreement.one]'s: whichever side's spelling
 * already matched an *existing* section of the collection wins, over one that only matches nothing yet
 * — a plan should reuse the section the user already has, not the wording that happened to come first.
 */
fun resolveSections(
    agreed: List<TriageAssignment>,
    disagreements: List<SectionDisagreement>,
    same: Set<Int>,
): List<TriageAssignment> {
    if (same.isEmpty()) return agreed
    val byTab = disagreements.associateBy { it.one.tabId }
    return agreed.map { assignment ->
        if (assignment.tabId !in same) return@map assignment
        val d = byTab[assignment.tabId] ?: return@map assignment
        val (title, id) = if (d.one.sectionId != null) {
            d.one.sectionTitle!! to d.one.sectionId
        } else {
            d.otherSectionId?.let { d.otherSectionTitle to it } ?: (d.one.sectionTitle!! to null)
        }
        assignment.copy(sectionTitle = title, sectionId = id)
    }
}

/**
 * The text of what the model wrote, tidied but not judged: its first non-blank line, unfenced and
 * unquoted. Null only when there was nothing there at all.
 *
 * Split out from [cleanName] because the two questions it used to answer at once are not the same
 * question. "What did it write?" has an answer for any reply; "is that shaped like a name?" only matters
 * where the reply has to *become* a name. Matching against a collection the user already has is the
 * first kind — see [planForBatch] — and running it through the second threw away perfectly good answers.
 */
internal fun nameText(raw: String?): String? {
    val line = raw?.lineSequence()
        ?.map { it.trim().trim('`').trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?: return null
    return line.trim('"', '\'', '«', '»', '“', '”', '.').trim().takeIf { it.isNotBlank() }
}

/**
 * What the model wrote, as a name it may *invent* — or null, if what it wrote is not one.
 *
 * The same reasoning as `cleanedTitle` in the rename box: a small model asked for a name will now and
 * then answer with a sentence, wrap it in quotes, or fence it as code. An invented name cannot be checked
 * against a source the way a cleaned-up title can — inventing one is the whole point — so what is
 * checked is that it is shaped like a name at all. A name that merely *matches* something the user
 * already named is not invented and is not checked this way: see [nameText].
 */
internal fun cleanName(raw: String?): String? = nameText(raw)?.takeIf { it.length <= NAME_LIMIT }

/**
 * What the model is asked to describe a collection or section by, once it holds more than
 * [SUMMARIZE_ABOVE] cards — see [summarizeCatalog]. [name] is shown only so the answer can talk about
 * the thing in ordinary words, never as something to describe instead of the titles: the model is not
 * asked "what is X", it is asked "what do these have in common", which is the question a name alone
 * cannot answer and a batch prompt needs answered.
 */
fun catalogSummaryPrompt(name: String, titles: List<String>): String = buildString {
    append("Titles of pages saved under \"").append(name).append("\":\n")
    titles.take(SUMMARY_SOURCE_LIMIT).forEach { append("- ").append(it.take(EXAMPLE_LIMIT)).append('\n') }
    append("\nIn well under 12 words, say what these have in common — specific enough that someone ")
    append("deciding whether a new, unrelated-looking page belongs here could tell from your words ")
    append("alone, not from the name \"").append(name).append("\" repeated back.")
}

/**
 * What the model wrote, as a short description — or null, if it wrote something else. The same
 * reasoning as [cleanName], stretched for a phrase rather than a word: a description this long or
 * shorter is a description, and anything past it is the model having written a paragraph instead.
 */
internal fun cleanSummary(raw: String?): String? {
    val line = raw?.lineSequence()
        ?.map { it.trim().trim('`').trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?: return null
    val text = line.trim('"', '\'', '«', '»', '“', '”', '.').trim()
    return text.takeIf { it.isNotBlank() && it.length <= SUMMARY_LIMIT }
}

/**
 * A JSON string's content, and null for anything else. `JsonPrimitive.content` renders a number or a
 * boolean as text rather than refusing, so a model that answered `{"collection": 3}` would otherwise
 * arrive as a collection named "3".
 */
private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null

/** A JSON number's value, and null for anything else — including a *string* holding digits. */
private fun JsonPrimitive.intOrNullIfNotNumber(): Int? = if (isString) null else content.toIntOrNull()

/** A JSON boolean's value, and null for anything else — including a *string* holding "true"/"false". */
private fun JsonPrimitive.booleanOrNullIfNotBoolean(): Boolean? =
    if (!isString && (content == "true" || content == "false")) content == "true" else null
