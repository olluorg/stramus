@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.Schema
import io.github.kidx.deleteDatabase
import io.github.kidx.openDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import stramus.core.db.StoreSeed
import stramus.core.db.installIndexedDb
import stramus.core.db.openStramusStore
import stramus.core.db.stramusSchema
import stramus.core.imports.importFile

/**
 * The tests nobody wrote, because nobody thought of them.
 *
 * Every other file here checks a case somebody had in mind. That is worth having and it is not enough:
 * each real defect this suite has ever caught was invisible until something forced it into the open,
 * and the next one will be too. So this does not check cases at all. It plays random sequences of the
 * things a person actually does — save a link, rename it, delete it, write a note, make a group, drag a
 * card, import a file — on several devices at once, syncing at random moments, with the network failing
 * at random moments, and then asks the four questions that must have the same answer whatever happened:
 *
 *  - **it converges**: once the dust settles, every device holds exactly the same thing;
 *  - **it terminates**: the settling takes a bounded number of rounds, rather than two devices trading
 *    one row for ever;
 *  - **nothing is lost**: a card that was made and never deleted is on every device;
 *  - **nothing comes back**: a card that was deleted and never remade is on none of them.
 *
 * The last two are asked only of cards each device *owns* — every device saves under an address prefix
 * of its own, and only its owner renames or deletes it. Not to make the test easier: without it those
 * two questions have no answer. Two devices that edit and delete the same card without having heard from
 * each other are a genuine conflict, and last-write-wins settles it by the clock — an edit made after a
 * deletion revives the row, on purpose (the server has a test for it). "Deleted and never remade" would
 * then be asking the test to re-derive the merge rules in order to check them, which is how a test comes
 * to agree with a bug. Conflicts are still everywhere in what follows: groups, imports and the shape of
 * the collection are shared by everybody.
 *
 * A failure prints its seed and the whole sequence that produced it, because a random test that cannot
 * be replayed is a rumour rather than a test. Run one seed again with [replay] and it is an ordinary,
 * deterministic failing test.
 *
 * The seeds below are fixed rather than drawn from the clock: a suite that tests something different on
 * every run is a suite whose green means nothing in particular, and a red one that nobody can reproduce.
 * New seeds are added by hand — that is the moment somebody looks at what they found.
 */
class SyncPropertyTest {

    @Test
    fun `random sequences on two devices converge, lose nothing and resurrect nothing`() = runTest {
        SEEDS.forEach { seed -> run(seed, devices = 2, steps = 40, faults = false) }
    }

    @Test
    fun `and three devices, which is where an order of arrival stops being obvious`() = runTest {
        SEEDS.take(4).forEach { seed -> run(seed, devices = 3, steps = 40, faults = false) }
    }

    @Test
    fun `and with the network failing under them`() = runTest {
        // Dropped answers, truncated pages, a run killed between applying a delta and writing the cursor.
        // The four questions are the same ones: an interrupted sync may leave work undone, never wrong.
        SEEDS.forEach { seed -> run(seed, devices = 2, steps = 40, faults = true) }
    }

    @Test
    fun `and a seed that once failed, kept so it cannot fail that way again`() = runTest {
        // Nothing here yet. When the generator finds something, its seed is copied in and stays — a
        // property test earns its keep by turning surprises into fixtures.
        REGRESSION_SEEDS.forEach { (seed, devices) -> run(seed, devices, steps = 60, faults = true) }
    }
}

private val SEEDS = listOf(1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L)

/** Seeds that once failed. Each is a bug that was found here and must not come back. */
private val REGRESSION_SEEDS: List<Pair<Long, Int>> = emptyList()

/** How many quiet rounds it may take before the devices are agreed. Beyond this they never will be. */
private const val SETTLE_ROUNDS = 12

// ---- the run ----------------------------------------------------------------------------------------

private suspend fun run(seed: Long, devices: Int, steps: Int, faults: Boolean) {
    installIndexedDb()
    val random = Xorshift(seed)
    // A page of three, which is nothing like production and exactly the point: paging is where the two
    // sides last disagreed, and a page big enough to hold every account this test builds would never
    // exercise it at all.
    val server = Server().apply { pageSize = 3 }
    val api = SyncApi { request -> server.handle(request) }
    val log = mutableListOf<String>()

    val world = List(devices) { index ->
        val name = "prop-$seed-$index"
        val schema = Schema(name, stramusSchema.migrations)
        deleteDatabase(name)
        val db = openDatabase(schema)
        val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag here."))
        val engine = SyncEngine(store.db, api)
        // Every device but the first joins the account the way a second browser does: the welcome note
        // it seeded itself is ours, not the user's, and keeping it would be a duplicate by construction.
        engine.signIn(USER, Uuid.random(), discardLocal = index > 0)
        Device(store, engine, prefix = "https://example.org/$index/")
    }

    // One round before anything else, so every device has the account's collections to act on. A device
    // that threw its own away on joining has nothing at all until it has heard from the server once, and
    // steps landing on it would quietly do nothing — a generator that mostly does nothing is a generator
    // that mostly finds nothing.
    world.forEach { runCatching { it.engine.syncNow() } }
    world.forEach { runCatching { it.engine.syncNow() } }

    /** Cards that were made and never deleted, and cards that were deleted and never remade. */
    val alive = mutableSetOf<String>()
    val dead = mutableSetOf<String>()

    try {
        repeat(steps) { step ->
            val device = world[random.int(world.size)]
            val what = describe(random, device, step, alive, dead, log, server, faults)
            log += what
        }

        // Everything the network was doing to them stops, and they are left to agree.
        server.clearFaults()
        val rounds = settle(world)
        assertTrue(
            rounds < SETTLE_ROUNDS,
            fail(seed, log, "they never went quiet — $rounds rounds and still talking:\n" + diff(world.map { it.shape() })),
        )

        val shapes = world.map { it.shape() }
        assertTrue(shapes.distinct().size == 1, fail(seed, log, "the devices disagree:\n${shapes.joinToString("\n--\n")}"))

        val held = world.first().urls()
        alive.forEach { url ->
            assertTrue(url in held, fail(seed, log, "$url was saved and never deleted, and is nowhere"))
        }
        dead.forEach { url ->
            assertTrue(url !in held, fail(seed, log, "$url was deleted and came back"))
        }
    } finally {
        world.forEach { it.store.close() }
    }
}

