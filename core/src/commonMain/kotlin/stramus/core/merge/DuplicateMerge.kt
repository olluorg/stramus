@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.merge

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.url.normalizeUrl

/**
 * Finding the things that are the same thing, after two copies of one tree have been poured into one.
 *
 * Signing a browser that already holds collections into an account that also holds them merges them by
 * row id, and by row id alone — which is the only thing a synchronising server can honestly do, since
 * ids are all it understands. Two devices that each built a section called "Работа" built two sections,
 * and the account ends up holding both. Nothing is lost, but nothing is joined either: the sidebar
 * grows a second of everything.
 *
 * This is the other half — matching by what the rows *say*, which only the client can do. It is
 * deliberately the same matching the import already does (see `Import.kt`, which folds a bookmarks file
 * into existing collections by title and skips links whose address is already there): the app has one
 * idea of when two saved things are one saved thing, and this is it, applied to a whole tree instead of
 * to an incoming file.
 *
 * Everything here is a pure function over the four models. It builds a [MergePlan] and writes nothing —
 * carrying the plan out belongs to the store, and what the user sees first is the plan itself.
 */

/** Trim, fold the inner whitespace, drop the case: what makes “ Работа” and “работа” one name. */
fun mergeKeyOf(title: String): String = title.trim().split(WHITESPACE).joinToString(" ").lowercase()

private val WHITESPACE = Regex("\\s+")

/**
 * One set of rows that are one row, and which of them survives.
 *
 * [winner] keeps its id — so every card, every collection, every reference to it stays valid, and the
 * other devices are told about a handful of moves rather than a rebuild. [losers] are re-parented out of
 * and then deleted.
 */
data class Fusion(
    val winner: Uuid,
    val losers: List<Uuid>,
    /** The winner's title, for the preview. */
    val title: String,
) {
    val ids: List<Uuid> get() = listOf(winner) + losers
    val fuses: Boolean get() = losers.isNotEmpty()
}

/**
 * What happens inside one collection — or one set of collections that are one collection.
 *
 * [cards] are the duplicates *within* the merged collection: with two "Kormium" collections joined, the
 * page saved on both machines is one page, and would otherwise sit in the result twice. Fusing the
 * collections without this would only move the duplication one level down.
 */
data class CollectionPlan(
    val collection: Fusion,
    val cardSections: List<Fusion>,
    val cards: List<Fusion>,
) {
    /** Nothing to do here — neither the collections nor anything in them turned out to be doubled. */
    val empty: Boolean get() = !collection.fuses && cardSections.none { it.fuses } && cards.none { it.fuses }
    val duplicateCards: Int get() = cards.sumOf { it.losers.size }
}

/**
 * What happens inside one section. [section] may fuse nothing — two collections of the *same* section
 * can be duplicates of each other, which is what a repeated import leaves behind.
 */
data class SectionPlan(
    val section: Fusion,
    val collections: List<CollectionPlan>,
) {
    val empty: Boolean get() = !section.fuses && collections.all { it.empty }
}

/**
 * Why something was left out, so the preview can say so rather than quietly showing less.
 *
 * A locked section is not merely skipped but *unread*: naming its collections in a list of what will be
 * joined would hand over exactly what the PIN is there to keep off the screen.
 */
data class MergeSkipped(
    val lockedSections: Int,
    val readOnlyCollections: Int,
)

data class MergePlan(
    val sections: List<SectionPlan>,
    val skipped: MergeSkipped,
) {
    val empty: Boolean get() = sections.isEmpty()
}

/**
 * Work out what is doubled.
 *
 * The match runs top down, and always *within an already matched parent*: sections against every
 * section, then collections against the collections of one merged section, then card sections and cards
 * against the contents of one merged collection. That order is the whole safety of it — “Работа” inside
 * “ХОББИ” and “Работа” inside “РАБОТА” are two different collections, and no key should ever bring them
 * together.
 *
 * Left alone, always:
 *  - **locked sections**, entirely, and their collections and cards with them;
 *  - **read-only collections**, which are saved sessions: two of them are two sessions, not a mistake;
 *  - **notes whose bodies differ**, even under one title — a title can be typed again, a paragraph cannot;
 *  - **files whose bytes differ**, however they are named.
 */
fun planMerge(
    sections: List<Section>,
    collections: List<Collection>,
    cardSections: List<CardSection>,
    cards: List<Card>,
): MergePlan {
    val open = sections.filter { !it.locked }
    val openIds = open.mapTo(mutableSetOf()) { it.id }

    val liveCollections = collections.filter { it.sectionId in openIds }
    val (mergeable, readOnly) = liveCollections.partition { !it.readOnly }

    val collectionsBySection = mergeable.groupBy { it.sectionId }
    val cardSectionsByCollection = cardSections.groupBy { it.collectionId }
    val cardsByCollection = cards.groupBy { it.collectionId }

    val plans = groupSections(open).map { group ->
        // The collections of every section of the group are now the collections of one section.
        val theirs = group.ids.flatMap { collectionsBySection[it].orEmpty() }
        SectionPlan(
            section = group,
            collections = groupByTitle(theirs, { it.id }, { it.title }, ::pickCollection)
                .map { fusion -> planCollection(fusion, cardSectionsByCollection, cardsByCollection) }
                .filterNot { it.empty },
        )
    }

    return MergePlan(
        sections = plans.filterNot { it.empty },
        skipped = MergeSkipped(sections.size - open.size, readOnly.size),
    )
}

