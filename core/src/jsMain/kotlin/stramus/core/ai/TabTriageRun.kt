@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiQuotaExceededException
import stramus.core.platform.AiSession
import stramus.core.sync.ApiException
import stramus.core.sync.StramusApi
import stramus.core.url.hostOf
import stramus.protocol.AiInventedCollection
import stramus.protocol.AiTriageRequest
import stramus.protocol.AiTriageTab

/**
 * The plan as it is written: a batch of tabs at a time, in the order the window holds them.
 *
 * A window of forty tabs is several questions to a model that answers in a second or two, so a plan
 * gathered up and handed over at the end is a minute of a spinner. These come out as they are decided
 * instead, and the user reads — and corrects — the top of the plan while the bottom is still being
 * written.
 */
sealed interface TriageStep {

    /**
     * One batch, decided. [assignments] holds only the tabs the model placed acceptably — the rest of
     * the batch stays unassigned for the user, which is not an error and does not stop the run.
     *
     * [done] of [total] is the progress to show, counted in *tabs* — the one unit both runs can
     * honestly report. Batches were the count once, and stopped meaning anything the moment
     * [cloudTriage] arrived: how many rounds a cloud run takes is decided server-side, mid-run, so
     * there is no total to show at the start. Tabs are known before either run asks anything.
     *
     * Which of these collections and sections are *new* is not said here: the UI has the catalog of
     * what exists and can see that for itself, and a fact derived where it is used cannot fall out of
     * step with the thing it is derived from.
     */
    data class Placed(
        val host: String,
        val done: Int,
        val total: Int,
        val assignments: List<TriageAssignment>,
    ) : TriageStep
}

/**
 * Sort the tabs of [groups] into [known] and whatever the model has to invent — a batch at a time.
 *
 * The shape of this is what leaves it without ceilings. One session is opened, framed with
 * [systemPrompt] — that is the one that may have to download the model, which is why
 * [onDownloadProgress] hangs off it — and every batch is then asked in a *clone* of it: same framing,
 * none of the history. So the context each question sees is ten tabs and the collection list, no
 * matter how many batches came before, and a window of two hundred tabs is not a bigger question than
 * a window of six. It is only a longer wait, which is what the [Flow] is for.
 *
 * Each batch costs two questions, not one: it is asked twice and only the agreeing answers are kept
 * (see [agreed]). That is the whole run's cost doubled, and it is what a plan the user can trust is
 * made of — an answer that does not come back the same was a guess. A third question is asked, but
 * only for a batch where the two agreed on a tab's collection and both named a section for it, just
 * differently spelled — see [sectionDisagreements] and [adjudicateSections]. Most batches never pay
 * for it.
 *
 * The clones are sequential, and not merely because the model is one: each batch is asked against the
 * collections as they stand *including the ones invented a moment ago*, so the second jobs board joins
 * the "Работа" the first one caused instead of founding "Вакансии" beside it. Asked in parallel they
 * could not, and the plan would arrive full of near-duplicate collections — which is exactly the
 * tidying up the user wanted done for them.
 *
 * [sidebarGroups] are the sidebar groups a new collection may be put in — the model picks one, and anything
 * that is not on this list is not a group. [newCollectionsIn] is where one goes when it did not pick:
 * a fallback, not the rule it used to be, back when every invented collection landed in whichever
 * section the user happened to have open.
 *
 * Either way the invented collection is offered back to the next batch *under its group* — the group
 * is what a collection's name means (see [TriageCollection.inSection]), and an invented one shown
 * groupless would be the one collection in the list the model cannot read properly.
 *
 * [precomputed] is what [BackgroundTriage] already worked out while the user was merely looking at a
 * tab, keyed by tab id — null for one it looked at and confidently left unassigned, absent for one it
 * never saw. Either way costs this run nothing: an id present in the map is never asked about, its
 * batch shrinking to whatever is left. It is still folded through [OfferedCatalog.reconcile] like
 * everything else, so a collection the background made up before the user created one of the same name
 * by hand — or before an earlier batch of *this* run invented it — is not saved as a second copy of it.
 *
 * Cancelling the collection — the user closed the window — stops the run and gives back every session.
 *
 * This is the local model's run. A cloud one is a different shape entirely — see [cloudTriage] — because
 * it does not hold [known] to hand the model in the first place: the catalog lives on the server now.
 */
