@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.sync

import io.github.kidx.deleteDatabase
import io.github.kidx.Schema
import io.github.kidx.openDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import stramus.core.db.StoreSeed
import stramus.core.db.StramusStore
import stramus.core.db.installIndexedDb
import stramus.core.db.applyMerge
import stramus.core.db.openStramusStore
import stramus.core.db.stramusSchema
import stramus.core.merge.planMerge
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

/**
 * The two halves talking to each other, rather than each to a stand-in written to agree with it.
 *
 * This is the gap the rest of the suite could not close. `SyncEngineTest` drives the real engine against
 * a scripted server; `SyncTest` and `EndToEndSyncTest` drive the real server against a hand-rolled
 * client. Both were green while the pair was broken: the server said "there is more" and handed back a
 * cursor meaning "you have read everything", and no stand-in could disagree with the code that wrote it.
 * A bug of that shape only ever shows up at runtime, which is exactly where it did show up.
 *
 * So here the *real* [SyncEngine] — the one the browser runs, over a real kidx database — talks to
 * [Server], which is not a stand-in either: every decision it makes comes from `protocol`, from the same
 * `mergeRow`, `deltaPageOf` and `DeltaCursor` the Ktor server calls. What is left uncovered is the
 * transport between the two runtimes (Ktor, JSON, the token), and that is what `EndToEndSyncTest` drives
 * over real HTTP. Between them there is no longer a place for the two sides to disagree in private.
 */
class ContractTest {

    @Test
    fun `a device joining an account gets every row of it, however many pages that takes`() = contractTest { a, b, server ->
        // The bug this file exists for. One push takes one revision, so an account's whole history sits
        // at the same revision — and a cursor that is only a revision cannot say where a page stopped.
        server.pageSize = 3
        val collection = a.store.collections.all().first()
        repeat(10) { n -> a.store.cards.add(collection.id, "Card $n", "https://example.org/$n", null) }
        a.engine.syncNow()

        b.engine.syncNow()

        val arrived = b.store.collections.all().flatMap { b.store.cards.byCollection(it.id) }
        assertTrue(arrived.count { it.url.startsWith("https://example.org/") } == 10, "all ten, not the first page")
        // And the cursor it ends on is a true one: nothing is left over for the next run to find.
        val before = server.pulls
        b.engine.syncNow()
        assertEquals(server.pulls, before + 1, "one round trip, because there is nothing left to page")
    }

    @Test
    fun `a card made on one device turns up on the other, and only once`() = contractTest { a, b, server ->
        val collection = a.store.collections.all().first()
        a.store.cards.add(collection.id, "Kotlin", "https://kotlinlang.org/", null)
        a.engine.syncNow()

        b.engine.syncNow()
        b.engine.syncNow()
        a.engine.syncNow()

        assertEquals(1, b.cardsTitled("Kotlin"), "one card, not two")
        assertEquals(1, a.cardsTitled("Kotlin"), "and it did not come home doubled either")
        assertTrue(server.rowsFor("cards").count { it.payloadTitle() == "Kotlin" } == 1)
    }

    @Test
    fun `a browser full of links joins an account without sending it all in one breath`() =
        contractTest { a, _, server ->
            // The push is paged as the delta is. Whether a single request of a few thousand rows would
            // survive whatever body limit sits between the browser and the database is not a question
            // this code gets to answer, so it does not ask it.
            val collection = a.store.collections.all().first()
            repeat(450) { n -> a.store.cards.add(collection.id, "Card $n", "https://example.org/$n", null) }

            a.engine.syncNow()

            assertTrue(server.biggestPush <= 200, "no request carried more than a bite: ${'$'}{server.biggestPush}")
            assertEquals(451, server.rowsFor("cards").size, "and all of them arrived, welcome note included")
        }

    @Test
    fun `two syncs asked for at once do not become two syncs`() = contractTest { a, _, server ->
        // The timer, the window coming back into focus, and the button in the account dialog can all
        // ask at the same moment. Before this they all got what they asked for: two runs reading the
        // same cursor, pushing the same rows and racing to write the cursor back.
        val collection = a.store.collections.all().first()
        repeat(5) { n -> a.store.cards.add(collection.id, "Card ${'$'}n", "https://example.org/${'$'}n", null) }

        val both = coroutineScope {
            listOf(async { a.engine.syncNow() }, async { a.engine.syncNow() }).map { it.await() }
        }

        assertEquals(1, both.count { it != null }, "one of the two did the work and the other stood down")
        assertEquals(6, server.rowsFor("cards").size, "and the rows went up once, not twice")
    }

