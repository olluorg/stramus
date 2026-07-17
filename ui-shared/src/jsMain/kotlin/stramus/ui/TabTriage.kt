@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.optgroup
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffectOnce
import react.useMemo
import react.useState
import stramus.core.ai.TriageAssignment
import stramus.core.ai.TriageCollection
import stramus.core.ai.TriageSection
import stramus.core.ai.TriageStep
import stramus.core.ai.TriageTab
import stramus.core.ai.preGroup
import stramus.core.ai.triage
import stramus.core.model.CardKind
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.CapturedTab
import stramus.core.repo.CardRepository
import stramus.core.repo.CardSectionRepository
import stramus.core.url.hostOf
import web.cssom.ClassName
import web.html.InputType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

/**
 * The value of the "don't save this one" option in a row's collection picker, and of "no section" in
 * its section picker. Empty rather than a word, so it cannot collide with something actually called
 * that.
 */
private const val NONE = ""

/** How many already-saved cards are shown against a site before the rest are merely counted. */
private const val RELATED_SHOWN = 3

/**
 * How many of a collection's cards are read to describe it to the model. A couple more than the
 * prompt will quote (`EXAMPLES_SHOWN` there), so that blanks and notes filtered out of them do not
 * leave a collection looking empty when it is not.
 */
private const val EXAMPLES_READ = 4

// The wrappers' InputType is opaque; named here the way the rest of the UI names the ones it uses.
private val CHECKBOX_INPUT: InputType = "checkbox".unsafeCast<InputType>()

/** Where one row is going, as the plan now has it: a collection, and a section within it or none. */
private data class Target(val collection: String, val section: String?)

/**
 * The plan so far: it grows a batch of tabs at a time as the model works down the window, and the
 * user may be editing the top of it while the bottom is still arriving.
 *
 * Only what the plan *decides* is here. Which collections and sections are new is not — that is
 * [TriageCatalog] against these titles, and a derived answer cannot fall out of step with the thing
 * it is derived from.
 */
