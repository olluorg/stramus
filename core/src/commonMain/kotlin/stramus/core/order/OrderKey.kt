package stramus.core.order

/**
 * The ordering key of a row — what puts the cards of a group, the collections of a section and the
 * sections of the sidebar in the order the user dragged them into.
 *
 * It is a *string*, not the obvious integer, and the reason is synchronisation. With a contiguous
 * `0, 1, 2, …` sequence there is no value between two neighbours, so moving one card has to renumber
 * every card after it — a hundred rows rewritten to express that one thing moved. Two devices doing
 * that at once then collide on every one of those rows, and the merge has to pick a winner for each.
 *
 * These keys always have room between them: [between] hands back a key that sorts strictly after
 * [between]'s first argument and strictly before its second, however close together those two are. A
 * move therefore writes exactly one row, and two devices moving different cards do not touch the same
 * rows at all.
 *
 * The keys sort with a plain string comparison — the alphabet below is in ASCII order, so lexicographic
 * order over these strings *is* the user's order. Rows are read `ORDER BY position, id`: the `id` is
 * there only to break a tie, which two devices can produce by generating the same key independently.
 *
 * ## Shape of a key
 *
 * `a0`, `a1`, … `az`, `b00`, … — an *integer part*, whose first character encodes its own length
 * (`a` = 2 characters, `b` = 3, …; `Z` = 2, `Y` = 3, … going negative), optionally followed by a
 * *fractional part* that subdivides the gap to the next integer: `a1V` sits between `a1` and `a2`.
 *
 * The integer part is what keeps appending cheap. Growing a key by a fraction each time — the naive
 * "midpoint between the last key and infinity" — adds a character every few inserts, so a collection
 * a user keeps adding to would end up with keys hundreds of characters long. Incrementing an integer
 * instead keeps a key at appending distance the same handful of characters, however many are appended.
 *
 * This is the [fractional indexing](https://observablehq.com/@dgreensp/implementing-fractional-indexing)
 * scheme (Figma's, as published by David Greenspan), ported here rather than pulled in as a dependency:
 * it is 150 lines, it has to run on both Kotlin/JS and the JVM, and it is the kind of code we want to
 * be able to read when a card lands in the wrong place.
 */
public object OrderKey {

    /**
     * The digits a key is written in — ASCII order, so `"a0" < "a1" < … < "az" < "b00"` holds under
     * ordinary string comparison, which is what the database sorts by.
     */
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** The key of the very first row of an empty group. */
    public val FIRST: String = "a" + DIGITS[0]

    /**
     * A key that sorts strictly between [prev] and [next] — null meaning "no neighbour on that side",
     * so `between(null, null)` is the first key of an empty group, `between(last, null)` appends, and
     * `between(null, first)` prepends.
     *
     * @throws IllegalArgumentException if [prev] is not strictly less than [next], which would ask for
     *   a key between a row and itself (or between two rows in the wrong order) — always a bug in the
     *   caller, never something to paper over with an arbitrary key.
     */
    public fun between(prev: String?, next: String?): String {
        prev?.let { validate(it) }
        next?.let { validate(it) }
        require(prev == null || next == null || prev < next) {
            "order keys out of order: \"$prev\" is not before \"$next\""
        }

        if (prev == null) {
            if (next == null) return FIRST

            val int = integerPart(next)
            val frac = next.substring(int.length)
            // The smallest integer part there is: it cannot be decremented, so the new key has to be
            // squeezed into the fraction below `next` instead.
            if (int == "A" + "0".repeat(26)) return int + midpoint("", frac)
            // `next` carries a fraction, so its own integer part is already free to use, being
            // strictly below it.
            if (int < next) return int
            return decrement(int) ?: error("order keys exhausted below \"$next\"")
        }

        if (next == null) {
            val int = integerPart(prev)
            val frac = prev.substring(int.length)
            // Appending: the ordinary case is to bump the integer, which keeps the key short. Only
            // when the integers are used up (`z…`, an absurd number of appends) does it fall back to
            // subdividing the fraction above `prev`.
            return increment(int) ?: (int + midpoint(frac, null))
        }

        val intPrev = integerPart(prev)
        val fracPrev = prev.substring(intPrev.length)
        val intNext = integerPart(next)
        val fracNext = next.substring(intNext.length)

        // Same integer: the gap is inside the fraction.
        if (intPrev == intNext) return intPrev + midpoint(fracPrev, fracNext)

        // Different integers: if there is a whole integer free between them, that is the shortest key
        // available. Otherwise they are adjacent, and the new key goes above `prev`'s fraction.
        val bumped = increment(intPrev) ?: error("order keys exhausted above \"$prev\"")
        if (bumped < next) return bumped
        return intPrev + midpoint(fracPrev, null)
    }

