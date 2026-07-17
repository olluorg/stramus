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
 */
data class TriageSection(val id: Uuid?, val title: String)

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
 * Roughly how much of the session the summary is written from. The summary is one question, so it is
 * the one place a window's whole shape still has to fit a context — fitted by budget rather than by a
 * count of sites. Nothing is sorted differently for it.
 */
private const val SUMMARY_BUDGET = 1500

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
    if (collections.isEmpty()) {
        append("The user has no collections yet. Name new ones.\n\n")
    } else {
        // Flat, one collection per line, with the group as an attribute of it — never as a heading
        // the collections are nested under. Nesting was tried and it taught the model to read the
        // levels off by one: shown "Работа:" with "Поиск" indented beneath it, it answered with the
        // collection "Работа" and the section "Поиск" — the group became a collection and the
        // collection became a section. There is nothing to misread here: every line is a collection,
        // and the only names inside a collection are the ones after "sections:".
        append("The user's collections — one per line: the name, the sidebar group it lives in, the ")
        append("sections inside it, and some of what is already saved there. Judge a collection by ")
        append("what is in it, not by its name: a name can mean anything, its contents cannot. The ")
        append("group is context for the name too, and never a place to put a tab.\n")
        for (collection in collections) {
            val line = buildString {
                append("- \"").append(collection.title).append("\" (group: ").append(collection.inSection ?: "none")
                if (collection.sections.isNotEmpty()) {
                    append("; sections: ").append(collection.sections.joinToString(", ") { it.title })
                }
                if (collection.examples.isNotEmpty()) {
                    append("; already here: ")
                    append(collection.examples.take(EXAMPLES_SHOWN).joinToString(", ") { "\"${it.take(EXAMPLE_LIMIT)}\"" })
                }
                append(")\n")
            }
            if (length + line.length > COLLECTIONS_BUDGET) break
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
    append("These tabs are open on ").append(batch.host).append(":\n")
    batch.tabs.forEachIndexed { index, tab ->
        append(index + 1).append(". ")
        append(tab.title.take(TITLE_SAMPLE_LIMIT).ifBlank { tab.url.take(TITLE_SAMPLE_LIMIT) })
        append('\n')
    }
    append("\nFor every tab, give its collection, and a section within that collection if one fits. ")
    append("Tabs of this site may go to different collections. Leave the section out when none applies.")
    // The model must be able to decline. Made to answer for every tab it will answer for every tab —
    // and an on-device model asked where a graphics card listing goes, given no collection for it,
    // will put it somewhere. Three runs put it in three different places. A tab left out costs the
    // user one click; a tab filed confidently in the wrong place costs them the trust of the whole
    // plan, which is the thing this feature is actually made of.
    append(" If you are not sure where a tab belongs, leave that tab out of your answer entirely — ")
    append("the user will place it themselves. Do not guess.")
}

/**
 * The shape a batch's answer must have, as the Prompt API's `responseConstraint` takes it.
 *
 * This is not politeness towards the model, it is the difference between a feature and a regex: asked
 * in prose, a small model answers in prose — with a preamble, a code fence, an apology — and none of
 * that can be told from an answer by looking at it. Constrained, what comes back is JSON of this shape
 * or the call fails, and a failure is something the UI can say out loud.
 *
 * `section` and `group` are not required: most collections have no sections, and a model made to name
 * one every time would invent one every time; and `group` is only meaningful for a collection the
 * model has just invented, an existing one being somewhere already.
 *
 * Nor is the array required to cover the batch. A tab the model is unsure of is a tab it is told to
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
              "section": { "type": "string" },
              "group": { "type": "string" }
            },
            "required": ["tab", "collection"],
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
    batch: TabBatch,
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
        val tab = batch.tabs.getOrNull(number - 1) ?: return@mapNotNull null
        if (!taken.add(tab.id)) return@mapNotNull null

        val name = cleanName((item["collection"] as? JsonPrimitive)?.contentOrNullIfNotString())
            ?: return@mapNotNull null
        // An existing collection keeps its own title, not the model's spelling of it: matched
        // case-insensitively, "kotlin" must still save into "Kotlin".
        val collection = collections.firstOrNull { it.title.trim().equals(name, ignoreCase = true) }
        val collectionTitle = collection?.title ?: name

        // A section belongs to its collection, so it is matched only among that collection's own —
        // "Вакансии" in "Работа" is not the "Вакансии" in some other collection, and a section named
        // for a collection that is itself invented cannot exist yet either.
        val sectionName = cleanName((item["section"] as? JsonPrimitive)?.contentOrNullIfNotString())
        val section = sectionName?.let { wanted ->
            collection?.sections?.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }
        }
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
 * What the model is asked for the session summary: the sites, where they ended up, and a title each.
 *
 * Self-contained rather than a follow-up in the session that placed the tabs — there is no such
 * session, each batch having been asked in a clone of its own. It reads better for it: by the time
 * this is asked, the tabs have collection names against them, and a name the model itself chose says
 * more about what the user was doing than the host ever did.
 *
 * [placed] is host → the collections its tabs went to. Prose, and constrained by nothing: the user
 * reads it, and keeps it as a note or does not — there is nothing here to act on.
 */
fun summaryPrompt(groups: List<TabGroup>, placed: Map<String, Set<String>>): String = buildString {
    append("A browsing session had these pages open:\n")
    for (group in groups) {
        val line = buildString {
            append("- ").append(group.host)
            placed[group.host]?.takeIf { it.isNotEmpty() }?.let { append(" [").append(it.joinToString(", ")).append(']') }
            group.tabs.firstOrNull()?.let { append(": ").append(it.title.take(TITLE_SAMPLE_LIMIT)) }
            append('\n')
        }
        if (length + line.length > SUMMARY_BUDGET) break
        append(line)
    }
    append("\nIn two or three sentences: what was this session about? ")
    append("Write it for the user to read later, as a reminder of what they were doing. ")
    append("Do not list the sites back — say what the work was.")
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
 * So a tab is placed only where both answers put it in the same collection. Where they agree on the
 * collection but not the divider under it, the collection stands and the divider is dropped: the
 * agreed part of an answer is still an answer, and a tab going in ungrouped is a normal outcome. Where
 * they disagree at all about the collection, or where only one of them mentions the tab, it arrives
 * unassigned and the user places it — which costs them one click, against a plan they cannot trust.
 *
 * The price is two questions per batch instead of one. That is the whole cost of the feature doubled,
 * and it buys the only thing that makes a plan worth reading.
 */
fun agreed(first: List<TriageAssignment>, second: List<TriageAssignment>): List<TriageAssignment> {
    val byTab = second.associateBy { it.tabId }
    return first.mapNotNull { one ->
        val other = byTab[one.tabId] ?: return@mapNotNull null
        if (!one.collectionTitle.equals(other.collectionTitle, ignoreCase = true)) return@mapNotNull null
        val sameSection = one.sectionTitle != null && one.sectionTitle.equals(other.sectionTitle, ignoreCase = true)
        if (sameSection) one else one.copy(sectionTitle = null, sectionId = null)
    }
}

/**
 * What the model wrote, as a name — or null, if what it wrote is not one.
 *
 * The same reasoning as `cleanedTitle` in the rename box: a small model asked for a name will now and
 * then answer with a sentence, wrap it in quotes, or fence it as code. A name cannot be checked
 * against a source the way a cleaned-up title can — inventing one is the whole point — so what is
 * checked is that it is shaped like a name at all.
 */
internal fun cleanName(raw: String?): String? {
    val line = raw?.lineSequence()
        ?.map { it.trim().trim('`').trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?: return null
    val name = line.trim('"', '\'', '«', '»', '“', '”', '.').trim()
    return name.takeIf { it.isNotBlank() && it.length <= NAME_LIMIT }
}

/**
 * A JSON string's content, and null for anything else. `JsonPrimitive.content` renders a number or a
 * boolean as text rather than refusing, so a model that answered `{"collection": 3}` would otherwise
 * arrive as a collection named "3".
 */
private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null

/** A JSON number's value, and null for anything else — including a *string* holding digits. */
private fun JsonPrimitive.intOrNullIfNotNumber(): Int? = if (isString) null else content.toIntOrNull()
