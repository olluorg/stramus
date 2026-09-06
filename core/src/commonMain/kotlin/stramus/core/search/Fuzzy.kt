package stramus.core.search

/**
 * Matching what the user meant rather than what they typed — the last thing the search box tries, and
 * the only one that forgives a mistake.
 *
 * Everything above this in the ladder (see `Search.kt`'s `matchOf`) asks a question with a yes-or-no
 * answer: is this the host they typed, does the title start with it, is it in there anywhere. Those
 * answers are exact, and an exact answer is worth more than a guess — which is why nothing here can
 * outscore them, and why none of it runs at all until they have all said no.
 *
 * Two guesses, for the two ways a query misses:
 *
 *  - a **typo**: the word is nearly right — a letter dropped, doubled, wrong, or two of them swapped
 *    ("kotlni", "buglaria"). [editDistance] measures it, transpositions included, because swapping two
 *    letters is one slip of the fingers and counting it as two would put it out of reach;
 *  - an **abbreviation**: the letters are right and in order but not together ("ktl" for "Kotlin").
 *    [abbreviationMatch] finds those, and where they landed.
 *
 * The danger in both is answering too much: at three characters nearly every word is within one edit
 * of the query, and a sentence of short words contains almost any run of letters in order. So the query
 * has a floor, the edit budget grows with the length of what is being matched, and an abbreviation has
 * to live inside a single word. A search box that answers everything answers nothing.
 */

/**
 * Below this many characters a query is too short to guess from — see the file comment.
 *
 * Public because a caller may have to *fetch* the candidates before anything can be guessed at: the
 * browser's history is searched by the browser, so a typo comes back empty and the ladder never sees a
 * row to forgive. Whether that second, broader fetch is worth making is this same question.
 */
const val FUZZY_MIN_QUERY: Int = 3

/**
 * What a fuzzy match is worth, on the scale `Search.kt` scores everything by.
 *
 * Both sit under the weakest exact tier there (a word found in the body of a note, worth 25), because
 * both are guesses about what was meant and that one is a fact about what is there. A typo is worth
 * more than an abbreviation: it says the user knows the word and mistyped it, where a run of initials
 * could belong to a dozen things.
 */
const val FUZZY_TYPO_SCORE: Double = 20.0
const val FUZZY_ABBREVIATION_SCORE: Double = 12.0

/** A match, and where in the text it landed — the ranges the dropdown draws in bold. */
data class FuzzyMatch(val score: Double, val ranges: List<IntRange>)

/**
 * Where a word ends and the next begins, for the purpose of matching a title: the punctuation a title
 * is actually built out of. The same set `Search.kt` splits a title on for its word-prefix tier, so
 * "a word of the title" means one thing in both places.
 */
private val WORD_BREAKS = charArrayOf(' ', '-', '_', '/', '.', ':', ',', '(', ')', '[', ']', '|', '—', '·')

/**
 * How near two words have to be to count as the same word mistyped.
 *
 * It grows with length because a slip costs the same either way and a long word has more room to hide
 * it: one edit in five characters is a fifth of the word wrong, one in ten is a tenth. Two is the
 * ceiling — beyond it the "typo" is a different word.
 */
private fun editBudget(length: Int): Int = when {
    length < 4 -> 0
    length < 7 -> 1
    else -> 2
}

/**
 * The best guess at [query] in [text], or null if neither guess is good enough.
 *
 * [query] is expected lowercase and trimmed, as the caller already has it; [text] is matched
 * case-insensitively and the ranges returned index into it as given, so the caller can highlight the
 * original.
 */
fun fuzzyMatch(query: String, text: String): FuzzyMatch? {
    if (query.length < FUZZY_MIN_QUERY || text.isBlank()) return null
    val lower = text.lowercase()

    typoMatch(query, text, lower)?.let { return it }
    return abbreviationMatch(query, lower)
}

/**
 * A word of the text that is [query] mistyped.
 *
 * Word by word rather than over the whole string: "kotlni docs" against "Kotlin documentation" is one
 * typo in one word, while measured end to end it is a dozen edits and would never be found. The best
 * (nearest) word wins, and its whole span is what gets highlighted — the user's eye is looking for the
 * word, not for the letter they got wrong.
 */