fun triage(
    ai: AiAssistant,
    systemPrompt: String,
    groups: List<TabGroup>,
    known: List<TriageCollection>,
    sidebarGroups: List<String>,
    newCollectionsIn: String?,
    precomputed: Map<Int, TriageAssignment?> = emptyMap(),
    onDownloadProgress: (Double) -> Unit = {},
): Flow<TriageStep> = flow {
    val batches = batches(groups)
    val base = ai.start(systemPrompt, onDownloadProgress)
    try {
        val catalog = OfferedCatalog(known, newCollectionsIn)
        // Counted in tabs, not batches — see [TriageStep.Placed]. A batch is a unit only this run has;
        // tabs are what both runs and the person watching have in common.
        val totalTabs = batches.sumOf { it.tabs.size }
        var doneTabs = 0

        batches.forEach { batch ->
            // Whatever [BackgroundTriage] already settled for a tab is not asked about again — its
            // batch shrinks to the rest. Folded first, so a collection it made up is in [catalog]
            // before the rest of this very batch is asked, exactly as an earlier batch's would be.
            val (ready, pending) = batch.tabs.partition { it.id in precomputed }
            val cached = ready.mapNotNull { precomputed[it.id] }.map(catalog::reconcile)
            cached.forEach(catalog::fold)

            val fresh = if (pending.isEmpty()) {
                emptyList()
            } else {
                resolveBatch(base, TabBatch(batch.host, pending), catalog.asked(), sidebarGroups).map(catalog::reconcile)
            }
            fresh.forEach(catalog::fold)

            doneTabs += batch.tabs.size
            emit(TriageStep.Placed(batch.host, doneTabs, totalTabs, cached + fresh))
        }
    } finally {
        base.close()
    }
}

/**
 * [triage]'s cloud counterpart: the model, the prompt, and the whole collection catalog all live on the
 * server now (see [AiTriageRequest]'s class doc and the server's `AiCatalogService`) — this sends tabs
 * and reads back however many of them the server actually got to (`AiTriageResponse.consideredTabIds`).
 *
 * There is no fixed batch size to chunk by any more: how many tabs fit in one call depends on how big the
 * account's own catalog is to describe, which only the server knows (see `AiProxyService.triage`, which
 * works this out from its own account's collections and the model's real context room). So this sends
 * everything still unplaced, is told how much of it got asked about, and sends the rest again — as many
 * rounds as it takes, each one asked once and never twice the way [resolveBatch] asks the local model.
 *
 * [groups] is still read as [preGroup] left it — one site's tabs together, in the order that site was
 * first seen among the window's tabs — and flattened in that order rather than reshuffled: it is what
 * gives the server something sensible to cut a round off partway through, a site boundary being a far
 * better place to split than an arbitrary one. See `selectByBudget` for the limit of that claim: tabs of
 * one site stay together, neighbours in the strip on different sites do not.
 *
 * Not [triage] with a flag any more: that shape assumed a caller who already has [TriageCollection]s to
 * hand the model, which is exactly what this account's cloud path does not send any more. What it does
 * still need offering back round to round is [AiInventedCollection] — the one piece of the catalog the
 * server genuinely cannot know on its own, an invented collection not being synced until the plan this
 * run produces is applied — accumulated here from each round's own unmatched placements the same reason
 * [OfferedCatalog] does it for the local model, just without a full catalog to fold them into.
 */
