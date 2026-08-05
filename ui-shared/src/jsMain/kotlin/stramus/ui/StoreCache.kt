@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlin.js.JSON
import kotlin.js.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section

/**
 * What the sidebar, and a handful of collections, looked like the last time this browser closed — read
 * once, before [openStramusStore] has even started opening the WASM database, so the page has something
 * to paint on the very first render instead of an empty shell, and stays able to paint a real answer for
 * a few renders after that if the user switches collections before the database is open.
 *
 * It is overwritten continuously (see the two `useEffect`s in `App.kt` that call [recordCollectionVisit]
 * and [writePaintCache]) and is never the source of truth for anything: every field here is replaced by
 * the real read within the first render or two, the moment the store is open and has answered. A stale
 * or corrupt cache is simply not used — [readPaintCache] returns null rather than throw, same as
 * [prefGet].
 *
 * [content] holds full card data for only a few collections at a time — see [hotSet] — chosen from
 * [visits], which costs almost nothing to keep one entry per collection ever opened.
 */
internal data class PaintCache(
    val sections: List<Section>,
    val collections: List<Collection>,
    val selectedId: Uuid?,
    val visits: List<CollectionVisit>,
    val content: Map<Uuid, CollectionContent>,
)

/** How often, and how recently, a collection was opened — what [hotSet] picks the cached few from. */
internal data class CollectionVisit(val collectionId: Uuid, val lastOpenedAt: Instant, val openCount: Int)

/** One collection's cards and card sections, as cached — see [PaintCache.content]. */
internal data class CollectionContent(val cards: List<Card>, val cardSections: List<CardSection>)

private const val CACHE_KEY = "stramus.paintCache"

/** Bumped when the shape below changes; an older or newer cache is discarded rather than misread. */
private const val CACHE_FORMAT = 2

/** How many collections' worth of cards are kept cached — see [hotSet]. */
private const val HOT_SET_SIZE = 5

/** How many collections' visit history is kept at all — a row is just an id, a count and a timestamp. */
private const val MAX_VISITS_TRACKED = 50

/**
 * Which collections are worth having their cards cached right now: the [HOT_SET_SIZE] most recently
 * opened, together with the [HOT_SET_SIZE] with the highest [frecency] — never more than twice that
 * many, usually quite a bit fewer, since a collection opened often is generally also one opened recently.
 *
 * Frecency, not a raw open count, for the second half: a collection lived in daily for a month last year
 * and never since should not permanently outrank one opened every day this week — see `Usage.kt`, which
 * ranks the search box's top sites by the same idea, applied here to collections instead of pages.
 *
 * This is what makes the content cache an *evicting* one: every write recomputes it from the latest
 * visits and keeps only entries still in it (see [writePaintCache]), so a collection that falls out of
 * regular use eventually falls out of the cache too, rather than the cache growing by one collection
 * forever.
 */
private fun hotSet(visits: List<CollectionVisit>): Set<Uuid> {
    val byRecency = visits.sortedByDescending { it.lastOpenedAt }.take(HOT_SET_SIZE).map { it.collectionId }
    val byFrequency = visits.sortedByDescending { frecency(it.openCount, it.lastOpenedAt) }
        .take(HOT_SET_SIZE)
        .map { it.collectionId }
    return (byRecency + byFrequency).toSet()
}

/** Collections whose section is currently locked — their content never touches the cache. */
private fun lockedCollectionIds(sections: List<Section>, collections: List<Collection>): Set<Uuid> {
    val lockedSectionIds = sections.filter { it.locked }.map { it.id }.toSet()
    return collections.filter { it.sectionId in lockedSectionIds }.map { it.id }.toSet()
}

/**
 * Record that [collectionId] was opened, just now — read-modify-write on the visit history alone, so
 * this can run on every collection switch without touching (or waiting on) the much larger content
 * cache. [writePaintCache] is what turns an updated visit into an updated [hotSet].
 */
internal fun recordCollectionVisit(collectionId: Uuid) {
    val previous = readRaw()
    val now = Clock.System.now()
    val already = previous?.visits.orEmpty()
    val updated = already.filter { it.collectionId != collectionId } +
        CollectionVisit(collectionId, now, (already.firstOrNull { it.collectionId == collectionId }?.openCount ?: 0) + 1)
    val trimmed = updated.sortedByDescending { it.lastOpenedAt }.take(MAX_VISITS_TRACKED)
    persist((previous ?: emptyRaw()).copy(visits = trimmed))
}

/**
 * Write the sidebar and the currently open collection's content into the cache — merged with whatever
 * was already cached for *other* collections, not replacing it, so switching between a few collections
 * in one session leaves all of them painted from real data on the next open, not just the last one.
 *
 * Cards and card sections of a collection whose section is locked are never written — nor kept, if they
 * are already there from before the section was locked. A locked section's cards are never read out of
 * the database at all (see `Store.kt`'s `KormiumSectionRepository`); this is the same rule applied to
 * the one other place they could otherwise end up sitting in the clear.
 */
internal fun writePaintCache(
    sections: List<Section>,
    collections: List<Collection>,
    selectedId: Uuid?,
    cards: List<Card>,
    cardSections: List<CardSection>,
) {
    val previous = readRaw()
    val visits = previous?.visits.orEmpty()
    val hot = hotSet(visits)
    val locked = lockedCollectionIds(sections, collections)

    val merged = previous?.content.orEmpty() +
        if (selectedId != null && selectedId !in locked) {
            mapOf(selectedId to CollectionContent(cards, cardSections))
        } else {
            emptyMap()
        }
    val kept = merged.filterKeys { it in hot && it !in locked }

    persist(PaintCache(sections, collections, selectedId, visits, kept))
}

