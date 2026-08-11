@file:OptIn(ExperimentalUuidApi::class)

package stramus.server

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import stramus.protocol.SyncRow

/**
 * [AiCatalogService], exercised the way a real account's sync rows arrive: pushed in whatever order the
 * client happened to send them in, never the order the sidebar shows them in.
 */
class AiCatalogTest {

    private val user = Uuid.random()

    @Test
    fun `collections come back sorted by orderKey, not by sync-row order`() = runTest {
        val catalog = newCatalog()
        // Pushed with "z" (last in the sidebar) first and "a" (first in the sidebar) last — sync-row
        // order and sidebar order deliberately disagree, which is the ordinary case: a push batches
        // whatever changed, not a walk of the sidebar from top to bottom.
        push(
            catalog.sync,
            user,
            collection(orderKey = "z", title = "Z last in sidebar"),
            collection(orderKey = "a", title = "A first in sidebar"),
            collection(orderKey = "m", title = "M middle"),
        )

        val result = catalog.service.catalogFor(user)

        // The prompt this feeds is budget-truncated ([COLLECTIONS_BUDGET] in `TabTriage.kt`) — an account
        // with enough collections to hit it shows the model whatever survives the cut, every batch. Sync-
        // row order made that an arbitrary, unrelated-to-the-account's-own-arrangement slice; sidebar
        // order at least means the truncation drops the same thing a person scrolling the sidebar would
        // reach last.
        assertEquals(listOf("A first in sidebar", "M middle", "Z last in sidebar"), result.collections.map { it.title })
    }

    @Test
    fun `a PIN-locked section's collections, and a read-only collection, are left out entirely`() = runTest {
        val catalog = newCatalog()
        val lockedSectionId = Uuid.random()
        val openSectionId = Uuid.random()
        push(
            catalog.sync,
            user,
            section(lockedSectionId, "Личное", orderKey = "a", pinHash = "hash"),
            section(openSectionId, "Работа", orderKey = "b", pinHash = null),
            collection(orderKey = "a", title = "За PIN-ом", sectionId = lockedSectionId),
            collection(orderKey = "b", title = "Общая, только для чтения", sectionId = openSectionId, readOnly = true),
            collection(orderKey = "c", title = "Обычная", sectionId = openSectionId),
        )

        val result = catalog.service.catalogFor(user)

        assertEquals(listOf("Обычная"), result.collections.map { it.title })
        // The locked section itself is not offered as a place a new collection could go, either — the
        // server has no notion of "unlocked this visit" the way the browser does.
        assertEquals(listOf("Работа"), result.groups)
    }
}

// ---- helpers ---------------------------------------------------------------------------------------

private class Catalog(val sync: SyncService, val service: AiCatalogService)

private fun newCatalog(): Catalog {
    val config = ServerConfig(databasePath = createTempDirectory("ai-catalog-test").resolve("s.db").toString())
    val db = openServerDatabase(config)
    return Catalog(SyncService(db), AiCatalogService(db))
}

private suspend fun push(sync: SyncService, userId: Uuid, vararg rows: SyncRow) {
    sync.sync(userId, Uuid.random(), since = 0, pushed = rows.toList())
}

private fun section(id: Uuid = Uuid.random(), title: String, orderKey: String, pinHash: String?) = SyncRow(
    tbl = "sections", id = id.toString(), updatedAt = "2026-07-14T12:00:00Z",
    payload = obj(
        "title" to title, "orderKey" to orderKey, "deletable" to "1", "collapsed" to "0",
        "pinSalt" to (pinHash?.let { "salt" }), "pinHash" to pinHash,
    ),
)

private fun collection(orderKey: String, title: String, sectionId: Uuid = Uuid.random(), readOnly: Boolean = false) = SyncRow(
    tbl = "collections", id = Uuid.random().toString(), updatedAt = "2026-07-14T12:00:00Z",
    payload = obj(
        "sectionId" to sectionId.toString(), "title" to title, "orderKey" to orderKey,
        "createdAt" to "2026-07-14T12:00:00Z", "readOnly" to if (readOnly) "1" else "0",
    ),
)

private fun obj(vararg pairs: Pair<String, String?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to (v?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?)) })
