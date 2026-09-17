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
import react.useEffect
import react.useEffectOnce
import react.useMemo
import react.useState
import kotlinx.coroutines.flow.Flow
import stramus.core.ai.TabGroup
import stramus.core.ai.TriageAssignment
import stramus.core.ai.TriageCollection
import stramus.core.ai.TriageSection
import stramus.core.ai.TriageStep
import stramus.core.ai.TriageTab
import stramus.core.ai.cloudTriage
import stramus.core.ai.preGroup
import stramus.core.ai.summarizeCatalog
import stramus.core.ai.triage
import stramus.core.model.CardKind
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.CapturedTab
import stramus.core.repo.CardRepository
import stramus.core.repo.CardSectionRepository
import stramus.core.sync.StramusApi
import stramus.core.url.hostOf
import stramus.core.url.normalizeUrl
import web.cssom.ClassName
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.console
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The value of the "don't save this one" option in a row's collection picker, and of "no section" in
 * its section picker. Empty rather than a word, so it cannot collide with something actually called
 * that.
 */
private const val NONE = ""

/** How many already-saved cards are shown against a site before the rest are merely counted. */
private const val RELATED_SHOWN = 3

/**
 * How many of a collection's — and, within it, a section's — cards are read as material for what the
 * model is told about it, whether that is a couple of titles quoted raw or a summary read from many
 * (see `summarizeCatalog`, `SUMMARIZE_ABOVE`). Generous on purpose: reading a card's title is cheap,
 * already-loaded metadata, no network call, and a summary is only as good as what it is written from.
 */
private const val EXAMPLES_READ = 20

/**
 * Where one row is going, as the plan now has it: a collection, and a section within it or none.
 *
 * [collectionId] and [sectionId] are what makes this a *place* rather than a pair of words. Null means
 * "does not exist yet", exactly as in [TriageAssignment], and applying the plan is what would create it.
 *
 * They are carried rather than looked up again at the end, and that is the whole point of their being
 * here. A cloud run's ids are resolved on the server, against the account's synced catalog; re-deriving
 * them here, from a list this side filters differently and compares more strictly, quietly turned a
 * collection that plainly exists into a new one — and applying that made a second "Stramus" beside the
 * user's own. A title is what a person reads; an id is what a card is saved into, and the two must not
 * be the same field doing both jobs.
 */
private data class Target(
    val collection: String,
    val section: String?,
    val collectionId: Uuid? = null,
    val sectionId: Uuid? = null,
)

/**
 * Where the modal is before it ever asks the model anything: two free, certain checks first, in this
 * order, each skipped when it has nothing to show. [SAVED] is a tab whose page is already a card
 * somewhere — the model would only place it and have the user untick it, see the old `triageDuplicate`
 * badge. [DUPES] is the same page open in more than one tab — not the model's business either way, and
 * a mess left for the user to notice on their own otherwise. Neither costs the model a question; only
 * [PLAN] does.
 */
private enum class TriagePhase { SAVED, DUPES, PLAN }

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
    /**
     * Collections whose group the *user* picked. A run that settles its groups late ([TriageStep.Regrouped])
     * must not overrule them: an answer that arrives after someone has moved a collection by hand is an
     * answer about a question they have already settled.
     */
    val userGroups: Set<String> = emptySet(),
    /** What is still being waited for, if anything — see [TriageStep.Asking]. */
    val asking: String? = null,
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

/**
 * Everything a row can be made to do, gathered up rather than handed down one argument at a time: it
 * is the same seven for every row on screen, always in the same order, and none of them is ever read
 * on its own by whatever is passing them along.
 */
private data class RowActions(
    val targetOf: (CapturedTab) -> Target?,
    val sectionsIn: (String) -> List<String>,
    val setTicked: (Int, Boolean) -> Unit,
    val setCollection: (Int, String) -> Unit,
    val setSection: (Int, String) -> Unit,
    /** Save this row now, on its own — see `saveOne`. */
    val saveOne: (CapturedTab) -> Unit,
    /** Close this row's tab, saving nothing — see `closeOne`. */
    val closeOne: (CapturedTab) -> Unit,
)

/**
 * The store's collections, read into the shape the model is shown — name, sidebar group, sections, and
 * a few saved cards as what the collection actually holds (see [TriageCollection.examples]).
 *
 * Read once by [TabTriageModal], when the plan is asked for.
 */