private fun typoMatch(query: String, text: String, lower: String): FuzzyMatch? {
    var best: IntRange? = null
    var bestDistance = Int.MAX_VALUE

    forEachWord(lower) { from, to ->
        val length = to - from
        // Only against words of a comparable length: "go" is not "google" mistyped, and comparing them
        // would cost the edits of the difference alone.
        val budget = editBudget(minOf(length, query.length))
        if (budget > 0 && kotlin.math.abs(length - query.length) <= budget) {
            val distance = editDistance(query, lower.substring(from, to), budget)
            if (distance in 1..budget && distance < bestDistance) {
                bestDistance = distance
                best = from until to
            }
        }
    }

    val range = best ?: return null
    // The nearer the word, the better the guess — but never near enough to reach the tier above.
    val penalty = (bestDistance - 1) * 3.0
    return FuzzyMatch(FUZZY_TYPO_SCORE - penalty, listOf(range.first..minOf(range.last, text.lastIndex)))
}

/**
 * A word that [query] is the shortening of: its letters, in that order, inside that one word, starting
 * at its first letter.
 *
 * All three conditions earn their place, and the middle one is the one that cost a test to find. Let an
 * abbreviation run across words and "ktl" matches "Keep the lights on" perfectly — K, T and L each
 * beginning a word, a flawless acronym of something nobody was looking for. English is full of short
 * words, so cross-word acronyms match nearly everything and are where the noise in a fuzzy box comes
 * from. Inside one word there is no such luck: "ktl" is "Kotlin" and very little else.
 *
 * Starting at the first letter, because that is how a word is shortened — "otl" is inside "Kotlin", in
 * order and close together, and is not what anyone means by it.
 *
 * [SPREAD] then bounds how much of the word may be skipped, and the tighter the run the better the
 * guess: the score falls away as the letters spread out, which orders "ktl" against "Kotlin" above
 * "ktl" against a longer word it happens to also fit.
 */
private fun abbreviationMatch(query: String, lower: String): FuzzyMatch? {
    var best: List<Int>? = null
    var bestDensity = 0.0

    forEachWord(lower) { from, to ->
        if (lower[from] == query[0]) {
            val positions = walk(query, lower, from, to)
            if (positions != null) {
                val spread = positions.last() - positions.first() + 1
                val density = query.length.toDouble() / spread
                if (spread <= query.length * SPREAD && density > bestDensity) {
                    bestDensity = density
                    best = positions
                }
            }
        }
    }

    val positions = best ?: return null
    return FuzzyMatch(FUZZY_ABBREVIATION_SCORE * bestDensity, mergeRanges(positions))
}

/** Where each character of [query] sits inside `text[from until to]`, in order, or null if one is missing. */
private fun walk(query: String, text: String, from: Int, to: Int): List<Int>? {
    val positions = ArrayList<Int>(query.length)
    var at = from
    for (c in query) {
        while (at < to && text[at] != c) at++
        if (at >= to) return null
        positions += at
        at++
    }
    return positions
}

/** How much of a word an abbreviation may skip over, as a multiple of how many letters it has. */
private const val SPREAD = 3

/**
 * Damerau–Levenshtein, bounded: the number of insertions, deletions, substitutions and *adjacent
 * transpositions* that turn one word into the other, or [budget] + 1 as soon as it is clear no answer
 * within the budget exists.
 *
 * Two rows of the table rather than all of them — nothing here needs the path, only the number — plus
 * the row before those two, which is what makes a transposition one edit instead of two.
 */
fun editDistance(a: String, b: String, budget: Int = Int.MAX_VALUE): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var twoBack = IntArray(b.length + 1)
    var oneBack = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        var rowBest = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(current[j - 1] + 1, oneBack[j] + 1, oneBack[j - 1] + cost)
            // The two letters were swapped: one slip, one edit.
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                value = minOf(value, twoBack[j - 2] + cost)
            }
            current[j] = value
            if (value < rowBest) rowBest = value
        }
        // Every remaining edit can only add to this row's best, so once it is past the budget the
        // answer is too. Saves walking the rest of a table whose result is already known to be "no".
        if (rowBest > budget) return budget + 1
        val spare = twoBack
        twoBack = oneBack
        oneBack = current
        current = spare
    }
    return oneBack[b.length]
}

/** Calls [block] with the half-open bounds of every word of [text]. */
private inline fun forEachWord(text: String, block: (from: Int, to: Int) -> Unit) {
    var from = 0
    while (from < text.length) {
        if (text[from] in WORD_BREAKS) {
            from++
            continue
        }
        var to = from
        while (to < text.length && text[to] !in WORD_BREAKS) to++
        block(from, to)
        from = to
    }
}

/** Single positions, run together into the spans a highlighter can draw in one go. */
private fun mergeRanges(positions: List<Int>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = positions.first()
    var previous = start
    for (at in positions.drop(1)) {
        if (at != previous + 1) {
            ranges += start..previous
            start = at
        }
        previous = at
    }
    ranges += start..previous
    return ranges
}