/**
 * The plan with the parts the user unticked taken out of it, ready to be carried out.
 *
 * [excluded] holds the *winner* ids of the rows whose row in the preview is unticked — the winner names
 * the fusion, and it is the only id of it the preview ever shows.
 *
 * Unticking a section takes its collections with it. They were matched against each other *across* the
 * sections being joined, and that match is not something that survives the sections staying apart: two
 * "Kormium" in two sections that remain two sections are two collections, exactly as they were.
 */
fun MergePlan.keeping(excluded: Set<Uuid>): MergePlan {
    if (excluded.isEmpty()) return this
    return copy(
        sections = sections
            .filterNot { it.section.winner in excluded }
            .map { plan -> plan.copy(collections = plan.collections.filterNot { it.collection.winner in excluded }) }
            .filterNot { it.empty },
    )
}

private fun planCollection(
    collection: Fusion,
    cardSectionsByCollection: Map<Uuid, List<CardSection>>,
    cardsByCollection: Map<Uuid, List<Card>>,
): CollectionPlan {
    val theirSections = collection.ids.flatMap { cardSectionsByCollection[it].orEmpty() }
    val theirCards = collection.ids.flatMap { cardsByCollection[it].orEmpty() }
    return CollectionPlan(
        collection = collection,
        cardSections = groupByTitle(theirSections, { it.id }, { it.title }) { a, _ -> a }
            .filter { it.fuses },
        cards = groupCards(theirCards).filter { it.fuses },
    )
}

/**
 * Sections by name — plus the one match a name cannot make.
 *
 * There is exactly one non-deletable section per database: the one a first install is given, named in
 * whatever language that install was opened in. Two devices started in different languages hold “Главная”
 * and “Main”, which are the same section under two names and would never meet by title. They are matched
 * by being the default, and the rest by what they are called.
 */
private fun groupSections(sections: List<Section>): List<Fusion> {
    val (defaults, named) = sections.partition { it.seeded }
    val fusions = mutableListOf<Fusion>()
    if (defaults.isNotEmpty()) {
        val winner = defaults.first()
        fusions += Fusion(winner.id, defaults.drop(1).map { it.id }, winner.title)
    }
    fusions += groupByTitle(named, { it.id }, { it.title }) { a, _ -> a }
    return fusions
}

/**
 * Rows of one parent, gathered by [mergeKeyOf] of their title, in the order they were given.
 *
 * [prefer] picks the winner of a group and is applied left to right — the store's own order (order key,
 * then id) comes in, so with no preference at all the first one wins and the answer is stable.
 */
private fun <T> groupByTitle(
    rows: List<T>,
    id: (T) -> Uuid,
    title: (T) -> String,
    prefer: (T, T) -> T,
): List<Fusion> = rows
    .groupBy { mergeKeyOf(title(it)) }
    .values
    .map { group ->
        val winner = group.reduce(prefer)
        Fusion(id(winner), group.filter { id(it) != id(winner) }.map(id), title(winner))
    }

/**
 * A collection keeps the older of the two: it is the one the user has had longer, and the one the other
 * device's copy was made in the image of. Everything else about it — the icon, the colour, the name's
 * capitalisation — is settled when the plan is carried out, by the same last-write-wins rule the sync
 * uses, and not here.
 */
private fun pickCollection(a: Collection, b: Collection): Collection = if (a.createdAt <= b.createdAt) a else b

/**
 * Cards of one merged collection, by what makes two cards one card.
 *
 * A link is its address, normalised (see [normalizeUrl]) — the same key the search box, the tab list and
 * the import all recognise a page by, so a page saved from a tab on one machine and from history on the
 * other is one page here too.
 *
 * A file is the hash of its bytes. Two files with one name and different contents are two files, and a
 * file whose bytes this device has not got yet ([Card.blobSha] null) is not matched at all: there is
 * nothing to compare it by, and a name is not enough to delete something over.
 *
 * A note is its title *and* its body, both. Two notes under one title with different text are two notes —
 * the loser of such a pair would take a paragraph of writing with it, and no amount of tidiness is worth
 * that. (The sync makes the same judgement from the other side: a note that loses a conflict is kept as a
 * copy rather than overwritten.)
 */
private fun groupCards(cards: List<Card>): List<Fusion> = cards
    .groupBy { card ->
        when (card.kind) {
            CardKind.LINK -> normalizeUrl(card.url).takeIf { it.isNotBlank() }?.let { "link:$it" }
            CardKind.FILE -> card.blobSha?.let { "file:$it" }
            CardKind.NOTE -> "note:${mergeKeyOf(card.title)} ${card.content.orEmpty()}"
        }
    }
    // A null key is a card with nothing to be recognised by — a link with no address, a file whose bytes
    // have not arrived. Each stays itself.
    .filterKeys { it != null }
    .values
    .map { group ->
        val winner = group.reduce(::pickCard)
        Fusion(winner.id, group.filter { it.id != winner.id }.map { it.id }, winner.title)
    }

/**
 * Which of two identical cards to keep: the one that has more to show for itself, then the older.
 *
 * "More to show" is the icon and the preview picture. They are fetched, not typed, and a card that has
 * them draws properly the moment the merge is done, where the other would sit blank until something went
 * and looked them up again.
 */
private fun pickCard(a: Card, b: Card): Card {
    val richer = a.dressing.compareTo(b.dressing)
    return when {
        richer > 0 -> a
        richer < 0 -> b
        else -> if (a.createdAt <= b.createdAt) a else b
    }
}

private val Card.dressing: Int get() = (if (favicon != null) 1 else 0) + (if (thumb != null) 1 else 0)