fun cloudTriage(api: StramusApi, groups: List<TabGroup>): Flow<TriageStep> = flow {
    val ordered = groups.flatMap { it.tabs }
    val total = ordered.size
    var remaining = ordered
    var invented = emptyList<AiInventedCollection>()
    var rounds = 0

    while (remaining.isNotEmpty() && rounds < MAX_CLOUD_ROUNDS) {
        rounds++
        val request = AiTriageRequest(
            tabs = remaining.map { AiTriageTab(it.id, it.title, it.url) },
            invented = invented,
        )
        val response = try {
            api.aiTriage(request)
        } catch (e: ApiException) {
            // 429 is the server's `AiQuotaException` — the one failure a caller must not treat as "this
            // round, unanswered, move on": every round left this run would fail the same way, and a plan
            // quietly missing everything from here on is worse than an error that says why.
            if (e.status == 429) throw AiQuotaExceededException(e.message) else null
        } catch (e: Throwable) {
            null
        } ?: break

        // Progress is measured by what is *left*, not by adding up what each round claims to have
        // considered: the two agree when the server behaves, and when it does not — an id echoed back
        // that was never sent, the same id twice — this is the reading that cannot run past [total] or
        // stall the loop on a round that removed nothing.
        val considered = response.consideredTabIds.toSet()
        val left = remaining.filterNot { it.id in considered }
        if (left.size == remaining.size) break

        val assignments = response.assignments.map { it.toTriageAssignment() }
        invented = foldInvented(invented, assignments)
        remaining = left

        emit(TriageStep.Placed(host = "cloud", done = total - remaining.size, total = total, assignments = assignments))
    }

    // Whatever is still unplaced, this run is over — say so. The UI reads "finished" as `done == total`
    // (see `TabTriageModal`'s own `running`), so a run that stopped early on a failed round used to leave
    // it waiting on a batch that was never coming: no plan, no error, a spinner for ever. Ending on an
    // empty step is the same partial-plan outcome the local model's own failed batch produces, said out
    // loud instead of by omission.
    if (remaining.isNotEmpty()) {
        emit(TriageStep.Placed(host = "cloud", done = total, total = total, assignments = emptyList()))
    }
}

/**
 * How many times [cloudTriage] will go back for the tabs a round had no room for.
 *
 * A ceiling on what one run can cost, not a target: every round is one of the account's hundred a month
 * (see the server's `aiMonthlyLimit`), and how many tabs fit in a round is decided server-side from the
 * account's own catalog size — so without a bound here, one badly-configured context window turns a
 * single window of tabs into dozens of billed calls. Ten rounds is far more than a sane configuration
 * ever needs and still cheap enough to be worth failing at rather than spending past.
 */
private const val MAX_CLOUD_ROUNDS = 10

private fun stramus.protocol.AiTriageAssignment.toTriageAssignment() = TriageAssignment(
    tabId = tabId,
    collectionTitle = collectionTitle,
    collectionId = collectionId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
    sectionTitle = sectionTitle,
    sectionId = sectionId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
    groupTitle = groupTitle,
)

/**
 * [invented], with whatever [assignments] placed in a collection or a section the server did not already
 * know about — a null [TriageAssignment.collectionId] or [TriageAssignment.sectionId] alike, the same
 * meaning it carries everywhere else in this file. An assignment that matched something the server already
 * has needs nothing offered back: the next batch's own server-side catalog read already has it.
 */
private fun foldInvented(invented: List<AiInventedCollection>, assignments: List<TriageAssignment>): List<AiInventedCollection> {
    val byTitle = invented.associateByTo(LinkedHashMap()) { it.title.lowercase() }
    for (a in assignments) {
        if (a.collectionId != null) continue
        val key = a.collectionTitle.lowercase()
        val newSection = a.sectionTitle.takeIf { a.sectionId == null }
        val existing = byTitle[key]
        byTitle[key] = if (existing == null) {
            AiInventedCollection(a.collectionTitle, a.groupTitle, listOfNotNull(newSection))
        } else {
            val sections = if (newSection != null && existing.sections.none { it.equals(newSection, ignoreCase = true) }) {
                existing.sections + newSection
            } else {
                existing.sections
            }
            existing.copy(group = existing.group ?: a.groupTitle, sections = sections)
        }
    }
    return byTitle.values.toList()
}