suspend fun knownCollections(
    collections: List<Collection>,
    sidebarSections: List<Section>,
    savedSections: CardSectionRepository,
    savedCards: CardRepository,
): List<TriageCollection> {
    val groupNames = sidebarSections.associateBy({ it.id }, { it.title })
    return collections.map { collection ->
        val sections = runCatching { savedSections.byCollection(collection.id) }
            .getOrDefault(emptyList())
            .sortedBy { it.orderKey }
        // What is in the collection, to be quoted to the model — or summarised — as what the
        // collection *is*. Links only: a note the user wrote, or a file they dropped in, says less
        // about where a browser tab belongs than a link already sitting there does. Cheap, and already
        // sorted by hand — this is the user's own judgement being handed back to the model.
        val cards = runCatching { savedCards.byCollection(collection.id) }
            .getOrDefault(emptyList())
            .filter { it.kind == CardKind.LINK && it.title.isNotBlank() }
        // Bucketed once, by whichever section (or none) each card sits under — one read of the
        // collection serves both its own examples and every one of its sections', rather than a
        // separate query per section for the same cards.
        val bySection = cards.groupBy { it.cardSectionId }
        TriageCollection(
            id = collection.id,
            title = collection.title,
            inSection = groupNames[collection.sectionId],
            sections = sections.map { section ->
                TriageSection(section.id, section.title, examples = bySection[section.id].orEmpty().take(EXAMPLES_READ).map { it.title })
            },
            examples = cards.take(EXAMPLES_READ).map { it.title },
        )
    }
}

/**
 * A plan written by something other than the model triage — `topicPlan`, which groups the tabs by the
 * words their titles share. The review is the same review, so it is the same window:
 * only the words at the top and the run behind it differ, and neither pre-step is shown, their questions
 * being about what the model should not spend a question on.
 */
class PlanSource(
    val heading: String,
    /** Said above the plan: what it was made from, and what saving it will and will not do. */
    val intro: String?,
    val unsortedHint: String,
    val run: (List<TabGroup>) -> Flow<TriageStep>,
)