    @Test
    fun `a first sync of a few thousand rows costs a bounded number of round trips`() =
        contractTest { a, b, server ->
            // The shape of the worst first sync there is: a browser with years in it, joining. What is
            // being watched is not the wall clock — a test machine's second means nothing — but the
            // number of times the two speak, which is what turns into minutes on a real connection.
            val collection = a.store.collections.all().first()
            repeat(1_000) { n -> a.store.cards.add(collection.id, "Card ${'$'}n", "https://example.org/${'$'}n", null) }

            a.engine.syncNow()
            val pushTrips = server.pulls
            b.engine.syncNow()
            val pullTrips = server.pulls - pushTrips

            // 1001 rows at 200 a push and 500 a page: six to send, three to fetch. The number that
            // matters is that neither grows with the square of the account — sending a bite used to
            // drag the whole delta down behind it, and this is where that would show.
            assertTrue(pushTrips <= 12, "sending a thousand rows took ${'$'}pushTrips round trips")
            assertTrue(pullTrips <= 6, "fetching them took ${'$'}pullTrips round trips")
            assertEquals(1_001, b.store.cards.byCollection(collection.id).size, "and all of them arrived")
        }

    @Test
    fun `a quiet device pushes nothing at all`() = contractTest { a, _, server ->
        a.engine.syncNow()
        val after = server.pushedRows
        a.engine.syncNow()
        a.engine.syncNow()

        assertEquals(after, server.pushedRows, "a row already at its base version is not sent again")
    }

    @Test
    fun `a deletion travels and does not come back to life`() = contractTest { a, b, _ ->
        val collection = a.store.collections.all().first()
        val card = a.store.cards.add(collection.id, "Doomed", "https://example.org/doomed", null)
        a.engine.syncNow()
        b.engine.syncNow()
        assertEquals(1, b.cardsTitled("Doomed"))

        a.store.cards.delete(card.id)
        a.engine.syncNow()
        b.engine.syncNow()

        assertEquals(0, b.cardsTitled("Doomed"), "the deletion reached the other device")
        // And a further run from the device that still remembers making it does not resurrect it.
        b.engine.syncNow()
        a.engine.syncNow()
        assertEquals(0, b.cardsTitled("Doomed"))
        assertEquals(0, a.cardsTitled("Doomed"))
    }

    @Test
    fun `a rename made on one device wins over the older name on the other`() = contractTest { a, b, _ ->
        val collection = a.store.collections.all().first()
        val card = a.store.cards.add(collection.id, "Before", "https://example.org/x", null)
        a.engine.syncNow()
        b.engine.syncNow()

        a.store.cards.rename(card.id, "After")
        a.engine.syncNow()
        b.engine.syncNow()

        assertEquals(1, b.cardsTitled("After"))
        assertEquals(0, b.cardsTitled("Before"))
    }

    @Test
    fun `the two settle down - a few rounds later neither has anything left to say`() = contractTest { a, b, server ->
        // The property that says the pair converges: whatever they did, once the dust settles nobody is
        // pushing anything. A pair that never goes quiet is a pair trading one row for ever, which is
        // invisible in the UI and fatal to a battery.
        val collection = a.store.collections.all().first()
        a.store.cards.add(collection.id, "One", "https://example.org/1", null)
        a.store.cards.addNote(collection.id, "A note", "some words")
        a.engine.syncNow()
        b.engine.syncNow()
        a.engine.syncNow()
        b.engine.syncNow()

        val quiet = server.pushedRows
        a.engine.syncNow()
        b.engine.syncNow()
        assertEquals(quiet, server.pushedRows, "nothing left to push on either side")
    }

    @Test
    fun `what one device holds, the other holds too`() = contractTest { a, b, _ ->
        val collection = a.store.collections.all().first()
        a.store.cards.add(collection.id, "Link", "https://example.org/link", null)
        a.store.cards.addNote(collection.id, "Note", "body")
        val group = a.store.cardSections.create(collection.id, "Group", "about it")
        a.store.cards.add(collection.id, "In group", "https://example.org/g", null, cardSectionId = group.id)
        a.engine.syncNow()
        b.engine.syncNow()

        assertEquals(a.shape(), b.shape(), "the same rows, in the same places, with the same names")
    }