/**
 * The catalog a run offers back to itself as it goes, growing to include whatever the model has invented
 * so far — collections and the sections inside them alike — so the next question sees them as reusable
 * rather than inventing a second spelling of the same thing. Shared by [triage], [cloudTriage] and
 * [BackgroundTriage], which each grow one of these across a different span: one run, one run, or a whole
 * page's lifetime.
 */
private class OfferedCatalog(known: List<TriageCollection>, private val newCollectionsIn: String?) {
    private val offered = known.toMutableList()

    /** What the next question is asked against. */
    fun asked(): List<TriageCollection> = offered

    /**
     * An assignment's ids as [asked] currently has them — always run before an assignment is folded in
     * or shown, so a placement worked out against a catalog that may since have moved on (a cached
     * background verdict, say) lands on the same collection a fresh answer would, id and all, rather
     * than duplicating it under a null id merely because it did not know about it yet.
     */
    fun reconcile(assignment: TriageAssignment): TriageAssignment {
        val collection = offered.firstOrNull { it.title.equals(assignment.collectionTitle, ignoreCase = true) }
        val section = assignment.sectionTitle?.let { wanted ->
            collection?.sections?.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
        }
        return assignment.copy(
            collectionId = collection?.id,
            sectionId = section?.id,
            groupTitle = collection?.inSection ?: assignment.groupTitle,
        )
    }

    /** Grows [asked] with whatever [assignment] needed that was not already there. */
    fun fold(assignment: TriageAssignment) {
        val existing = offered.indexOfFirst { it.title.equals(assignment.collectionTitle, ignoreCase = true) }
        if (existing < 0) {
            // A collection the model made up. It goes into the list the next question sees with no id
            // — [TriageAssignment] carries that null all the way to `applyTriage`, which is what
            // finally makes it.
            offered += TriageCollection(
                id = null,
                title = assignment.collectionTitle,
                inSection = assignment.groupTitle ?: newCollectionsIn,
                sections = listOfNotNull(assignment.sectionTitle?.let { TriageSection(null, it) }),
            )
        } else {
            // The collection is known; the section under it may still be new. Offering it back is
            // what stops the next question spelling the same divider a second way.
            val collection = offered[existing]
            val section = assignment.sectionTitle
            if (section != null && collection.sections.none { it.title.equals(section, ignoreCase = true) }) {
                offered[existing] = collection.copy(sections = collection.sections + TriageSection(null, section))
            }
        }
    }

    /**
     * Refresh [asked] from the store's real collections — [BackgroundTriage.seed]'s own doing. Whatever
     * this has invented since the last call and the store still does not have survives; an invention the
     * store *has* since gained (the user made one by hand, or an applied plan did) is dropped, so the
     * next question is offered the real one and not a copy of it under a different id.
     */
    fun reset(known: List<TriageCollection>) {
        val invented = offered.filter { it.id == null && known.none { k -> k.title.equals(it.title, ignoreCase = true) } }
        offered.clear()
        offered += known
        offered += invented
    }
}

/**
 * What a batch resolves to: asked twice, only what agrees kept (see [agreed]), and a leftover section
 * disagreement settled by [adjudicateSections] rather than dropped. The one piece of [triage] that
 * [BackgroundTriage] also runs, one tab at a time, ahead of the user asking for a plan at all.
 */