external interface TabTriageProps : Props {
    var strings: Strings

    /** Where the plan comes from when it is not a model's — see [PlanSource]. Null for the model triage. */
    var source: PlanSource?

    /** The local, on-device model — read only when [cloud] is false; see the note there. */
    var assistant: AiAssistant?

    /**
     * The signed-in account's own connection to the server — read only when [cloud] is true.
     *
     * Not named `api`: `App` holds its own state under that name, and inside the builder that sets a
     * prop a local of the same name wins — see the note on [intoCollections].
     */
    var stramusApi: StramusApi

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

    /**
     * True where the run should ask the cloud model rather than the one on this machine — a wholly
     * different run, `cloudTriage` over [api] rather than `triage` over [assistant]: the cloud model's
     * whole catalog lives on the server, so there is no local [TriageCollection] list to pre-summarise
     * (`summarizeCatalog` is skipped) or to hand it in the first place.
     */
    var cloud: Boolean

    /** True where the setting says a saved tab is closed — the button has to say which it will do. */
    var closesTabs: Boolean

    /**
     * Close these tabs — what the two pre-steps act with, before the model ever sees the window, and
     * what a single row's × acts with once the plan is up.
     */
    var onCloseTabs: (List<Int>) -> Unit

    /**
     * Save this one row now, the modal staying open — the row's own ✓, as against [onApply], which is
     * the whole plan and the end of the run. Closes the tab afterwards on the same setting [closesTabs]
     * reports, so one row dealt with by hand means exactly what the same row would have meant at the end.
     */
    var onSaveOne: (TriageAssignment) -> Unit

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
    var downloading by useState<Double?>(null)
    var error by useState<String?>(null)

    // Where the modal is in the two free pre-steps — see [TriagePhase]. Null until the store has been
    // read: which of SAVED/DUPES/PLAN to start on depends on what it found, so there is nothing to
    // decide yet, and the model must not be asked before this is settled to something other than null.
    var phase by useState<TriagePhase?>(null)

    // Unticked rows of the current pre-step — closing is the default, per [triageStep], and this is
    // what a row opts out of it. Reset on every [advance]: a step's ticks are its own, not carried into
    // the next one's unrelated list of tabs.
    var keptOpen by useState<Set<Int>>(emptySet())

    // Rows already dealt with one at a time — saved by their own ✓, or closed by their own ×. They
    // leave the plan the moment they are acted on, which is what keeps the remaining list a list of
    // what is still to decide, and what keeps a row saved by hand out of the final apply a second time.
    //
    // Held here rather than left to the tab list to sort out, because it does not always: a saved row
    // whose tab stays open (the setting says so) is gone from the plan all the same, and a closed one
    // must go now rather than whenever the browser's own event comes back.
    var settled by useState<Set<Int>>(emptySet())

    // The window's pages, gathered by site and with the duplicates already collapsed. Derived from the
    // props rather than held: it is what the tabs *are*, and the run has no say in it.
    val groups = useMemo(props.tabs) { preGroup(props.tabs.map { TriageTab(it.id, it.title, it.url) }) }
    val byId = useMemo(props.tabs) { props.tabs.associateBy { it.id } }
    // One row per page — not per tab: the same page open twice was two identical rows to read.
    val rows = useMemo(groups, byId, settled) {
        groups.flatMap { group -> group.tabs.mapNotNull { byId[it.id] } }.filterNot { it.id in settled }
    }

    // The same page, open more than once — the second pre-step's business. A free, certain check, over
    // `props.tabs` rather than `rows`: `rows` has already collapsed these for the model (see `preGroup`),
    // which is exactly why the browser itself still needs telling. Keyed by the identity `openPage`
    // itself uses, so a trailing slash or a tracking parameter does not hide a duplicate from this.
    val openDupGroups = useMemo(props.tabs) {
        props.tabs.groupBy { normalizeUrl(it.url) }.filterKeys { it.isNotBlank() }.filterValues { it.size > 1 }
    }

    // What the model is actually asked about: [groups] with a tab dropped wherever its id is a known
    // duplicate — the whole point of asking first rather than letting the plan arrive with it unticked.
    // A site left with nothing to ask about disappears rather than being sent as an empty batch.
    val aiGroups = useMemo(groups, catalog) {
        groups.mapNotNull { group ->
            group.tabs.filter { it.id !in catalog.duplicates }.takeIf { it.isNotEmpty() }?.let { TabGroup(group.host, it) }
        }
    }

    // Read the store — quick, and certain — then decide where to start: the first pre-step that has
    // something to show, or [TriagePhase.PLAN] straight away if neither does. Nothing here waits to be
    // *shown*: the rows come from the props, so the window is never a spinner regardless of phase.
    useEffectOnce {
        val known = knownCollections(props.intoCollections, props.sidebarSections, props.savedSections, props.savedCards)
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
        // by hand while this query was running must stay unticked, hence the transform. Only matters for
        // whatever is left after the pre-step: a duplicate closed there never reaches the plan at all.
        if (duplicates.isNotEmpty()) setDropped { it + duplicates }
        phase = when {
            props.source != null -> TriagePhase.PLAN
            duplicates.isNotEmpty() -> TriagePhase.SAVED
            openDupGroups.isNotEmpty() -> TriagePhase.DUPES
            else -> TriagePhase.PLAN
        }
    }

    /** Past the pre-step [from] — to the next one with something to show, or straight to the plan. */
    fun advance(from: TriagePhase) {
        keptOpen = emptySet()
        phase = when (from) {
            TriagePhase.SAVED -> if (openDupGroups.isNotEmpty()) TriagePhase.DUPES else TriagePhase.PLAN
            TriagePhase.DUPES, TriagePhase.PLAN -> TriagePhase.PLAN
        }
    }

    // The model is asked only once [phase] has settled on PLAN — see the effect above, which is the one
    // place that ever sets it there for the first time. Runs exactly once for that reason: [phase] only
    // ever *reaches* PLAN, it does not leave it, so this fires on that one transition and never again.
    useEffect(phase) {
        if (phase != TriagePhase.PLAN) return@useEffect
        try {
            // Two different runs behind one flow of [TriageStep]s — [cloudTriage] over [props.stramusApi] when
            // the run is a cloud one, [triage] over [props.assistant] otherwise. The rest of this effect
            // does not care which: a step is a step regardless of which model wrote it.
            // How many tabs the run will ask about in total, known before it has answered anything —
            // so the progress line reads "0 of N" the moment the run starts rather than nothing at all
            // until the first step. Tabs for both runs, never batches: a cloud run does not know its
            // round count in advance at all (the server decides it mid-run), so tabs are the only unit
            // the two can both report — see `TriageStep.Placed`.
            setPlan { it.copy(total = aiGroups.sumOf { group -> group.tabs.size }) }

            val source = props.source
            val steps: Flow<TriageStep> = if (source != null) {
                source.run(aiGroups)
            } else if (props.cloud) {
                cloudTriage(props.stramusApi, aiGroups)
            } else {
                val assistant = props.assistant ?: run {
                    error = s.aiUnavailable
                    return@useEffect
                }
                val availability = assistant.availability()
                if (availability == AiAvailability.UNAVAILABLE) {
                    error = s.aiUnavailable
                    return@useEffect
                }
                // A collection or section holding more than a couple of cards is described by what it
                // is about rather than by a couple of their titles — see `summarizeCatalog`. Asked once
                // here, not per batch: every batch of this run reads the same summaries, which is also
                // what keeps this out of the pre-steps above — they must stay instant, and this is not.
                val known = summarizeCatalog(assistant, s.aiSystemPrompt, catalog.collections)
                triage(
                    ai = assistant,
                    systemPrompt = s.aiTriageSystemPrompt,
                    groups = aiGroups,
                    known = known,
                    sidebarGroups = props.sidebarSections.map { it.title },
                    newCollectionsIn = props.newCollectionsIn,
                    // The browser's `monitor` fires a progress event even for a model that is already
                    // on the machine — nothing is actually being fetched, and the number means nothing.
                    // Rather than try to tell a real download from that one apart by its numbers,
                    // `availability()` is asked first and believed: only a browser that just said "not
                    // ready yet" gets this wired up at all, so an already-available model never shows
                    // the banner regardless of what the browser's own event says.
                    onDownloadProgress = if (availability == AiAvailability.AVAILABLE) {
                        {}
                    } else {
                        { progress -> downloading = progress }
                    },
                )
            }
            steps.collect { step ->
                downloading = null
                when (step) {
                    // Onto the plan as it now stands, not as it stood when this closure was made:
                    // every batch before this one is in it, and so is anything the user has moved.
                    is TriageStep.Placed -> {
                        // The run settles *identity*; this browser is the authority on what that
                        // identity is called and where it sits. They can disagree: a cloud run answers
                        // out of the account's synced catalog, which may hold an older name than this
                        // browser shows — a collection renamed here and not yet pushed comes back under
                        // the name the server still has. Left as it arrived, that title matches no local
                        // collection, so the plan cannot group it, cannot offer its sections, and draws
                        // it under the fallback section as though it were about to be created; the card
                        // would still be saved into the right collection (the id is right), which is
                        // precisely what makes the wrong display so hard to argue with. Renamed to what
                        // this browser calls it, every lookup below works on names it actually has.
                        val assignments = step.assignments.map { a ->
                            val local = a.collectionId?.let { id -> catalog.collections.firstOrNull { it.id == id } }
                            val localSection = a.sectionId?.let { id -> local?.sections?.firstOrNull { it.id == id } }
                            a.copy(
                                collectionTitle = local?.title ?: a.collectionTitle,
                                sectionTitle = localSection?.title ?: a.sectionTitle,
                            )
                        }
                        // What the model decided, as it decides it — one line a tab, filterable on
                        // "[triage]" in devtools. Not a permanent feature: it exists to be read over
                        // someone's shoulder while a run happens, the plan already saying the same
                        // thing more slowly (see the tree below). A tab the batch left unassigned is
                        // not logged here — it is exactly the rows the "не разобрано" group shows.
                        assignments.forEach { assignment ->
                            val tab = byId[assignment.tabId]
                            val place = assignment.sectionTitle
                                ?.let { "${assignment.collectionTitle} / $it" }
                                ?: assignment.collectionTitle
                            val name = tab?.title?.takeIf { it.isNotBlank() } ?: tab?.url ?: "#${assignment.tabId}"
                            console.log("[triage] ${step.host} — $name → $place")
                        }
                        setPlan { current ->
                            current.copy(
                                targets = current.targets + assignments.associate {
                                    it.tabId to Target(it.collectionTitle, it.sectionTitle, it.collectionId, it.sectionId)
                                },
                                // Only for collections that do not exist: an existing one is already
                                // somewhere. A group the user has since chosen by hand stands.
                                newGroups = current.newGroups + assignments
                                    .filter { it.collectionId == null && it.groupTitle != null }
                                    .filter { it.collectionTitle !in current.newGroups }
                                    .associate { it.collectionTitle to it.groupTitle!! },
                                done = step.done,
                                total = step.total,
                            )
                        }
                    }

                    is TriageStep.Asking -> setPlan { current -> current.copy(asking = step.note) }

                    // The groups, settled after the plan was drawn. Only for collections that do not exist
                    // yet — an existing one is where it already is — and never for one the user has moved
                    // themselves in the meantime.
                    is TriageStep.Regrouped -> setPlan { current ->
                        val late = step.groups.filterKeys { it !in current.userGroups }
                        current.copy(newGroups = current.newGroups + late, asking = null)
                    }
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

    /**
     * The collection of this title that already exists, if it does — what makes it not a new one.
     *
     * Trimmed on both sides, the way `planForBatch` has always matched: a title with a stray space at
     * either end is the same collection to the person who named it, and one of the two comparisons
     * being stricter than the other is precisely how the same name ends up existing twice.
     */
    fun existing(title: String): TriageCollection? =
        catalog.collections.firstOrNull { it.title.trim().equals(title.trim(), ignoreCase = true) }

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
        setPlan { current ->
            current.copy(
                newGroups = current.newGroups + (title to group),
                // Remembered as the user's own, so a late answer cannot move it back — see [TriageState].
                userGroups = current.userGroups + title,
            )
        }
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

    /**
     * Collections the run resolved to a real id, keyed by the title it answered with — the ones this
     * side may not find in [catalog] at all. Small and rebuilt per render, which is fine: it is only
     * ever as long as the plan itself.
     */
    val resolvedByTitle: Map<String, Uuid> = plan.targets.values
        .mapNotNull { t -> t.collectionId?.let { t.collection.trim().lowercase() to it } }
        .toMap()

    /**
     * Whether applying this would *create* the collection — which is what the "new" badge claims, so it
     * has to be the same question `applyTriage` will ask. An id the run resolved settles it even where
     * this side's own catalog does not recognise the title: marking that "new" was how a collection that
     * already existed came to be announced, and then created, a second time.
     */
    fun isNewCollection(title: String): Boolean =
        existing(title) == null && title.trim().lowercase() !in resolvedByTitle

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
        // Picked by hand out of the list this side drew, so this side is exactly the right place to
        // resolve it — unlike a placement the run produced, whose identity is already settled.
        val picked = existing(title)
        val keptId = kept?.let { k -> picked?.sections?.firstOrNull { it.title.trim().equals(k.trim(), ignoreCase = true) }?.id }
        setPlan { current -> current.copy(targets = current.targets + (tabId to Target(title, kept, picked?.id, keptId))) }
    }

    fun setSection(tabId: Int, section: String) {
        val wanted = section.takeIf { it != NONE }
        setPlan { current ->
            val target = current.targets[tabId] ?: return@setPlan current
            val id = wanted?.let { w ->
                existing(target.collection)?.sections?.firstOrNull { it.title.trim().equals(w.trim(), ignoreCase = true) }?.id
            }
            current.copy(targets = current.targets + (tabId to target.copy(section = wanted, sectionId = id)))
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
                    ?.let { current.copy(targets = current.targets + (tabId to Target(it, null, existing(it)?.id))) }
                    ?: current
            }
        }
        setDropped { it - tabId }
    }

    /**
     * One row as the store would carry it out, or null for a row that is going nowhere — unticked, or
     * not placed yet. The one place a [Target] becomes a [TriageAssignment], so a row saved on its own
     * lands exactly where the same row would have landed at the end of the plan.
     */
    fun assignmentFor(tab: CapturedTab): TriageAssignment? {
        val target = targetOf(tab) ?: return null
        val collection = existing(target.collection)
        val section = target.section?.let { wanted ->
            collection?.sections?.firstOrNull { it.title.trim().equals(wanted.trim(), ignoreCase = true) }
        }
        // The group travels with the plan: `applyTriage` needs it to know where to *make* a collection
        // that does not exist. For one that does, it is where it already is and changes nothing.
        return TriageAssignment(
            tabId = tab.id,
            collectionTitle = target.collection,
            // What the run resolved wins; the local lookup is the fallback for a title that only this
            // side knows about — one the user typed into a row, or a collection made since the run began.
            collectionId = target.collectionId ?: collection?.id,
            sectionTitle = target.section,
            sectionId = target.sectionId ?: section?.id,
            groupTitle = groupOf(target.collection),
        )
    }

    /** The plan as it now stands, ready to be applied — the unticked and the unplaced left out. */
    val chosen = rows.mapNotNull { assignmentFor(it) }

    /**
     * This row, now, into the collection the plan has it going to — and out of the plan afterwards, so
     * the button that ends the run does not save it a second time.
     *
     * Why a row has its own button at all: a plan of forty rows is read from the top, and the ones the
     * user is already sure about are in the way of the ones they are not. Dealing with one where they
     * are looking beats scrolling back to a single "apply everything" once they have thought about all
     * of it — and a run interrupted halfway has still saved what it saved.
     */
    fun saveOne(tab: CapturedTab) {
        val assignment = assignmentFor(tab) ?: return
        props.onSaveOne(assignment)
        settled = settled + tab.id
    }

    /** This row's tab closed, and nothing saved — the row's own answer to "not this one, and not later". */
    fun closeOne(tab: CapturedTab) {
        props.onCloseTabs(listOf(tab.id))
        settled = settled + tab.id
    }

    val actions = RowActions(::targetOf, ::sectionsIn, ::setTicked, ::setCollection, ::setSection, ::saveOne, ::closeOne)
    val running = plan.total > 0 && plan.done < plan.total && error == null

    modalShell(props.onClose, "modal triage-modal") {
        div {
            className = ClassName("modal-head")
            h3 {
                className = ClassName("ai-title")
                val source = props.source
                if (source == null) {
                    span { className = ClassName("ai-badge"); +s.aiChip }
                    +s.triageHeading
                } else {
                    +source.heading
                }
            }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
        }

        if (phase == TriagePhase.SAVED) {
            triageStep(
                heading = s.triageSavedHeading,
                hintText = s.triageSavedHint,
                rows = props.tabs.filter { it.id in catalog.duplicates },
                keptOpen = keptOpen,
                onToggle = { id, keep -> keptOpen = if (keep) keptOpen + id else keptOpen - id },
                closeLabel = { count -> s.triageCloseStep(count) },
                skipLabel = s.triageSkipStep,
                onClose = { ids -> props.onCloseTabs(ids); advance(TriagePhase.SAVED) },
                onSkip = { advance(TriagePhase.SAVED) },
            )
        } else if (phase == TriagePhase.DUPES) {
            // The kept tab of each group is not shown — see [preGroup]'s own precedent, "the first tab
            // of the page is the one kept". What is offered here is only the ones closing would remove.
            triageStep(
                heading = s.triageDupesHeading,
                hintText = s.triageDupesHint,
                rows = openDupGroups.values.flatMap { it.drop(1) },
                keptOpen = keptOpen,
                onToggle = { id, keep -> keptOpen = if (keep) keptOpen + id else keptOpen - id },
                closeLabel = { count -> s.triageCloseStep(count) },
                skipLabel = s.triageSkipStep,
                onClose = { ids -> props.onCloseTabs(ids); advance(TriagePhase.DUPES) },
                onSkip = { advance(TriagePhase.DUPES) },
            )
        } else {
        div {
            className = ClassName("triage-body")

            props.source?.intro?.let { intro -> div { className = ClassName("triage-intro"); +intro } }
            downloading?.let { progress ->
                div { className = ClassName("ai-download"); +s.aiDownloading((progress * 100).toInt()) }
            }
            error?.let { message -> div { className = ClassName("empty"); +message } }
            // Said while the batches are still being decided, and gone when they are: the groups below
            // fill in under it as they land, so this is a progress line and not a spinner.
            if (running) {
                div { className = ClassName("triage-progress"); +s.triageProgress(plan.done, plan.total) }
            }
            // Said while something slower than the plan is still being waited for, and gone the moment it
            // answers. The plan underneath is readable and correctable throughout: this is a line about
            // one thing still to come, not a screen standing in for the window.
            plan.asking?.let { note -> div { className = ClassName("triage-progress"); +note } }

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
                                triageRows(s, "u:$title", ungrouped, catalog, titlesByGroup, actions)
                            } else {
                                if (ungrouped.isNotEmpty()) {
                                    triageSectionHead(s.ungrouped, isNew = false, count = ungrouped.size, strings = s, section = null)
                                    triageRows(s, "u:$title", ungrouped, catalog, titlesByGroup, actions)
                                }
                                sections.forEach { section ->
                                    val under = going.filter { targetOf(it)?.section == section }
                                    triageSectionHead(section, isNewSection(title, section), under.size, s, section)
                                    triageRows(s, "s:$title/$section", under, catalog, titlesByGroup, actions)
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
                    if (!running) {
                        div { className = ClassName("empty small"); +(props.source?.unsortedHint ?: s.triageUnsortedHint) }
                    }
                    triageRows(s, "left", leftOut, catalog, titlesByGroup, actions)
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
}

/**
 * One of the two pre-steps a run may open with — see [TriagePhase]. A heading, why, the tabs in
 * question, and a way past it. Shared between [TriagePhase.SAVED] and [TriagePhase.DUPES], which
 * differ only in which tabs they name and the words around them.
 *
 * Every row starts ticked — closing is the default, the same way a plan row starts placed — and
 * unticking one is how a single tab is kept open rather than all of them or none, without hunting for
 * an individual close button per row. [onClose] only ever hears about the ticked ones, [keptOpen] is
 * where the rest are kept — and the primary button's own count follows the ticks, not the full list.
 */
private fun ChildrenBuilder.triageStep(
    heading: String,
    hintText: String,
    rows: List<CapturedTab>,
    keptOpen: Set<Int>,
    onToggle: (Int, Boolean) -> Unit,
    closeLabel: (Int) -> String,
    skipLabel: String,
    onClose: (List<Int>) -> Unit,
    onSkip: () -> Unit,
) {
    val toClose = rows.filterNot { it.id in keptOpen }
    div {
        className = ClassName("triage-body")
        div {
            className = ClassName("triage-step")
            div { className = ClassName("triage-step-head"); +heading }
            div { className = ClassName("triage-step-hint"); +hintText }
            ul {
                className = ClassName("triage-tabs")
                rows.forEach { tab ->
                    li {
                        key = tab.id.toString().unsafeCast<Key>()
                        className = ClassName("triage-tab")
                        label {
                            className = ClassName("triage-pick")
                            input {
                                type = CHECKBOX_INPUT
                                checked = tab.id !in keptOpen
                                onChange = { e -> onToggle(tab.id, !e.target.checked) }
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
                    }
                }
            }
        }
    }
    div {
        className = ClassName("modal-actions")
        button { className = ClassName("btn"); onClick = { onSkip() }; +skipLabel }
        button {
            className = ClassName("btn primary")
            disabled = toClose.isEmpty()
            onClick = { onClose(toClose.map { it.id }) }
            +closeLabel(toClose.size)
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
    actions: RowActions,
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
            val target = actions.targetOf(tab)
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
                        onChange = { e -> actions.setTicked(tab.id, e.target.checked) }
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
                    onChange = { e -> actions.setCollection(tab.id, e.target.value) }
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
                val sections = target?.let { actions.sectionsIn(it.collection) }.orEmpty()
                if (target != null && sections.isNotEmpty()) {
                    select {
                        className = ClassName("triage-target triage-section-pick")
                        hint(s.triageSectionHint)
                        value = target.section ?: NONE
                        onChange = { e -> actions.setSection(tab.id, e.target.value) }
                        option { value = NONE; +s.triageNoSection }
                        sections.forEach { section ->
                            option { key = section.unsafeCast<Key>(); value = section; +section }
                        }
                    }
                }
                // The two ways to be done with this one row without waiting for the rest of the plan.
                // Both leave the list the moment they are pressed (see `saveOne` and `closeOne`), which
                // is what makes them worth pressing: the plan shrinks to what is still undecided.
                //
                // Saving is offered only where the row is actually going somewhere — a row nobody has
                // placed has no collection to be saved into, and a ✓ that does nothing is worse than
                // no ✓ at all. Closing is offered on every row: "not this one, and not later" is an
                // answer about a tab, and a tab is there either way.
                if (target != null) {
                    button {
                        className = ClassName("icon triage-act")
                        hint(s.triageSaveOneHint)
                        onClick = { actions.saveOne(tab) }
                        icon("check")
                    }
                }
                button {
                    className = ClassName("icon del triage-act")
                    hint(s.triageCloseOneHint)
                    onClick = { actions.closeOne(tab) }
                    icon("x")
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
