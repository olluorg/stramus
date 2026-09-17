package stramus.core.topics

/*
 * Putting a topic where the user already keeps that sort of thing.
 *
 * Everything else in this package is about a browser full of tabs and an app with nothing in it — the first
 * run. The second run is a different question, and until now it was answered badly: the plan always
 * proposed new collections, matching an existing one only when the titles were the same word for word. So
 * a person with a collection called "Лада Калина" was offered "Калина замена топливной трубки" beside it,
 * and their sidebar grew a second one of everything they already had.
 *
 * The match is made on the same evidence the grouping itself uses — the words. A collection is what it
 * holds, so its cards' titles are read exactly as a topic's tabs are, and the two are compared by the rare
 * words they share. It is deliberately hard to satisfy: merging into the wrong collection is a mess the
 * user has to undo by hand, while failing to merge costs them one extra collection they can drag. Less
 * done, never wrong — the same trade as everywhere else here.
 *
 * And it is what finally gives the third level. A collection the user has divided into sections is offered
 * back with its sections, and a topic joining it lands under the one whose cards it reads like. Sections
 * this code *invents* remain what they were: nothing. The user's own dividers are a fact; guessing at new
 * ones was tried, and produced a section named after the collection it was inside.
 */

/** A section inside an existing collection, as the matcher reads it: its name and what is under it. */
data class KnownSection(val id: String, val title: String, val cardTitles: List<String> = emptyList())

/**
 * A collection that already exists. [id] is opaque here — whatever the caller uses to name a collection,
 * handed back untouched in [ExistingPlace].
 */
data class KnownCollection(
    val id: String,
    val title: String,
    val cardTitles: List<String> = emptyList(),
    val sections: List<KnownSection> = emptyList(),
)

/** Where a topic belongs among what the user already has. */
data class ExistingPlace(
    val collectionId: String,
    val collectionTitle: String,
    val sectionId: String?,
    val sectionTitle: String?,
)

/**
 * The fewest words a topic and a collection must share before one is said to belong in the other. Two is
 * the floor everywhere in this package, and for the same reason: one shared word is a coincidence, and
 * here a coincidence moves somebody's tabs into a collection they did not choose.
 */
private const val MIN_SHARED = 2

/** And they must be this much of what the topic is about — two words in common out of forty is nothing. */
private const val MIN_SHARE = 0.25

/** A section is a smaller thing and a cheaper mistake: one telling word under the right collection will do. */
private const val MIN_SHARED_IN_SECTION = 1

/**
 * Where [topicTitle] and its [tabTitles] belong among [known], or null to make a new collection.
 *
 * The topic's words are matched against each collection's own — its name and the titles of the cards in it
 * — and the best is taken if it clears [MIN_SHARED] shared words and [MIN_SHARE] of the topic's own. Within
 * that collection, the section whose cards share the most is offered too, and none if none share anything.
 */
fun matchExisting(
    topicTitle: String,
    tabTitles: List<String>,
    known: List<KnownCollection>,
): ExistingPlace? {
    val topicWords = wordsIn(listOf(topicTitle) + tabTitles)
    if (topicWords.isEmpty()) return null

    val best = known
        .map { collection -> collection to shared(topicWords, wordsIn(listOf(collection.title) + collection.cardTitles)) }
        .filter { (_, shared) -> shared.size >= MIN_SHARED && shared.size.toDouble() / topicWords.size >= MIN_SHARE }
        .maxByOrNull { (_, shared) -> shared.size }
        ?.first
        ?: return null

    val section = best.sections
        .map { section -> section to shared(topicWords, wordsIn(listOf(section.title) + section.cardTitles)) }
        .filter { (_, shared) -> shared.size >= MIN_SHARED_IN_SECTION }
        .maxByOrNull { (_, shared) -> shared.size }
        ?.first

    return ExistingPlace(
        collectionId = best.id,
        collectionTitle = best.title,
        sectionId = section?.id,
        sectionTitle = section?.title,
    )
}

/** The stems worth comparing in [texts] — the same reading a tab's title gets, minus the site's own name. */
private fun wordsIn(texts: List<String>): Set<String> =
    texts.flatMap { keepRuns(it, host = "").flatten() }
        .map { stem(it.lowercase()) }
        .toSet()

private fun shared(one: Set<String>, other: Set<String>): Set<String> = one intersect other