internal suspend fun resolveBatch(
    base: AiSession,
    batch: TabBatch,
    offered: List<TriageCollection>,
    sidebarGroups: List<String>,
): List<TriageAssignment> {
    // One batch the model choked on is one batch, not the run: a question that throws leaves its tabs
    // unassigned and the caller carries on. And a first answer with nothing in it has nothing to
    // confirm, so the second question is not worth asking. Except a quota running out ([aiCatching]) —
    // the next question fails the very same way, so this is the one failure that is not folded away.
    val first = aiCatching { ask(base, batch, offered, sidebarGroups) }.orEmpty()
    val second = if (first.isEmpty()) {
        emptyList()
    } else {
        aiCatching { ask(base, batch, offered, sidebarGroups) }.orEmpty()
    }
    val settled = agreed(first, second)
    // Both answers named a section for these, just spelled differently — a question of its own settles
    // it rather than the string equality [agreed] falls back to. Skipped when there is nothing to
    // settle, which is the common case: most tabs get no section, or the same one.
    val disagreements = sectionDisagreements(first, second)
    return if (disagreements.isEmpty()) {
        settled
    } else {
        val same = aiCatching { adjudicateSections(base, disagreements) }.orEmpty()
        resolveSections(settled, disagreements, same)
    }
}

/**
 * [runCatching], except an exhausted quota is not an ordinary failure to fold into "empty" — see
 * [AiQuotaExceededException]. Every other throw (a batch the model choked on, a network hiccup) is
 * swallowed exactly as before; this one is let through to whichever caller is prepared to stop and say
 * so, rather than asking a question that would fail the same way as the last ten.
 */
private suspend fun <T> aiCatching(block: suspend () -> T): T? = try {
    block()
} catch (e: AiQuotaExceededException) {
    throw e
} catch (e: Throwable) {
    null
}

/** One batch's question, in a session of its own that is given back the moment it has answered. */
private suspend fun ask(
    base: AiSession,
    batch: TabBatch,
    offered: List<TriageCollection>,
    groups: List<String>,
): List<TriageAssignment> = base.clone().use { session ->
    planForBatch(session.askJson(batchPrompt(batch, offered, groups), batchSchema()), batch.tabs, offered, groups)
}

/** The tie-breaker for a batch's [SectionDisagreement]s, in a session of its own — see [sectionAdjudicationPrompt]. */
private suspend fun adjudicateSections(base: AiSession, disagreements: List<SectionDisagreement>): Set<Int> =
    base.clone().use { session ->
        adjudicatedSame(session.askJson(sectionAdjudicationPrompt(disagreements), sectionAdjudicationSchema()), disagreements)
    }

/**
 * What each open tab would be triaged to, worked out while the user is merely looking at it rather than
 * when they finally ask for a plan — so that by the time they do, most of the wait is already spent.
 *
 * One long-lived session, cloned per tab exactly as [triage] clones per batch: opening a fresh one for
 * every tab activated in an ordinary session of browsing would be the expensive thing this exists to
 * avoid. [evaluate] is safe to call as often as a tab is merely re-activated — the cache is keyed on the
 * tab's id *and* its current url and title, so nothing is asked twice about a tab that has not changed,
 * and a tab that has (a navigation, not a new id) is asked about again rather than answered from stale
 * memory.
 *
 * A collection this cache invents is offered back to the next tab it looks at, the same reason [triage]
 * grows its own list batch to batch — spread here across the whole page's lifetime instead of one run,
 * because two tabs of one site opened minutes apart must still join one collection, not found two. See
 * [seed] for how that list is kept from drifting too far from what the store actually holds.
 *
 * Never the source of truth for a plan: [triage]'s own `reconcile` re-derives every id this hands it
 * against the catalog as it stands *then*, so a stale guess here costs at most a wasted invention, never
 * a wrong save.
 */
class BackgroundTriage(private val ai: AiAssistant, private val systemPrompt: String) {
    private var base: AiSession? = null
    private val catalog = OfferedCatalog(emptyList(), newCollectionsIn = null)
    private val cache = mutableMapOf<Int, CachedVerdict>()

    // Set once a quota-backed assistant says its month is spent, and never asked again this page's
    // lifetime: the next tab would fail exactly the same way, and a background feature that is meant to
    // be invisible must not spend the rest of the session finding that out one tab at a time. The local
    // model has no such ceiling and never sets this.
    private var quotaExceeded = false

