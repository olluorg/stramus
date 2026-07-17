package stramus.core.order

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderKeyTest {

    @Test
    fun `first key of an empty group`() {
        assertEquals("a0", OrderKey.between(null, null))
        assertEquals(OrderKey.FIRST, OrderKey.between(null, null))
    }

    @Test
    fun `appending bumps the integer part and keeps the key short`() {
        assertEquals("a1", OrderKey.between("a0", null))
        assertEquals("a2", OrderKey.between("a1", null))
    }

    @Test
    fun `prepending goes below the first key`() {
        val prepended = OrderKey.between(null, "a0")
        assertTrue(prepended < "a0", "\"$prepended\" should sort before \"a0\"")
    }

    @Test
    fun `inserting between neighbours subdivides the fraction`() {
        val mid = OrderKey.between("a0", "a1")
        assertTrue("a0" < mid && mid < "a1", "\"$mid\" should sit between \"a0\" and \"a1\"")
    }

    @Test
    fun `keys can always be squeezed between two neighbours, however close`() {
        // Insert into the same gap over and over: every key has room under it, which is the whole
        // promise of the scheme and the reason a move rewrites one row instead of a hundred.
        var low = "a0"
        val high = "a1"
        repeat(200) {
            val mid = OrderKey.between(low, high)
            assertTrue(low < mid && mid < high, "\"$mid\" should sit between \"$low\" and \"$high\"")
            low = mid
        }
    }

    @Test
    fun `a long run of appends does not grow the key`() {
        // The naive "midpoint against infinity" would add a character every few appends and leave a
        // much-used collection with keys hundreds of characters long. The integer part is what stops
        // that, and this is the test that would catch losing it.
        var key = OrderKey.between(null, null)
        var previous = key
        repeat(5_000) {
            key = OrderKey.between(key, null)
            assertTrue(previous < key, "appended \"$key\" should sort after \"$previous\"")
            previous = key
        }
        assertTrue(key.length <= 4, "after 5000 appends the key is \"$key\" (${key.length} chars)")
    }

    @Test
    fun `a run of prepends does not grow the key either`() {
        var key = OrderKey.between(null, null)
        var previous = key
        repeat(5_000) {
            key = OrderKey.between(null, key)
            assertTrue(key < previous, "prepended \"$key\" should sort before \"$previous\"")
            previous = key
        }
        assertTrue(key.length <= 4, "after 5000 prepends the key is \"$key\" (${key.length} chars)")
    }

    @Test
    fun `sequence lays out n keys in ascending order`() {
        val keys = OrderKey.sequence(null, null, 100)
        assertEquals(100, keys.size)
        assertEquals(keys.sorted(), keys, "the generated keys should already be in order")
        assertEquals(keys.distinct().size, keys.size, "the generated keys should be distinct")
    }

    @Test
    fun `sequence stays inside its bounds`() {
        val keys = OrderKey.sequence("a0", "a1", 50)
        assertEquals(50, keys.size)
        assertEquals(keys.sorted(), keys)
        assertTrue(keys.all { it > "a0" && it < "a1" }, "every key should sit between the bounds")
    }

    @Test
    fun `sequence spreads keys instead of stacking them`() {
        // Generated one after another, the hundredth key would be far longer than the first. Halving
        // is what keeps a bulk insert — the migration off integer positions — from doing that.
        val keys = OrderKey.sequence("a0", "a1", 100)
        assertTrue(keys.maxOf { it.length } <= 6, "longest key was \"${keys.maxBy { it.length }}\"")
    }

    @Test
    fun `asking for a key between a row and itself is a bug, not a key`() {
        assertFailsWith<IllegalArgumentException> { OrderKey.between("a1", "a1") }
        assertFailsWith<IllegalArgumentException> { OrderKey.between("a1", "a0") }
    }

    @Test
    fun `keys with a trailing zero are rejected`() {
        // "a1V0" and "a1V" would name the same point, and two spellings of one position break the
        // "strictly between" guarantee. The generator never makes one; a caller must not either.
        assertFailsWith<IllegalArgumentException> { OrderKey.between("a1V0", null) }
    }

    @Test
    fun `random inserts keep the list ordered`() {
        // The property that actually matters, exercised the way the app exercises it: keep dropping a
        // new row into a random gap and check the list is still sorted afterwards.
        val random = Random(20260714)
        val keys = mutableListOf(OrderKey.between(null, null))
        repeat(1_000) {
            val at = random.nextInt(keys.size + 1)
            val prev = keys.getOrNull(at - 1)
            val next = keys.getOrNull(at)
            val key = OrderKey.between(prev, next)
            keys.add(at, key)
            assertEquals(keys.sorted(), keys, "inserting \"$key\" at $at broke the order")
        }
    }
}