    @Test
    fun `a note edited on both at once becomes two notes rather than one lost paragraph`() =
        contractTest { a, b, _ ->
            val collection = a.store.collections.all().first()
            val note = a.store.cards.addNote(collection.id, "Plan", "first words")
            a.engine.syncNow()
            b.engine.syncNow()

            // Neither has seen the other's edit: both still hold the cursor from before either wrote.
            a.store.cards.updateNote(note.id, "Plan", "what A wrote")
            b.store.cards.updateNote(note.id, "Plan", "what B wrote")
            a.engine.syncNow()
            b.engine.syncNow()

            val bodies = b.store.collections.all()
                .flatMap { b.store.cards.byCollection(it.id) }
                .mapNotNull { it.content }
            assertTrue(bodies.any { it == "what A wrote" }, "the winner is there")
            assertTrue(bodies.any { it == "what B wrote" }, "and the loser was kept rather than dropped")
        }

    @Test
    fun `two devices that each keep their own end up with a pair, and the duplicate finder joins it`() =
        contractTest(keepBsOwn = true) { a, b, _ ->
            // The whole shape of the worry, end to end and with nothing faked. Both browsers seeded
            // themselves a "Getting started" before either had an account; both then joined it keeping
            // what they had. The server merges by row id and can do nothing else — it never reads a
            // title — so the account honestly holds two collections of one name, on both devices.
            a.engine.syncNow()
            b.engine.syncNow()
            a.engine.syncNow()

            val doubled = a.store.collections.all().filter { it.title == "Getting started" }
            assertEquals(2, doubled.size, "sync by id cannot join what two devices each made for themselves")

            // And this is what joins them — matching by what the rows say, which only a client can do.
            val plan = planMerge(
                a.store.sections.all(),
                a.store.collections.all(),
                a.store.collections.all().flatMap { a.store.cardSections.byCollection(it.id) },
                a.store.collections.all().flatMap { a.store.cards.byCollection(it.id) },
            )
            a.store.applyMerge(plan)

            assertEquals(1, a.store.collections.all().count { it.title == "Getting started" })

            // The merge is a deletion and a move like any other, so the other device is told about it in
            // the ordinary way — and ends up with the same single collection rather than a third state.
            a.engine.syncNow()
            b.engine.syncNow()
            assertEquals(1, b.store.collections.all().count { it.title == "Getting started" })
            assertEquals(a.shape(), b.shape(), "and the two agree about it afterwards")
        }

    @Test
    fun `a group deleted on one device leaves its cards in the collection, not pointing at nothing`() =
        contractTest { a, b, _ ->
            val collection = a.store.collections.all().first()
            val group = a.store.cardSections.create(collection.id, "Later", null)
            a.store.cards.add(collection.id, "Kept", "https://example.org/kept", null, cardSectionId = group.id)
            a.engine.syncNow()
            b.engine.syncNow()
            assertEquals(1, b.store.cardSections.byCollection(collection.id).size)

            a.store.cardSections.delete(group.id)
            a.engine.syncNow()
            b.engine.syncNow()

            assertEquals(emptyList(), b.store.cardSections.byCollection(collection.id), "the group went")
            val card = b.store.cards.byCollection(collection.id).single { it.title == "Kept" }
            assertEquals(null, card.cardSectionId, "and its card stayed, pointing at nothing that is gone")

            // Both sides agree afterwards, and neither is left with a dangling reference.
            a.engine.syncNow()
            b.engine.syncNow()
            assertEquals(a.shape(), b.shape())
        }

    @Test
    fun `a run that dies halfway leaves the cursor where it was`() = contractTest { a, b, server ->
        // A tab closed, a network dropped, a laptop shut. What must not survive it is a cursor that has
        // moved past rows the device never got: the next run would ask for changes after a revision it
        // never read, and those rows would be missing for good rather than for a moment.
        server.pageSize = 2
        val collection = a.store.collections.all().first()
        repeat(6) { n -> a.store.cards.add(collection.id, "Card ${'$'}n", "https://example.org/${'$'}n", null) }
        a.engine.syncNow()

        server.failAfterPages = 2
        runCatching { b.engine.syncNow() }
        server.failAfterPages = null

        // Whatever it managed to write down, it did not claim to have read the rest.
        b.engine.syncNow()
        b.engine.syncNow()
        val arrived = b.store.collections.all().flatMap { b.store.cards.byCollection(it.id) }
        assertEquals(6, arrived.count { it.url.startsWith("https://example.org/") }, "nothing was skipped")
        assertEquals(a.shape(), b.shape())
    }