    private data class CachedVerdict(val url: String, val title: String, val assignment: TriageAssignment?)

    /** See [OfferedCatalog.reset]. */
    fun seed(known: List<TriageCollection>) = catalog.reset(known)

    /** What is already known about [tab], or null for one never looked at or looked at when it read differently. */
    fun verdict(tab: TriageTab): TriageAssignment? {
        val cached = cache[tab.id] ?: return null
        return cached.assignment.takeIf { cached.url == tab.url && cached.title == tab.title }
    }

    /** [tabs] filtered to what this cache can answer for right now — [triage]'s `precomputed`. */
    fun snapshot(tabs: List<TriageTab>): Map<Int, TriageAssignment?> = buildMap {
        tabs.forEach { tab ->
            val cached = cache[tab.id] ?: return@forEach
            if (cached.url == tab.url && cached.title == tab.title) put(tab.id, cached.assignment)
        }
    }

    /** Work out where [tab] would go, unless this cache already knows — see [verdict]. */
    suspend fun evaluate(tab: TriageTab, sidebarGroups: List<String>) {
        if (quotaExceeded) return
        if (verdict(tab) != null) return
        if (cache[tab.id]?.let { it.url == tab.url && it.title == tab.title } == true) return // already asked, and confidently unsure
        val session = base ?: ai.start(systemPrompt).also { base = it }
        val assignment = try {
            resolveBatch(session, TabBatch(hostOf(tab.url), listOf(tab)), catalog.asked(), sidebarGroups).firstOrNull()
        } catch (e: AiQuotaExceededException) {
            quotaExceeded = true
            null
        } catch (e: Throwable) {
            null
        }
        assignment?.let(catalog::fold)
        cache[tab.id] = CachedVerdict(tab.url, tab.title, assignment)
    }
}

/**
 * [known], with a summary in place of a title dump wherever a collection or section holds more than
 * [SUMMARIZE_ABOVE] cards — see [catalogSummaryPrompt]. Skipped entirely, no session opened at all,
 * when nothing here needs it: most windows of a modest number of collections never pay for this.
 *
 * A session of its own, framed for prose exactly as the old session summary was — [systemPrompt] must
 * not be the triage's own JSON-only framing, or the answer arrives fenced in a code block instead of
 * the phrase it was asked for.
 *
 * One collection or section that fails to summarise (a throw, a reply [cleanSummary] rejects) simply
 * keeps its raw titles — the same "less done, never wrong" a batch answer gets, spelled out here for a
 * description instead of a placement.
 */
suspend fun summarizeCatalog(ai: AiAssistant, systemPrompt: String, known: List<TriageCollection>): List<TriageCollection> {
    val needsAny = known.any { collection ->
        collection.examples.size > SUMMARIZE_ABOVE || collection.sections.any { it.examples.size > SUMMARIZE_ABOVE }
    }
    if (!needsAny) return known
    val base = ai.start(systemPrompt)
    try {
        suspend fun summaryOf(name: String, examples: List<String>): String? {
            if (examples.size <= SUMMARIZE_ABOVE) return null
            return aiCatching {
                base.clone().use { session -> cleanSummary(session.ask(catalogSummaryPrompt(name, examples)).lastOrNull()) }
            }
        }
        return known.map { collection ->
            collection.copy(
                summary = summaryOf(collection.title, collection.examples),
                sections = collection.sections.map { section ->
                    section.copy(summary = summaryOf(section.title, section.examples))
                },
            )
        }
    } finally {
        base.close()
    }
}

/**
 * Run [block] with this session and give it back afterwards, whatever happens — including the user
 * closing the window mid-question, which cancels the coroutine rather than returning through it.
 * `AiSession` is not `AutoCloseable` (its `close` is not the JVM's), so this is the same idea spelled
 * out for it.
 */
private inline fun <T> AiSession.use(block: (AiSession) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