private data class TriageState(
    val targets: Map<Int, Target> = emptyMap(),
    /**
     * For a collection the plan invented: the sidebar group it would be created in. Keyed by title,
     * because that is what a collection is here until it exists — and one title is one collection, so
     * one group. The user may change it; nothing else may.
     */
    val newGroups: Map<String, String> = emptyMap(),
    /** Batches decided so far, of how many. Equal when the run is done; the head shows it while it is not. */
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * What the store had to say, before the model was asked anything: it is quick, and it is certain.
 *
 * [collections] is what actually exists — with the ids the plan will need — so it is also what tells a
 * name in the plan from a name the plan invented.
 */
private data class TriageCatalog(
    val collections: List<TriageCollection> = emptyList(),
    /** Tabs whose URL is already saved somewhere: unticked by default, and said so on the row. */
    val duplicates: Set<Int> = emptySet(),
    /** Per site, the cards already saved from it — what makes "you have this already" visible. */
    val related: Map<String, List<RelatedCard>> = emptyMap(),
)

/** An already-saved card, as the preview names it: enough to recognise, not enough to open. */
private data class RelatedCard(val title: String, val collectionTitle: String)

external interface TabTriageProps : Props {
    var strings: Strings
    var assistant: AiAssistant

    /** The window's open tabs — what is about to be sorted. */
    var tabs: List<CapturedTab>

    /**
     * The collections a tab may be put into: the writable ones, which is the caller's to decide.
     *
     * Not named `collections` — nor [savedCards] `cards`, nor [savedSections] `cardSections`: `App`
     * holds state under all three names, and inside the builder that sets a prop a local of the same
     * name wins. `collections = ...` there would not set this prop at all, it would call `App`'s own
     * state setter, during render, for ever (React #301). The types match exactly, so nothing catches
     * it but the browser. A prop of this component must be named after nothing `App` has.
     */
    var intoCollections: List<Collection>

    /** Read to find what is saved already. Only [CardRepository.search] is used, and only to read. */
    var savedCards: CardRepository

    /** Read for the sections inside each collection — what the plan may put a card under. Read-only here. */
    var savedSections: CardSectionRepository

    /**
     * The sidebar sections, so each collection can be shown to the model under the group it lives in.
     * Not decoration: see [TriageCollection.inSection] — a collection's name means what its group says.
     *
     * Not named `sections`, which is `App`'s own state; see the note on [intoCollections].
     */
    var sidebarSections: List<Section>

    /** The sidebar section a collection the model invented would be created in, to be said out loud. */
    var newCollectionsIn: String

    /** True where the setting says a saved tab is closed — the button has to say which it will do. */
    var closesTabs: Boolean

    /** False with no collection open: then the summary can be copied but not kept. */
    var canSaveSummary: Boolean

    var onSaveSummary: (String, String) -> Unit
    var onApply: (List<TriageAssignment>) -> Unit
    var onClose: () -> Unit
}

/**
 * The tabs of a window, sorted into collections and the sections inside them by the browser's own
 * model — shown, and not yet done.
 *
 * The whole of this feature is the waiting: a plan the user has read and corrected is worth having,
 * and a plan applied the moment the model finished writing it is a mess of forty cards in collections
 * nobody chose, which is exactly the work the feature was supposed to save. So nothing here touches
 * the store until the button is pressed. Every row can be moved to another collection, put under
 * another section, or dropped from the plan; a row whose page is saved already arrives unticked,
 * because the common case for it is that the user does not want it twice.
 *
 * The model runs on this machine (see `BuiltInAi`), so a window of tabs — titles, URLs, and the names
 * of the user's own collections — is read by nothing that is not already on it. That is not a detail
 * of the implementation: it is why this exists as a local feature rather than as a request somewhere
 * with everything the user has open in it.
 *
 * What the model is asked is one batch of tabs at a time (`batchPrompt`), and it answers per *tab*:
 * one site's tabs may go to different collections, which is the common case and the reason this is
 * not asked per site. What comes back is checked rather than trusted (`planForBatch`): a tab that is
 * not in the batch, a name that is a paragraph, are dropped, and those tabs arrive unassigned. That
 * is the failure this feature is built to have — less done, never wrong.
 */
val TabTriageModal = FC<TabTriageProps> { props ->
    val s = props.strings

    // [plan] and [dropped] are both written from the run — a coroutine that outlives the render it
    // started on — and from the user's clicks, which land between the batches. So they are never
    // assigned from a value read out of the enclosing render: `by` reads what the state was *when the
    // closure was made*, and the run's closure is made once, on the first render, where the plan is
    // empty. Every batch would then be written onto that same empty plan and only the last would
    // survive. The setters take the current value and hand back the next, which is the only form that
    // is correct in both places.
    val planState = useState(TriageState())
    var plan by planState
    val setPlan = planState.component2()

    val droppedState = useState<Set<Int>>(emptySet())
    var dropped by droppedState
    val setDropped = droppedState.component2()

    var catalog by useState(TriageCatalog())
    var summary by useState("")
    var downloading by useState<Double?>(null)
    var error by useState<String?>(null)

    // The window's pages, gathered by site and with the duplicates already collapsed. Derived from the
    // props rather than held: it is what the tabs *are*, and the run has no say in it.
    val groups = useMemo(props.tabs) { preGroup(props.tabs.map { TriageTab(it.id, it.title, it.url) }) }
    val byId = useMemo(props.tabs) { props.tabs.associateBy { it.id } }
    // One row per page — not per tab: the same page open twice was two identical rows to read.
    val rows = useMemo(groups, byId) { groups.flatMap { group -> group.tabs.mapNotNull { byId[it.id] } } }

    // Read the store, then ask the model — one effect, because the second needs what the first found
    // and cannot read it back out of state: `catalog` inside this closure would be the empty value it
    // had on the first render (see the note on the setters above). So `known` stays a local, and the
    // sections are read once rather than once per reader.
    //
    // The store goes first because it is quick and certain, and because it decides which rows arrive
    // unticked. Nothing waits on it to be *shown*: the rows come from the props, so the window is
    // never a spinner — everything is on screen, unsorted, while the model works down the batches.
    useEffectOnce {
        val groupNames = props.sidebarSections.associateBy({ it.id }, { it.title })
        val known = props.intoCollections.map { collection ->
            val sections = runCatching { props.savedSections.byCollection(collection.id) }
                .getOrDefault(emptyList())
                .sortedBy { it.orderKey }
            // What is in the collection, to be quoted to the model as what the collection *is*. Links
            // only: a note the user wrote, or a file they dropped in, says less about where a browser
            // tab belongs than a link already sitting there does. Cheap, and already sorted by hand —
            // this is the user's own judgement being handed back to the model.
            val examples = runCatching { props.savedCards.byCollection(collection.id) }
                .getOrDefault(emptyList())
                .filter { it.kind == CardKind.LINK && it.title.isNotBlank() }
                .take(EXAMPLES_READ)
                .map { it.title }
            TriageCollection(
                id = collection.id,
                title = collection.title,
                inSection = groupNames[collection.sectionId],
                sections = sections.map { TriageSection(it.id, it.title) },
                examples = examples,
            )
        }
        val byTitle = props.intoCollections.associateBy({ it.id }, { it.title })
        val savedUrls = mutableSetOf<String>()
        val related = mutableMapOf<String, List<RelatedCard>>()
        groups.forEach { group ->
            // `search` matches a card's URL, so a site's host finds the cards saved from it. Asked of
            // the store rather than of the model: a question with a certain answer is not one for a model.
            val found = runCatching { props.savedCards.search(group.host) }.getOrDefault(emptyList())
                .filter { hostOf(it.url) == group.host }
            found.forEach { savedUrls += it.url }
            if (found.isNotEmpty()) {
                related[group.host] = found.map { card -> RelatedCard(card.title, byTitle[card.collectionId] ?: "") }
            }
        }
        val duplicates = rows.filter { it.url in savedUrls }.map { it.id }.toSet()
        catalog = TriageCatalog(known, duplicates, related)
        // The plan is a proposal, and proposing a second copy of something is the one case where the
        // user almost certainly means no. They can tick it back — and a row they have already unticked
        // by hand while this query was running must stay unticked, hence the transform.
        if (duplicates.isNotEmpty()) setDropped { it + duplicates }

        try {
            if (props.assistant.availability() == AiAvailability.UNAVAILABLE) {
                error = s.aiUnavailable
                return@useEffectOnce
            }
            triage(
                ai = props.assistant,
                systemPrompt = s.aiTriageSystemPrompt,
                groups = groups,
                known = known,
                sidebarGroups = props.sidebarSections.map { it.title },
                newCollectionsIn = props.newCollectionsIn,
                // The plain assistant framing: the summary is prose, and the triage's own system
                // prompt would have the model answer it in JSON.
                summarySystemPrompt = s.aiSystemPrompt,
                onDownloadProgress = { progress -> downloading = progress },
            ).collect { step ->
                downloading = null
                when (step) {
                    // Onto the plan as it now stands, not as it stood when this closure was made:
                    // every batch before this one is in it, and so is anything the user has moved.
                    is TriageStep.Placed -> setPlan { current ->
                        current.copy(
                            targets = current.targets + step.assignments.associate {
                                it.tabId to Target(it.collectionTitle, it.sectionTitle)
                            },
                            // Only for collections that do not exist: an existing one is already
                            // somewhere. A group the user has since chosen by hand stands.
                            newGroups = current.newGroups + step.assignments
                                .filter { it.collectionId == null && it.groupTitle != null }
                                .filter { it.collectionTitle !in current.newGroups }
                                .associate { it.collectionTitle to it.groupTitle!! },
                            done = step.done,
                            total = step.total,
                        )
                    }
                    is TriageStep.Summarised -> summary = step.text
                }
            }
        } catch (e: CancellationException) {
            throw e // the window was closed: nobody is waiting for this
        } catch (e: Throwable) {
            // Whatever was placed before it broke is still a plan, and still the user's to apply.
            error = e.message ?: s.aiFailed
        }
    }

    fun targetOf(tab: CapturedTab): Target? = if (tab.id in dropped) null else plan.targets[tab.id]

    /** The collection of this title that already exists, if it does — what makes it not a new one. */
    fun existing(title: String): TriageCollection? =
        catalog.collections.firstOrNull { it.title.equals(title, ignoreCase = true) }

    /** Every collection a row may be sent to: the ones there are, plus the ones the run has invented. */
    val collectionTitles = catalog.collections.map { it.title } +
        plan.targets.values.map { it.collection }.filter { existing(it) == null }.distinct()

    /**
     * The sidebar section a collection sits in — and, for one the plan invented, the one it will be
     * created in. So the tree can draw every collection under a branch, including the ones that do
     * not exist yet: they are going somewhere, and that somewhere is worth showing before it happens.
     */
    fun groupOf(title: String): String =
        existing(title)?.inSection ?: plan.newGroups[title] ?: props.newCollectionsIn

    /** Move a collection the plan invented into another sidebar section. Only an invented one moves. */
    fun setGroup(title: String, group: String) {
        setPlan { current -> current.copy(newGroups = current.newGroups + (title to group)) }
    }

    /**
     * The collection picker's options, in the sidebar's own shape — the same tree the plan is drawn
     * as, so the row's menu and the plan above it agree. Two collections may share a name in
     * different sections, and a flat menu could not tell them apart at all.
     */
    val titlesByGroup: List<Pair<String, List<String>>> = collectionTitles.groupBy { groupOf(it) }
        .toList()
        .sortedBy { (group, _) ->
            props.sidebarSections.indexOfFirst { it.title == group }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }

    /** The sections a row may be put under, in [collection]: the ones there are, plus the invented. */
    fun sectionsIn(collection: String): List<String> {
        val had = existing(collection)?.sections?.map { it.title } ?: emptyList()
        val proposed = plan.targets.values.filter { it.collection == collection }.mapNotNull { it.section }
        return (had + proposed).distinct()
    }

    fun isNewCollection(title: String): Boolean = existing(title) == null

    fun isNewSection(collection: String, section: String): Boolean =
        existing(collection)?.sections?.none { it.title.equals(section, ignoreCase = true) } ?: true

    fun setCollection(tabId: Int, title: String) {
        if (title == NONE) {
            setDropped { it + tabId }
            return
        }
        setDropped { it - tabId }
        // The section came from the old collection and means nothing under the new one — a divider
        // belongs to its collection. Keeping it would propose a section the user never saw offered
        // there. Worked out here, at the click, rather than inside the transform: it is a question
        // about what this render is showing the user, which is exactly what they just clicked on.
        val offeredThere = sectionsIn(title)
        val kept = plan.targets[tabId]?.section?.takeIf { section ->
            offeredThere.any { it.equals(section, ignoreCase = true) }
        }
        setPlan { current -> current.copy(targets = current.targets + (tabId to Target(title, kept))) }
    }

    fun setSection(tabId: Int, section: String) {
        setPlan { current ->
            val target = current.targets[tabId] ?: return@setPlan current
            current.copy(targets = current.targets + (tabId to target.copy(section = section.takeIf { it != NONE })))
        }
    }

    /**
     * The tick on a row. Unticking is only dropping it; ticking puts the row back where the run had
     * placed it — which is why the run's own placement is never erased by a tick, only overridden by
     * [dropped]. A row the run never placed (a batch it could not answer, or has not reached) has
     * nowhere of its own to go back to, so it takes the first collection there is.
     *
     * The run may land the very batch this row is in between the click and the state settling, so the
     * placement is read inside the transform rather than out here.
     */
    fun setTicked(tabId: Int, ticked: Boolean) {
        if (!ticked) {
            setDropped { it + tabId }
            return
        }
        setPlan { current ->
            when {
                current.targets.containsKey(tabId) -> current
                else -> collectionTitles.firstOrNull()
                    ?.let { current.copy(targets = current.targets + (tabId to Target(it, null))) }
                    ?: current
            }
        }
        setDropped { it - tabId }
    }

    /** The plan as it now stands, ready to be applied — the unticked and the unplaced left out. */
    val chosen = rows.mapNotNull { tab ->
        val target = targetOf(tab) ?: return@mapNotNull null
        val collection = existing(target.collection)
        val section = target.section?.let { wanted ->
            collection?.sections?.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
        }
        // The group travels with the plan: `applyTriage` needs it to know where to *make* a collection
        // that does not exist. For one that does, it is where it already is and changes nothing.
        TriageAssignment(
            tabId = tab.id,
            collectionTitle = target.collection,
            collectionId = collection?.id,
            sectionTitle = target.section,
            sectionId = section?.id,
            groupTitle = groupOf(target.collection),
        )
    }
    val running = plan.total > 0 && plan.done < plan.total && error == null

    modalShell(props.onClose, "modal triage-modal") {
        div {
            className = ClassName("modal-head")
            h3 {
                className = ClassName("ai-title")
                span { className = ClassName("ai-badge"); +s.aiChip }
                +s.triageHeading
            }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("triage-body")

            downloading?.let { progress ->
                div { className = ClassName("ai-download"); +s.aiDownloading((progress * 100).toInt()) }
            }
            error?.let { message -> div { className = ClassName("empty"); +message } }
            // Said while the batches are still being decided, and gone when they are: the groups below
            // fill in under it as they land, so this is a progress line and not a spinner.
            if (running) {
                div { className = ClassName("triage-progress"); +s.triageProgress(plan.done, plan.total) }
            }

            if (summary.isNotBlank()) {
                div {
                    className = ClassName("triage-summary")
                    div { className = ClassName("triage-summary-head"); +s.triageSummaryHeading }
                    markdownBlock("ai-answer", summary)
                    div {
                        className = ClassName("ai-turn-tools")
                        button {
                            className = ClassName("icon ai-tool")
                            hint(s.aiCopy)
                            onClick = { copyToClipboard(summary) }
                            +"⧉"
                        }
                        if (props.canSaveSummary) {
                            button {
                                className = ClassName("icon ai-tool")
                                hint(s.aiSaveNote)
                                onClick = { props.onSaveSummary(s.triageSummaryTitle, summary) }
                                +"⊞"
                            }
                        }
                    }
                }
            }

            // The plan drawn as the thing it is about: the sidebar's own tree — section, then the
            // collections in it, then the dividers in those. The user is going to check this against
            // a sidebar they know, and a flat list of collection names made them do that in their
            // head; a collection called "Поиск" means one thing under "Работа" and another under
            // "Личное", and the plan should say which without being asked.
            //
            // Only what the plan touches is drawn. This is a preview of a change, not a second copy
            // of the sidebar: a section holding nothing this run would be a branch with no fruit.
            val used = collectionTitles.filter { title -> chosen.any { it.collectionTitle == title } }
            val usedByGroup = used.groupBy { groupOf(it) }
            val groupOrder = props.sidebarSections.map { it.title }.filter { it in usedByGroup } +
                usedByGroup.keys.filter { group -> props.sidebarSections.none { it.title == group } }

            groupOrder.forEach { group ->
                val inGroup = usedByGroup[group].orEmpty()
                div {
                    key = "g:$group".unsafeCast<Key>()
                    className = ClassName("triage-branch")
                    div {
                        className = ClassName("triage-branch-head")
                        span { className = ClassName("triage-branch-title"); +group }
                        span {
                            className = ClassName("count")
                            +rows.count { targetOf(it)?.collection in inGroup }.toString()
                        }
                    }

                    inGroup.forEach { title ->
                        val going = rows.filter { targetOf(it)?.collection == title }
                        div {
                            key = "c:$title".unsafeCast<Key>()
                            className = ClassName("triage-group")
                            div {
                                className = ClassName("triage-group-head")
                                span { className = ClassName("triage-group-title"); +title }
                                if (isNewCollection(title)) {
                                    // A collection that does not exist yet is the one thing here the
                                    // user cannot undo by unticking a row, so it is said before it
                                    // happens — and where it will appear is theirs to choose. The
                                    // model proposes a group; this is how it is overruled. Moving it
                                    // redraws it under another branch, which is the whole answer to
                                    // "why is Электроника under Работа".
                                    span {
                                        className = ClassName("triage-new")
                                        hint(s.triageNewHint(group))
                                        +s.triageNew
                                    }
                                    select {
                                        className = ClassName("triage-target triage-group-pick")
                                        hint(s.triageGroupHint)
                                        value = group
                                        onChange = { e -> setGroup(title, e.target.value) }
                                        props.sidebarSections.forEach { section ->
                                            option {
                                                key = section.title.unsafeCast<Key>()
                                                value = section.title
                                                +section.title
                                            }
                                        }
                                    }
                                }
                                span { className = ClassName("count"); +going.size.toString() }
                            }

                            val ungrouped = going.filter { targetOf(it)?.section == null }
                            val sections = going.mapNotNull { targetOf(it)?.section }.distinct()
                            // A collection with no sections in the plan is just its rows: a lone
                            // "Ungrouped" heading over all of them divides nothing.
                            if (sections.isEmpty()) {
                                triageRows(s, "u:$title", ungrouped, catalog, titlesByGroup, ::targetOf, ::sectionsIn, ::setTicked, ::setCollection, ::setSection)
                            } else {
                                if (ungrouped.isNotEmpty()) {
                                    triageSectionHead(s.ungrouped, isNew = false, count = ungrouped.size, strings = s, section = null)
                                    triageRows(s, "u:$title", ungrouped, catalog, titlesByGroup, ::targetOf, ::sectionsIn, ::setTicked, ::setCollection, ::setSection)
                                }
                                sections.forEach { section ->
                                    val under = going.filter { targetOf(it)?.section == section }
                                    triageSectionHead(section, isNewSection(title, section), under.size, s, section)
                                    triageRows(s, "s:$title/$section", under, catalog, titlesByGroup, ::targetOf, ::sectionsIn, ::setTicked, ::setCollection, ::setSection)
                                }
                            }
                        }
                    }
                }
            }

            // Everything not placed: the batches the run has yet to reach, the tabs it could not
            // answer for, and the rows the user unticked. All three are the same thing to act on — a
            // row the user may put somewhere — so they are one group rather than three.
            val leftOut = rows.filter { targetOf(it) == null }
            if (leftOut.isNotEmpty()) {
                div {
                    className = ClassName("triage-group triage-unsorted")
                    div {
                        className = ClassName("triage-group-head")
                        span { className = ClassName("triage-group-title"); +s.triageUnsorted }
                        span { className = ClassName("count"); +leftOut.size.toString() }
                    }
                    if (!running) div { className = ClassName("empty small"); +s.triageUnsortedHint }
                    triageRows(s, "left", leftOut, catalog, titlesByGroup, ::targetOf, ::sectionsIn, ::setTicked, ::setCollection, ::setSection)
                }
            }
        }

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.cancel }
            button {
                className = ClassName("btn primary")
                disabled = chosen.isEmpty()
                onClick = { props.onApply(chosen) }
                +s.triageApply(chosen.size, props.closesTabs)
            }
        }
    }
}

