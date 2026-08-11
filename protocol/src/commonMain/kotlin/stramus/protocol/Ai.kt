package stramus.protocol

import kotlinx.serialization.Serializable

/**
 * One tab, as far as the cloud triage needs to know it — nothing about the user's collections, because
 * the server already has those (see the server's own `AiCatalog.kt`): it is the same account's data,
 * synced there for an entirely different reason, and reading it here is cheaper and more private than
 * asking the browser to describe its whole sidebar on every batch.
 */
@Serializable
data class AiTriageTab(val id: Int, val title: String, val url: String)

/**
 * A collection this run has invented so far but not yet saved anywhere — offered back on the next batch
 * of the *same* run so it is reused instead of invented a second time under a slightly different name.
 * Nothing else travels with it: no examples, because there is nothing saved under an invented name yet.
 *
 * This is the one piece of the catalog the server cannot know on its own — an invented collection is not
 * synced until the plan is applied, which is after the run that invented it has already finished asking.
 */
@Serializable
data class AiInventedCollection(val title: String, val group: String? = null, val sections: List<String> = emptyList())

/**
 * A run's question: some tabs — as many as the caller has left to place, not necessarily as many as the
 * server will actually consider in one call (see [AiTriageResponse.consideredTabIds]) — and whatever this
 * run has invented so far.
 */
@Serializable
data class AiTriageRequest(
    val tabs: List<AiTriageTab>,
    val invented: List<AiInventedCollection> = emptyList(),
)

/**
 * Where one tab is proposed to go — the server's own verdict, already checked against the real catalog
 * and [AiTriageRequest.invented] alike (see `planForBatch` in `core`, which this is built from). A null
 * [collectionId] or [sectionId] means exactly what it means locally: that collection or section does not
 * exist yet, and applying the plan is what would create it.
 */
@Serializable
data class AiTriageAssignment(
    val tabId: Int,
    val collectionTitle: String,
    val collectionId: String?,
    val sectionTitle: String?,
    val sectionId: String?,
    val groupTitle: String?,
)

/**
 * [AiTriageAssignment] for every tab the model placed acceptably — a tab it declined stays out of this
 * list, same as everywhere else in the app.
 *
 * [consideredTabIds] is every tab this call actually asked the model about — a superset of
 * [assignments]' own tab ids, since a decline belongs here too. The gap between what the caller sent
 * ([AiTriageRequest.tabs]) and this is what the catalog cost to describe: read the account's whole
 * collection list, and only what is left of the model's context after that is spent on tabs — so a call
 * asks about as many as fit, not a fixed count agreed in advance. The caller sends whatever [tabId]s are
 * not in here again, in a follow-up call, until nothing is left; a tab a call could not even get to is
 * not the same fact as one it looked at and declined, and only [consideredTabIds] tells them apart.
 */
@Serializable
data class AiTriageResponse(
    val assignments: List<AiTriageAssignment>,
    val consideredTabIds: List<Int> = emptyList(),
)
