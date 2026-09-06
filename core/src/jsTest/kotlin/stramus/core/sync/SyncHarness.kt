@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import stramus.core.db.StramusStore
import stramus.protocol.DeltaCursor
import stramus.protocol.RowKey
import stramus.protocol.ServerRow
import stramus.protocol.SyncConflict
import stramus.protocol.SyncRequest
import stramus.protocol.SyncResponse
import stramus.protocol.SyncRow
import stramus.protocol.deltaPageOf
import stramus.protocol.isEchoOfThisPush
import stramus.protocol.mergeRow

/*
 * The two devices and the server they share, used by every test in this package that wants a real
 * conversation rather than a scripted one.
 *
 * The point of the server here is that it is not a stand-in: every decision it makes comes from
 * `protocol`, from the same functions the Ktor server calls. A hand-written fake can only ever agree
 * with whoever wrote it, and for a while that was the whole of the coverage on both sides at once.
 */

internal class Device(
    val store: StramusStore,
    val engine: SyncEngine,
    /** The address space this device saves under, so a randomised test knows whose card is whose. */
    val prefix: String = "https://example.org/",
) {
    suspend fun fileCardId(title: String) =
        store.collections.all().flatMap { store.cards.byCollection(it.id) }.first { it.title == title }.id

    suspend fun cardsTitled(title: String): Int =
        store.collections.all().flatMap { store.cards.byCollection(it.id) }.count { it.title == title }

    /** Everything this device holds, as text — for asserting that two of them hold the same thing. */
    suspend fun shape(): String {
        val collections = store.collections.all().sortedBy { it.title }
        val cards = collections.flatMap { store.cards.byCollection(it.id) }
        val groups = collections.flatMap { store.cardSections.byCollection(it.id) }
        return buildString {
            collections.forEach { appendLine("C ${it.id} ${it.title}") }
            groups.sortedBy { it.id.toString() }.forEach { appendLine("G ${it.id} ${it.title} ${it.description}") }
            cards.sortedBy { it.id.toString() }
                .forEach { appendLine("K ${it.id} ${it.cardSectionId} ${it.title} ${it.url} ${it.content}") }
        }
    }
}

/**
 * A server made of the real decisions and nothing else.
 *
 * The rows live in a map instead of SQLite and there is no HTTP in front of it, but every answer it
 * gives comes from `protocol` — [mergeRow] settles each pushed row, [deltaPageOf] cuts the page,
 * [DeltaCursor] names where it stopped, [isEchoOfThisPush] decides what not to send back. Those are the
 * same functions `SyncService` calls, so this cannot quietly disagree with the server the way a
 * hand-written stand-in can.
 */
internal class Server {
    private val rows = mutableMapOf<RowKey, ServerRow>()
    private var rev = 0L

    /** Turned down by a test that wants to walk several pages without a thousand rows to do it with. */
    var pageSize: Int = 500

    /** After this many pages of one run, the network "dies" — see the test about an interrupted run. */
    var failAfterPages: Int? = null
    private var pagesThisRun = 0

    /** Answers to drop outright, one at a time: a request that never comes back. */
    var dropNextAnswers: Int = 0

    /**
     * Answers to do the work for and then lose: the push lands, the device never hears that it did.
     *
     * The realistic half of "the network failed". A device that pushed and got nothing back has to
     * assume nothing happened and send again, and sending a second time what the server already has
     * must be a no-op rather than a second copy — which is the whole reason rows carry ids the client
     * chose rather than ones the server hands out.
     */
    var applyThenFailNext: Int = 0

    /**
     * Break something, at random, and say what was broken so a failure can be read.
     *
     * These are the three shapes a network failure takes here: the answer never arrives, the answer
     * arrives with less in it than it claims, or the run dies partway through a delta it was paging.
     * All three leave work undone; none of them may leave anything *wrong*, which is what the properties
     * check afterwards.
     */
    fun injectFault(): String = when ((counter++) % 3) {
        0 -> { dropNextAnswers += 1; "the next answer is lost on the way there" }
        1 -> { applyThenFailNext += 1; "the next answer is lost on the way back" }
        else -> { failAfterPages = 1; "the run dies after one page" }
    }

