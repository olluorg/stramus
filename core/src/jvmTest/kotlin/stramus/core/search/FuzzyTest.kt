package stramus.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guesses, and — mostly — what they must refuse to guess.
 *
 * This runs after every exact test in the ladder has said no, so anything it matches is something the
 * box would otherwise have shown nothing for. That makes the false positives the expensive ones: a
 * wrong guess does not replace a right answer, it fills a list that should have been empty.
 */
class FuzzyTest {

    @Test
    fun `a mistyped word is still that word`() {
        assertTrue(fuzzyMatch("kotlni", "Kotlin Programming Language") != null, "two letters swapped")
        assertTrue(fuzzyMatch("buglaria", "Bulgaria") != null, "a transposition inside a longer word")
        assertTrue(fuzzyMatch("documentaion", "Documentation") != null, "a dropped letter")
        assertTrue(fuzzyMatch("githbu", "GitHub — where the world builds software") != null)
    }

    @Test
    fun `the highlight covers the word, not the letter that was wrong`() {
        val match = fuzzyMatch("kotlni", "The Kotlin docs")!!
        assertEquals(listOf(4..9), match.ranges)
        assertEquals("Kotlin", "The Kotlin docs".substring(4..9))
    }

    @Test
    fun `a different word is not a typo`() {
        assertNull(fuzzyMatch("cat", "dog"))
        assertNull(fuzzyMatch("kotlin", "python"), "nothing in common is not a slip of the fingers")
        // Length alone rules this out before a single edit is counted: "goo" is not "google" mistyped,
        // it is the start of it — which the ladder's own prefix tier catches long before this runs.
        assertNull(fuzzyMatch("xyz", "google"))
    }

    @Test
    fun `a query too short to be sure of is not guessed at`() {
        // At two characters almost every word is one edit away, so nothing is.
        assertNull(fuzzyMatch("kt", "Kotlin"))
        assertNull(fuzzyMatch("ab", "Abc def"))
    }

    @Test
    fun `initials find the thing they are initials of`() {
        // k-o-t-l: three letters across four, so three quarters of the full amount.
        val match = fuzzyMatch("ktl", "Kotlin")!!
        assertEquals(FUZZY_ABBREVIATION_SCORE * (3.0 / 4.0), match.score, 0.001)
        assertTrue(fuzzyMatch("ghb", "GitHub") != null)
    }

    @Test
    fun `an abbreviation that runs across words is not one`() {
        // A flawless acronym of something nobody was looking for: K-eep T-he L-ights. Allowing these is
        // where the noise in a fuzzy box comes from, so an abbreviation has to live inside one word.
        assertNull(fuzzyMatch("ktl", "Keep the lights on, please"))
        assertNull(fuzzyMatch("abc", "a very big careful cat"))
    }

    @Test
    fun `an abbreviation has to start a word`() {
        // "otl" is inside "Kotlin", in order and close together — but nobody abbreviates from the
        // middle of a word, and allowing it would match nearly anything.
        assertNull(fuzzyMatch("otl", "Kotlin"))
    }

    @Test
    fun `a guess never reaches the weakest exact tier`() {
        // 25 is what a word found in the body of a note is worth; every guess has to sit under it.
        val guesses = listOf(
            fuzzyMatch("kotlni", "Kotlin"),
            fuzzyMatch("documentaion", "Documentation"),
            fuzzyMatch("ktl", "Kotlin"),
            fuzzyMatch("ghb", "GitHub"),
        )
        assertTrue(guesses.all { it != null && it.score < 25.0 }, "a guess must not outrank a fact")
    }

    @Test
    fun `the nearer word wins`() {
        // Both are within budget; the one edit away is the better guess and takes the highlight.
        val match = fuzzyMatch("kotlin", "Kotlik and Kotlan")!!
        assertEquals(1, match.ranges.size)
    }

    @Test
    fun `a transposition costs one edit, not two`() {
        assertEquals(1, editDistance("kotlni", "kotlin"))
        assertEquals(1, editDistance("ab", "ba"))
        assertEquals(2, editDistance("kitten", "kiten".reversed().reversed() + "s"))
    }

    @Test
    fun `edit distance counts the ordinary edits too`() {
        assertEquals(0, editDistance("same", "same"))
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(4, editDistance("", "four"))
        assertEquals(4, editDistance("four", ""))
    }

    @Test
    fun `the budget stops the walk without changing the answer below it`() {
        // Under the budget the number is exact; over it, only "more than the budget" is promised.
        assertEquals(3, editDistance("kitten", "sitting", budget = 5))
        assertTrue(editDistance("kitten", "sitting", budget = 1) > 1)
    }
}