/**
 * Read the cache back, re-applying the same lock check as [writePaintCache]: a section PIN-protected
 * *after* the cache was last written, by a session that has since closed, must not paint that
 * collection's cards for the instant before the real store says otherwise.
 */
internal fun readPaintCache(): PaintCache? {
    val cache = readRaw() ?: return null
    val locked = lockedCollectionIds(cache.sections, cache.collections)
    return if (locked.isEmpty()) cache else cache.copy(content = cache.content.filterKeys { it !in locked })
}

private fun emptyRaw() = PaintCache(emptyList(), emptyList(), null, emptyList(), emptyMap())

private fun persist(cache: PaintCache) {
    prefSet(
        CACHE_KEY,
        JSON.stringify(
            json(
                "v" to CACHE_FORMAT,
                "sections" to cache.sections.map { it.encode() }.toTypedArray(),
                "collections" to cache.collections.map { it.encode() }.toTypedArray(),
                "selectedId" to cache.selectedId?.toString(),
                "visits" to cache.visits.map { it.encode() }.toTypedArray(),
                "content" to cache.content.entries
                    .map { (id, c) -> json("id" to id.toString(), "content" to c.encode()) }
                    .toTypedArray(),
            ),
        ),
    )
}

/** The cache exactly as stored, with no lock check applied — for [recordCollectionVisit] and
 * [writePaintCache] to merge against; only [readPaintCache] (what the UI actually paints from) sanitizes. */
private fun readRaw(): PaintCache? = runCatching {
    val text = prefGet(CACHE_KEY) ?: return null
    val d = JSON.parse<dynamic>(text)
    if (d.v as? Int != CACHE_FORMAT) return null

    PaintCache(
        sections = (d.sections as Array<dynamic>).map { decodeSection(it) },
        collections = (d.collections as Array<dynamic>).map { decodeCollection(it) },
        selectedId = (d.selectedId as String?)?.let { Uuid.parse(it) },
        visits = (d.visits as Array<dynamic>).map { decodeVisit(it) },
        content = (d.content as Array<dynamic>).associate { Uuid.parse(it.id as String) to decodeContent(it.content) },
    )
}.getOrNull()

// ---- one field's worth of JSON at a time — no library, the shapes are small and fixed ----

private fun CollectionVisit.encode() = json(
    "collectionId" to collectionId.toString(), "lastOpenedAt" to lastOpenedAt.toString(), "openCount" to openCount,
)

private fun decodeVisit(d: dynamic): CollectionVisit = CollectionVisit(
    collectionId = Uuid.parse(d.collectionId as String),
    lastOpenedAt = Instant.parse(d.lastOpenedAt as String),
    openCount = d.openCount as Int,
)

private fun CollectionContent.encode() = json(
    "cards" to cards.map { it.encode() }.toTypedArray(),
    "cardSections" to cardSections.map { it.encode() }.toTypedArray(),
)

private fun decodeContent(d: dynamic): CollectionContent = CollectionContent(
    cards = (d.cards as Array<dynamic>).map { decodeCard(it) },
    cardSections = (d.cardSections as Array<dynamic>).map { decodeCardSection(it) },
)

private fun Section.encode() = json(
    "id" to id.toString(), "title" to title, "orderKey" to orderKey,
    "deletable" to deletable, "collapsed" to collapsed, "locked" to locked,
)

private fun decodeSection(d: dynamic): Section = Section(
    id = Uuid.parse(d.id as String),
    title = d.title as String,
    orderKey = d.orderKey as String,
    deletable = d.deletable as Boolean,
    collapsed = d.collapsed as Boolean,
    locked = d.locked as Boolean,
)

private fun Collection.encode() = json(
    "id" to id.toString(), "sectionId" to sectionId.toString(), "title" to title,
    "orderKey" to orderKey, "createdAt" to createdAt.toString(), "readOnly" to readOnly,
)

private fun decodeCollection(d: dynamic): Collection = Collection(
    id = Uuid.parse(d.id as String),
    sectionId = Uuid.parse(d.sectionId as String),
    title = d.title as String,
    orderKey = d.orderKey as String,
    createdAt = Instant.parse(d.createdAt as String),
    readOnly = d.readOnly as Boolean,
)

private fun CardSection.encode() = json(
    "id" to id.toString(), "collectionId" to collectionId.toString(), "title" to title,
    "description" to description, "orderKey" to orderKey, "collapsed" to collapsed,
)

private fun decodeCardSection(d: dynamic): CardSection = CardSection(
    id = Uuid.parse(d.id as String),
    collectionId = Uuid.parse(d.collectionId as String),
    title = d.title as String,
    description = d.description as String?,
    orderKey = d.orderKey as String,
    collapsed = d.collapsed as Boolean,
)

private fun Card.encode() = json(
    "id" to id.toString(), "collectionId" to collectionId.toString(),
    "cardSectionId" to cardSectionId?.toString(), "kind" to kind.id, "title" to title, "url" to url,
    "favicon" to favicon, "content" to content, "thumb" to thumb, "mime" to mime, "blobSha" to blobSha,
    "orderKey" to orderKey, "createdAt" to createdAt.toString(),
)

private fun decodeCard(d: dynamic): Card = Card(
    id = Uuid.parse(d.id as String),
    collectionId = Uuid.parse(d.collectionId as String),
    cardSectionId = (d.cardSectionId as String?)?.let { Uuid.parse(it) },
    kind = CardKind.from(d.kind as String?),
    title = d.title as String,
    url = d.url as String,
    favicon = d.favicon as String?,
    content = d.content as String?,
    thumb = d.thumb as String?,
    mime = d.mime as String?,
    blobSha = d.blobSha as String?,
    orderKey = d.orderKey as String,
    createdAt = Instant.parse(d.createdAt as String),
)
