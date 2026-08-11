@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import stramus.core.ai.TriageCollection
import stramus.core.ai.TriageSection

private val json = Json { ignoreUnknownKeys = true }

/** How many of a collection's — and, within it, a section's — cards are quoted as what it holds. */
private const val EXAMPLES_READ = 20

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.uuidOrNull(key: String): Uuid? = str(key)?.let { runCatching { Uuid.parse(it) }.getOrNull() }

/** What a cloud triage prompt is built against: this account's own collections, and the sidebar groups they live in. */
data class AiCatalog(val collections: List<TriageCollection>, val groups: List<String>)

/**
 * Rebuilds [AiCatalog] straight from [SyncRows] — the same rows every device already pushes here for an
 * entirely different reason (sync), read back rather than sent again by the client on every triage batch.
 * This is the one place the server reads *into* a row's payload rather than passing it through untouched
 * (see the class doc on `AiTriageRequest`), and it is narrow on purpose: exactly the fields `core`'s
 * `TabTriage.kt` prompt-builders need, nothing else.
 *
 * Two things are always left out, neither of them a filter the caller can turn off:
 * - A PIN-locked sidebar section, and every collection under it. The browser can ask "is this section
 *   unlocked *this visit*"; the server has no such notion at all, so the only safe reading is "always
 *   locked" — a PIN-protected collection's contents must never reach the cloud model.
 * - A read-only collection (one shared *to* this account). Nothing can be filed into it, so offering it as
 *   a destination would only ever be a wrong answer — the same reasoning `App.kt`'s `triageTargets` already
 *   applies for the local, in-browser plan.
 */
class AiCatalogService(private val db: SuspendDatabase<ServerDb>) {

    suspend fun catalogFor(userId: Uuid): AiCatalog {
        val rows = db.suspendAutocommit {
            SyncRows.find { where { SyncRows.userId eq userId } }
        }

        data class SectionRow(val title: String, val orderKey: String, val pinned: Boolean)
        val sectionRows = mutableMapOf<Uuid, SectionRow>()
        data class CollRow(val id: Uuid, val sectionId: Uuid?, val title: String, val readOnly: Boolean, val orderKey: String)
        data class CardSectionRow(val id: Uuid, val collectionId: Uuid?, val title: String, val orderKey: String)
        data class CardRow(val collectionId: Uuid?, val cardSectionId: Uuid?, val title: String, val orderKey: String)
        val collRows = mutableListOf<CollRow>()
        val cardSectionRows = mutableListOf<CardSectionRow>()
        val cardRows = mutableListOf<CardRow>()

        for (row in rows) {
            if (row.deletedAt != null) continue
            val payload = row.payload?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: continue
            val id = runCatching { Uuid.parse(row.id) }.getOrNull() ?: continue
            when (row.tbl) {
                "sections" -> sectionRows[id] = SectionRow(
                    title = payload.str("title") ?: continue,
                    orderKey = payload.str("orderKey") ?: "",
                    pinned = !payload.str("pinHash").isNullOrBlank(),
                )
                "collections" -> collRows += CollRow(
                    id = id,
                    sectionId = payload.uuidOrNull("sectionId"),
                    title = payload.str("title") ?: continue,
                    readOnly = payload.str("readOnly") == "1",
                    orderKey = payload.str("orderKey") ?: "",
                )
                "card_sections" -> cardSectionRows += CardSectionRow(
                    id = id,
                    collectionId = payload.uuidOrNull("collectionId"),
                    title = payload.str("title") ?: continue,
                    orderKey = payload.str("orderKey") ?: "",
                )
                "cards" -> {
                    if (payload.str("kind") != "link") continue
                    val title = payload.str("title")?.takeIf { it.isNotBlank() } ?: continue
                    cardRows += CardRow(
                        collectionId = payload.uuidOrNull("collectionId"),
                        cardSectionId = payload.uuidOrNull("cardSectionId"),
                        title = title,
                        orderKey = payload.str("orderKey") ?: "",
                    )
                }
            }
        }

        val cardsByCollection = cardRows.groupBy { it.collectionId }
        val cardsByCardSection = cardRows.groupBy { it.cardSectionId }

        // Sorted the way the sidebar is: [appendCollections] cuts the list off once it runs past its
        // character budget, and an account with enough collections to hit that budget hits it every
        // single batch — so whichever order this list is in *is* the model's whole picture of the
        // account, silently, forever. Unsorted (the raw order [SyncRows.find] happens to return) that
        // picture was arbitrary and the same arbitrary slice every time, regardless of which tabs were
        // actually being asked about — the likely reason one real run kept filing unrelated tabs into
        // whichever handful of collections survived the cut.
        val collections = collRows
            .filter { !it.readOnly && sectionRows[it.sectionId]?.pinned != true }
            .sortedBy { it.orderKey }
            .map { coll ->
                val sections = cardSectionRows
                    .filter { it.collectionId == coll.id }
                    .sortedBy { it.orderKey }
                    .map { cs ->
                        val examples = cardsByCardSection[cs.id].orEmpty().sortedBy { it.orderKey }.take(EXAMPLES_READ).map { it.title }
                        TriageSection(cs.id, cs.title, examples = examples)
                    }
                TriageCollection(
                    id = coll.id,
                    title = coll.title,
                    inSection = coll.sectionId?.let { sectionRows[it]?.title },
                    sections = sections,
                    examples = cardsByCollection[coll.id].orEmpty().sortedBy { it.orderKey }.take(EXAMPLES_READ).map { it.title },
                )
            }

        val groups = sectionRows.values.filterNot { it.pinned }.sortedBy { it.orderKey }.map { it.title }

        return AiCatalog(collections, groups)
    }
}