    /** Stop breaking things: the devices are about to be left to agree, and they need a network to do it. */
    fun clearFaults() {
        dropNextAnswers = 0
        applyThenFailNext = 0
        failAfterPages = null
    }

    private var counter = 0

    /** Whether the blob store refuses everything: a quota reached, or a network that will not carry it. */
    var refuseBlobs: Boolean = false

    private val blobs = mutableMapOf<String, ByteArray>()

    /** What the conversation cost, for the tests that are about a device going quiet. */
    var pulls: Int = 0
        private set
    var pushedRows: Int = 0
        private set

    /** The largest single push this server was asked to take — see [PUSH_LIMIT] on the client. */
    var biggestPush: Int = 0
        private set

    fun rowsFor(table: String): List<SyncRow> = rows.values.map { it.row }.filter { it.tbl == table }

    /** The files, addressed by the hash of their bytes — the other half of what a device syncs. */
    fun blobsApi(): BlobApi = object : BlobApi {
        override suspend fun missing(shas: List<String>): List<String> =
            if (refuseBlobs) shas else shas.filterNot { it in blobs }

        override suspend fun upload(sha: String, bytes: ByteArray) {
            if (refuseBlobs) error("the account is full")
            blobs[sha] = bytes
        }

        override suspend fun download(sha: String): ByteArray? = if (refuseBlobs) null else blobs[sha]
    }

    fun handle(request: SyncRequest): SyncResponse {
        pulls++
        if (request.cursor == null) pagesThisRun = 0
        pagesThisRun++
        failAfterPages?.let { limit -> if (pagesThisRun > limit) error("the network went away") }
        if (dropNextAnswers > 0) {
            dropNextAnswers--
            error("the answer never came back")
        }
        pushedRows += request.rows.size
        if (request.rows.size > biggestPush) biggestPush = request.rows.size
        val now = Clock.System.now()
        val continuation = request.cursor != null
        val newRev = if (request.rows.isEmpty()) rev else rev + 1

        val accepted = mutableListOf<RowKey>()
        val conflicts = mutableListOf<SyncConflict>()
        request.rows.forEach { incoming ->
            val key = RowKey(incoming.tbl, incoming.id)
            val verdict = mergeRow(incoming, rows[key], request.since, request.deviceId, now)
            verdict.conflict?.let { conflicts += SyncConflict(incoming.tbl, incoming.id, it) }
            verdict.write?.let { winner ->
                rows[key] = ServerRow(winner.copy(rev = newRev), request.deviceId)
                accepted += key
            }
        }
        if (request.rows.isNotEmpty()) rev = newRev

        val cursor = request.cursor?.let(DeltaCursor::parse)
        val delta = rows.values
            .filter { it.row.rev > request.since }
            .filter { cursor == null || DeltaCursor.of(it.row) > cursor }
            .sortedWith(compareBy({ it.row.rev }, { it.row.tbl }, { it.row.id }))
            .filterNot { isEchoOfThisPush(it, newRev, request.deviceId, accepted.toSet(), continuation) }
            .map { it.row }
        val page = deltaPageOf(delta.take(pageSize + 1), pageSize)

        // Everything above has happened; the device is simply not going to hear about it.
        //
        // A page that arrives *short* while still claiming to be complete is not among the faults here,
        // and the reason is worth writing down: a client cannot defend against it. `hasMore` is the only
        // thing that says whether to ask again, so a server that gets it wrong loses the device's rows in
        // silence and for good. That is not a hazard to be tolerated, it is a bug to not have — which is
        // what putting the paging decision in `protocol`, called by both sides, is for.
        if (applyThenFailNext > 0) {
            applyThenFailNext--
            error("the answer was lost on the way back")
        }

        return SyncResponse(
            rev = newRev,
            accepted = accepted,
            rows = page.rows,
            conflicts = conflicts,
            hasMore = page.hasMore,
            nextCursor = page.nextCursor,
        )
    }
}