    /**
     * [count] keys in ascending order, all strictly between [prev] and [next]. What a bulk insert
     * needs — and what the migration off integer positions uses, laying the rows of a group out in
     * the order they already had.
     *
     * The keys are spread by halving rather than generated one after another, so a hundred of them
     * stay short instead of each being a character longer than the last.
     */
    public fun sequence(prev: String?, next: String?, count: Int): List<String> {
        require(count >= 0) { "count must not be negative, was $count" }
        if (count == 0) return emptyList()
        if (count == 1) return listOf(between(prev, next))

        if (next == null) {
            var key = between(prev, null)
            val keys = mutableListOf(key)
            repeat(count - 1) {
                key = between(key, null)
                keys += key
            }
            return keys
        }

        if (prev == null) {
            var key = between(null, next)
            val keys = mutableListOf(key)
            repeat(count - 1) {
                key = between(null, key)
                keys += key
            }
            return keys.asReversed()
        }

        val half = count / 2
        val mid = between(prev, next)
        return sequence(prev, mid, half) + mid + sequence(mid, next, count - half - 1)
    }

    // ---- the integer part -------------------------------------------------------------------------

    /**
     * How long the integer part beginning with [head] is, [head] itself included. `a`..`z` are the
     * non-negative magnitudes (2 to 27 characters), `A`..`Z` the negative ones — so a key can always
     * be prepended to as well as appended to.
     */
    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw IllegalArgumentException("invalid order key head: '$head'")
    }

    private fun integerPart(key: String): String {
        require(key.isNotEmpty()) { "order key must not be empty" }
        val length = integerLength(key[0])
        require(length <= key.length) { "order key \"$key\" is shorter than its integer part claims" }
        return key.substring(0, length)
    }

    private fun validate(key: String) {
        val int = integerPart(key)
        val frac = key.substring(int.length)
        // A fraction ending in the lowest digit names the same point as the fraction without it, and
        // two spellings of one position would break the "strictly between" guarantee — reject it.
        require(!frac.endsWith(DIGITS[0])) { "order key \"$key\" has a trailing zero in its fraction" }
        for (c in key) require(c in DIGITS) { "order key \"$key\" contains a character outside the alphabet" }
    }

    /** The next integer part after [int], or null if the magnitude is used up (`zzz…`). */
    private fun increment(int: String): String? {
        val head = int[0]
        val digits = int.substring(1).toCharArray()

        var carry = true
        var i = digits.size - 1
        while (carry && i >= 0) {
            val next = DIGITS.indexOf(digits[i]) + 1
            if (next == DIGITS.length) {
                digits[i] = DIGITS[0]
            } else {
                digits[i] = DIGITS[next]
                carry = false
            }
            i--
        }

        if (!carry) return head + digits.concatToString()

        // The digits wrapped: the magnitude has to grow (or, crossing zero, flip sign).
        return when (head) {
            'Z' -> "a" + DIGITS[0]
            'z' -> null
            else -> {
                val grown = head + 1
                val rest = digits.concatToString()
                // Past 'a' the magnitude counts upward (each step is one digit longer); below it, the
                // negatives count downward, so growing the magnitude *shortens* the digit string.
                if (grown > 'a') grown + rest + DIGITS[0] else grown + rest.dropLast(1)
            }
        }
    }

    /** The integer part before [int], or null if the magnitude is used up (`AAA…`). */
    private fun decrement(int: String): String? {
        val head = int[0]
        val digits = int.substring(1).toCharArray()

        var borrow = true
        var i = digits.size - 1
        while (borrow && i >= 0) {
            val next = DIGITS.indexOf(digits[i]) - 1
            if (next < 0) {
                digits[i] = DIGITS[DIGITS.length - 1]
            } else {
                digits[i] = DIGITS[next]
                borrow = false
            }
            i--
        }

        if (!borrow) return head + digits.concatToString()

        return when (head) {
            'a' -> "Z" + DIGITS[DIGITS.length - 1]
            'A' -> null
            else -> {
                val shrunk = head - 1
                val rest = digits.concatToString()
                if (shrunk < 'Z') shrunk + rest + DIGITS[DIGITS.length - 1] else shrunk + rest.dropLast(1)
            }
        }
    }

    // ---- the fractional part ----------------------------------------------------------------------

    /**
     * A fraction strictly between [a] and [b] (empty [a] = 0, null [b] = 1), in the fewest digits that
     * fit. Both are bare fractions — no integer part, no trailing zero.
     */
    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Whatever the two share is not where they differ: keep it and subdivide the rest.
            var common = 0
            while (common < b.length && (a.getOrNull(common) ?: DIGITS[0]) == b[common]) common++
            if (common > 0) return b.substring(0, common) + midpoint(a.drop(common), b.drop(common))
        }

        val digitA = if (a.isEmpty()) 0 else DIGITS.indexOf(a[0])
        val digitB = if (b != null) DIGITS.indexOf(b[0]) else DIGITS.length

        if (digitB - digitA > 1) {
            // There is a digit free between them — the shortest fraction possible.
            return DIGITS[(digitA + digitB) / 2].toString()
        }

        // The digits are adjacent, so the answer is longer than one digit.
        return if (b != null && b.length > 1) {
            // `b` has more to it than that first digit, so `b`'s own first digit is already above `a`.
            b.substring(0, 1)
        } else {
            // Keep `a`'s digit and go on subdividing what follows it: midpoint("49", "5") is "495".
            DIGITS[digitA] + midpoint(a.drop(1), null)
        }
    }
}