/** Sync everyone until nobody has anything left to say; the answer is how many rounds that took. */
private suspend fun settle(world: List<Device>): Int {
    var rounds = 0
    while (rounds < SETTLE_ROUNDS) {
        rounds++
        var shapesBefore = world.map { it.shape() }
        world.forEach { runCatching { it.engine.syncNow() } }
        val after = world.map { it.shape() }
        if (after == shapesBefore && after.distinct().size == 1) return rounds
        shapesBefore = after
    }
    return rounds
}

/** One random thing a person might do, done — and written down in the words a failure will show. */
private suspend fun describe(
    random: Xorshift,
    device: Device,
    step: Int,
    alive: MutableSet<String>,
    dead: MutableSet<String>,
    log: MutableList<String>,
    server: Server,
    faults: Boolean,
): String {
    val store = device.store
    val collection = store.collections.all().firstOrNull() ?: return "no collection yet"
    val cards = store.cards.byCollection(collection.id)

    if (faults && random.int(6) == 0) {
        val fault = server.injectFault()
        return "network: $fault"
    }

    // What this device is allowed to touch: what it saved itself. See the file comment.
    val mine = cards.filter { it.url.startsWith(device.prefix) }

    return when (random.int(10)) {
        0, 1, 2 -> {
            val url = "${device.prefix}$step"
            store.cards.add(collection.id, "Card $step", url, null)
            alive += url
            dead -= url
            "add $url"
        }

        3 -> mine.randomOrNull(random)?.let { card ->
            store.cards.rename(card.id, "Renamed $step")
            "rename ${card.url}"
        } ?: "nothing of mine to rename"

        4 -> mine.randomOrNull(random)?.let { card ->
            store.cards.delete(card.id)
            alive -= card.url
            dead += card.url
            "delete ${card.url}"
        } ?: "nothing of mine to delete"

        5 -> {
            store.cardSections.create(collection.id, "Group $step", null)
            "group $step"
        }

        6 -> {
            val groups = store.cardSections.byCollection(collection.id)
            val card = mine.randomOrNull(random)
            if (card != null) {
                store.cards.move(card.id, collection.id, groups.randomOrNull(random)?.id, 0)
                "move ${card.url}"
            } else {
                "nothing to move"
            }
        }

        7 -> {
            val groups = store.cardSections.byCollection(collection.id)
            groups.randomOrNull(random)?.let { group ->
                store.cardSections.delete(group.id)
                "drop group ${group.title}"
            } ?: "no group to drop"
        }

        8 -> {
            // An import, because that is the other way rows appear and it has to play by the same rules.
            val url = "https://imported.example/$step"
            importFile(
                store,
                "bookmarks.html",
                """<DL><p><DT><H3>Работа</H3><DL><p><DT><A HREF="$url">Imported $step</A></DL><p></DL><p>""",
                "Imported",
            ) { null }
            alive += url
            dead -= url
            "import $url"
        }

        else -> {
            runCatching { device.engine.syncNow() }
            "sync"
        }
    }
}

private fun <T> List<T>.randomOrNull(random: Xorshift): T? = if (isEmpty()) null else this[random.int(size)]

private suspend fun Device.urls(): Set<String> =
    store.collections.all().flatMap { store.cards.byCollection(it.id) }.map { it.url }.toSet()

/** What the devices disagree about, rather than everything they hold — a failure has to be readable. */
private fun diff(shapes: List<String>): String {
    val lines = shapes.map { it.lines().filter(String::isNotBlank).toSet() }
    val everywhere = lines.reduce { a, b -> a intersect b }
    return shapes.indices.joinToString("\n") { index ->
        val only = (lines[index] - everywhere).sorted()
        "device $index has, and someone does not:\n" + only.joinToString("\n").ifBlank { "  (nothing)" }
    }
}

private fun fail(seed: Long, log: List<String>, why: String): String = buildString {
    appendLine(why)
    appendLine("seed $seed — replay it by putting it in REGRESSION_SEEDS. What happened:")
    log.forEachIndexed { index, line -> appendLine("  ${index.toString().padStart(3)}  $line") }
}

private val USER = Uuid.random()

/**
 * A random number generator with a seed, so a failure can be replayed exactly.
 *
 * `kotlin.random.Random(seed)` would do, but xorshift is four lines and cannot change under us between
 * Kotlin versions — a "deterministic" test whose determinism depends on the standard library's choice
 * of algorithm is one that stops reproducing the day that choice changes.
 */
private class Xorshift(seed: Long) {
    private var state = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun int(bound: Int): Int {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        // Masked to positive before the modulo: a truncated Long is as often negative as not, and a
        // negative index is a crash in the generator rather than a finding about the thing generated.
        val value = (state ushr 1).toInt() and Int.MAX_VALUE
        return if (bound <= 0) 0 else value % bound
    }
}