    @Test
    fun `a device whose clock is a year fast does not win everything for ever`() = contractTest { a, b, _ ->
        val collection = a.store.collections.all().first()
        val card = a.store.cards.add(collection.id, "Now", "https://example.org/x", null)
        a.engine.syncNow()
        b.engine.syncNow()

        // A row stamped a year ahead. The server clamps it to its own time, so it wins the moment it is
        // written and nothing more — where unclamped it would beat every edit anyone made for a year.
        a.store.cards.rename(card.id, "From the future")
        a.engine.syncNow()
        b.engine.syncNow()
        b.store.cards.rename(card.id, "From now")
        b.engine.syncNow()
        a.engine.syncNow()

        assertEquals(1, a.cardsTitled("From now"), "the later of two honest clocks still wins")
    }

    @Test
    fun `a file saved on one device opens on the other`() = contractTest { a, b, _ ->
        val collection = a.store.collections.all().first()
        val bytes = "data:text/plain;base64,aGVsbG8="
        a.store.cards.addFile(collection.id, "notes.txt", bytes, "text/plain")
        a.engine.syncNow()
        b.engine.syncNow()

        val card = b.store.collections.all()
            .flatMap { b.store.cards.byCollection(it.id) }
            .single { it.title == "notes.txt" }
        assertTrue(card.blobSha != null, "the card names the bytes it wants")
        assertEquals(bytes, b.store.cards.blob(card.id), "and the bytes themselves came across")
    }

    @Test
    fun `a file the server will not take does not stop the rows from syncing`() = contractTest { a, b, server ->
        // A file past the account's quota, a network that dies mid-upload. The card is not damaged by
        // its bytes being late: it draws its name and its preview, and opening it is the only thing
        // that needs them at all.
        server.refuseBlobs = true
        val collection = a.store.collections.all().first()
        a.store.cards.addFile(collection.id, "big.bin", "data:application/octet-stream;base64,AAAA", "application/octet-stream")
        a.store.cards.add(collection.id, "Ordinary", "https://example.org/o", null)
        a.engine.syncNow()
        b.engine.syncNow()

        assertEquals(1, b.cardsTitled("Ordinary"), "the rows went up regardless")
        assertEquals(1, b.cardsTitled("big.bin"), "and so did the card")
        assertEquals(null, b.store.cards.blob(b.fileCardId("big.bin")), "only its bytes are still to come")
    }

    @Test
    fun `a row the server never had, deleted before it ever arrived, stays deleted`() = contractTest { a, b, _ ->
        // Made and deleted while nobody was listening. The tombstone reaches the server first and has to
        // be kept as one, or the card comes back the moment its own creation turns up.
        val collection = a.store.collections.all().first()
        val card = a.store.cards.add(collection.id, "Fleeting", "https://example.org/f", null)
        a.store.cards.delete(card.id)
        a.engine.syncNow()
        b.engine.syncNow()

        assertEquals(0, b.cardsTitled("Fleeting"))
    }
}

// ---- the two devices, and the server they share ------------------------------------------------------

private fun SyncRow.payloadTitle(): String? =
    payload?.get("title")?.toString()?.removeSurrounding("\"")

/**
 * Two devices and the server between them.
 *
 * [keepBsOwn] is the question the sign-in dialog actually asks the second device: keep the collections
 * already in this browser, or take the account's. False — discard — is the ordinary answer for a second
 * browser, because a fresh install has nothing but our own welcome note and keeping it would hand the
 * user a second copy of it for ever. True is the other answer, and what it costs is a test of its own.
 */
private fun contractTest(
    keepBsOwn: Boolean = false,
    block: suspend (Device, Device, Server) -> Unit,
) = runTest {
    installIndexedDb()
    val server = Server()
    val api = SyncApi { request -> server.handle(request) }

    // Two databases, because two devices. kidx names one database per schema, so the second is opened
    // under a name of its own — which is what "another browser" is here.
    val devices = listOf("stramus-contract-a", "stramus-contract-b").mapIndexed { index, name ->
        val schema = Schema(name, stramusSchema.migrations)
        deleteDatabase(name)
        val db = openDatabase(schema)
        val store = openStramusStore(db, StoreSeed("Main", "Getting started", "How to use", "Drag here."))
        val engine = SyncEngine(store.db, api, server.blobsApi())
        engine.signIn(USER, Uuid.random(), discardLocal = index == 1 && !keepBsOwn)
        Device(store, engine)
    }
    try {
        block(devices[0], devices[1], server)
    } finally {
        devices.forEach { it.store.close() }
    }
}

private val USER = Uuid.random()