/** A divider inside a collection group: which section the rows under it are going into. */
private fun ChildrenBuilder.triageSectionHead(
    title: String,
    isNew: Boolean,
    count: Int,
    strings: Strings,
    section: String?,
) {
    div {
        key = "s:${section ?: "-"}".unsafeCast<Key>()
        className = ClassName("triage-section-head")
        span { className = ClassName("triage-section-title"); +title }
        if (isNew) {
            span { className = ClassName("triage-new"); hint(strings.triageNewSectionHint); +strings.triageNew }
        }
        span { className = ClassName("count"); +count.toString() }
    }
}

/**
 * The rows of one section: each tab, whether it is in the plan, and where it is going.
 *
 * The pickers are on every row rather than only on the ones the user might want to move, because
 * which ones those are is precisely what is not known here — the model's confidence is not a thing it
 * reports, and a row that cannot be corrected without first being untangled from a group is not a
 * correction the user will make.
 */
private fun ChildrenBuilder.triageRows(
    s: Strings,
    rowsKey: String,
    rows: List<CapturedTab>,
    catalog: TriageCatalog,
    titlesByGroup: List<Pair<String, List<String>>>,
    targetOf: (CapturedTab) -> Target?,
    sectionsIn: (String) -> List<String>,
    setTicked: (Int, Boolean) -> Unit,
    setCollection: (Int, String) -> Unit,
    setSection: (Int, String) -> Unit,
) {
    ul {
        key = rowsKey.unsafeCast<Key>()
        className = ClassName("triage-tabs")
        // What is already saved from a site is said once per site, under its first row here: the point
        // is "you have been here before", and repeating it under every tab would bury the plan. The
        // host of each row is worked out once — this runs again on every batch that lands.
        val hosts = rows.map { hostOf(it.url) }
        val firstOfHost = hosts.withIndex().distinctBy { it.value }.map { it.index }.toSet()

        rows.forEachIndexed { index, tab ->
            val target = targetOf(tab)
            li {
                key = tab.id.toString().unsafeCast<Key>()
                className = ClassName(if (target == null) "triage-tab skipped" else "triage-tab")
                label {
                    className = ClassName("triage-pick")
                    input {
                        type = CHECKBOX_INPUT
                        checked = target != null
                        // Where a ticked-back row goes is the run's business, not this row's: see
                        // `setTicked`, which still has what the model said about it.
                        onChange = { e -> setTicked(tab.id, e.target.checked) }
                    }
                    Favicon {
                        url = tab.url
                        favicon = tab.favicon
                    }
                    span {
                        className = ClassName("triage-tab-title")
                        hint(tab.title.ifBlank { tab.url })
                        +tab.title.ifBlank { hostOf(tab.url) }
                    }
                }
                if (tab.id in catalog.duplicates) {
                    span { className = ClassName("triage-dup"); hint(s.triageDuplicateHint); +s.triageDuplicate }
                }
                select {
                    className = ClassName("triage-target")
                    hint(s.triageMoveHint)
                    value = target?.collection ?: NONE
                    onChange = { e -> setCollection(tab.id, e.target.value) }
                    option { value = NONE; +s.triageSkip }
                    // Grouped by sidebar section, as the plan above is: "Поиск" under "Работа" and
                    // "Поиск" under "Личное" are different collections, and a flat menu of names
                    // could not say which is which.
                    titlesByGroup.forEach { (group, titles) ->
                        optgroup {
                            key = group.unsafeCast<Key>()
                            label = group
                            titles.forEach { title ->
                                option { key = title.unsafeCast<Key>(); value = title; +title }
                            }
                        }
                    }
                }
                // Only where the row is going somewhere, and only where that somewhere has dividers:
                // a picker offering nothing but "no section" is a control that cannot be used.
                val sections = target?.let { sectionsIn(it.collection) }.orEmpty()
                if (target != null && sections.isNotEmpty()) {
                    select {
                        className = ClassName("triage-target triage-section-pick")
                        hint(s.triageSectionHint)
                        value = target.section ?: NONE
                        onChange = { e -> setSection(tab.id, e.target.value) }
                        option { value = NONE; +s.triageNoSection }
                        sections.forEach { section ->
                            option { key = section.unsafeCast<Key>(); value = section; +section }
                        }
                    }
                }
            }
            catalog.related[hosts[index]]?.takeIf { index in firstOfHost }?.let { found ->
                li {
                    key = "rel:${tab.id}".unsafeCast<Key>()
                    className = ClassName("triage-related")
                    span { +s.triageRelated(hosts[index], found.size) }
                    found.take(RELATED_SHOWN).forEach { card ->
                        span {
                            key = card.title.unsafeCast<Key>()
                            className = ClassName("triage-related-card")
                            +if (card.collectionTitle.isBlank()) card.title else "${card.title} — ${card.collectionTitle}"
                        }
                    }
                }
            }
        }
    }
}
