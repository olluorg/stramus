package stramus.ui

import stramus.core.ai.TriageCollection
import stramus.core.platform.CapturedTab
import stramus.core.topics.ExistingPlace
import stramus.core.topics.KnownCollection
import stramus.core.topics.KnownSection
import stramus.core.topics.SiteGroup
import stramus.core.topics.TitleTopic
import stramus.core.topics.TitledTab
import stramus.core.topics.matchExisting
import stramus.core.topics.siteGroupFor
import stramus.core.topics.siteTopics
import stramus.core.topics.titleTopics
import kotlin.js.console

/**
 * Persisted once the first-run offer to sort the open tabs into topics has been made — shown, or found to
 * have nothing to show — so it is made on the very first open of a fresh install and never again. The
 * window's own button stays for every later time.
 */
internal const val STARTER_SEEN_PREF = "starterSeen"

/**
 * The topics window, open: over one browser window's tabs, or over every window's ([windowId] null —
 * the first-run offer, which is about everything the user has open). [starter] is that offer, which never
 * closes a tab: a new user's forty tabs vanishing on their first click is not a way to meet anybody.
 */
internal data class TopicsRun(
    val windowId: Int?,
    val starter: Boolean,
    val topics: List<TitleTopic>,
    /**
     * The sidebar group each collection is proposed for, keyed by the collection's own title. Empty where
     * nothing could say — then every new collection goes where new collections go by default.
     */
    val groups: Map<String, String> = emptyMap(),
    /**
     * For a topic that belongs in something the user already keeps: which collection, and which divider
     * inside it. Keyed by the topic's own title. Empty on a first run, where there is nothing to join.
     */
    val places: Map<String, ExistingPlace> = emptyMap(),
)

/**
 * Which of [topics] belong in collections the user already has — see `matchExisting` for what "belong"
 * has to survive.
 *
 * [known] is the catalog the triage window reads for itself (`knownCollections`): every collection a card
 * may be saved into, with a few of its cards quoted. A collection behind a PIN is not in it and so is never
 * matched — the caller leaves those out, and it matters here as much as on screen: a plan may not route
 * somebody's tabs into a collection they have locked.
 */
internal fun existingPlaces(
    topics: List<TitleTopic>,
    tabs: List<CapturedTab>,
    known: List<TriageCollection>,
): Map<String, ExistingPlace> {
    val catalog = known.mapNotNull { collection ->
        collection.id?.let { id ->
            KnownCollection(
                id = id.toString(),
                title = collection.title,
                cardTitles = collection.examples,
                sections = collection.sections.mapNotNull { section ->
                    section.id?.let { KnownSection(it.toString(), section.title, section.examples) }
                },
            )
        }
    }
    if (catalog.isEmpty()) return emptyMap()

    val byId = tabs.associateBy { it.id }
    val found = topics.mapNotNull { topic ->
        val titles = topic.tabIds.mapNotNull { byId[it]?.title }
        matchExisting(topic.title, titles, catalog)?.let { topic.title to it }
    }.toMap()
    found.forEach { (topic, place) ->
        val where = place.sectionTitle?.let { "${place.collectionTitle} / $it" } ?: place.collectionTitle
        console.log("[topics] «$topic» joins what you already have: «$where»")
    }
    return found
}

/** What a [SiteGroup] is called on screen. The shelves are the same everywhere; the words are not. */
internal fun groupNameOf(group: SiteGroup, strings: Strings): String = when (group) {
    SiteGroup.SHOPPING -> strings.topicGroupShopping
    SiteGroup.CODE -> strings.topicGroupCode
    SiteGroup.VIDEO -> strings.topicGroupVideo
    SiteGroup.JOBS -> strings.topicGroupJobs
    SiteGroup.READING -> strings.topicGroupReading
    SiteGroup.DOCS -> strings.topicGroupDocs
}

/**
 * [tabs] gathered into topics — see `titleTopics`. Nothing is asked of the browser beyond the tabs
 * themselves: the topics are in what the pages are called and what their addresses say was searched for,
 * both of which are already in hand.
 *
 * A tab in no topic is simply not here. It is not saved anywhere under a heading of its own either — the
 * window shows it among the unsorted, where the user places it or leaves it. There was a collection for
 * the tabs nobody had opened in a week; it went, because "we could not group these" and "you have not
 * looked at these" are the same heap to the person reading them, and only one of the two was honest about
 * it. Every run logs what it found under "[topics]", which is how this is judged against a real window.
 */
internal fun findTopics(tabs: List<CapturedTab>): List<TitleTopic> {
    val titled = tabs.map { TitledTab(it.id, it.title, it.url) }
    val byWords = titleTopics(titled)
    // What the words could not join, by the site it is on — see [siteTopics]. Last, and after the fact:
    // it only ever picks up what is left, so a site can no longer outbid a topic the way it once did.
    val topics = byWords + siteTopics(titled, byWords.flatMap { it.tabIds }.toSet())
    console.log("[topics] ${tabs.size} tabs → ${topics.size} collections, ${topics.sumOf { it.tabIds.size }} placed")
    topics.forEach { console.log("[topics] «${it.title}» — ${it.tabIds.size} (${it.terms.joinToString(", ")})") }
    return topics
}

/** The sidebar group for each topic, read from where its pages live — see [siteGroupFor]. */
internal fun siteGroups(topics: List<TitleTopic>, tabs: List<CapturedTab>, strings: Strings): Map<String, String> {
    val byId = tabs.associateBy { it.id }
    return topics.mapNotNull { topic ->
        val urls = topic.tabIds.mapNotNull { byId[it]?.url }
        siteGroupFor(urls)?.let { topic.title to groupNameOf(it, strings) }
    }.toMap()
}

