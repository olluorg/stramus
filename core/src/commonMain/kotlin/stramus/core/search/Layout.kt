package stramus.core.search

/**
 * The same keystrokes, read on the other keyboard layout.
 *
 * Typing "kotlin" with the Russian layout still on produces "лщедшт"; typing "привет" with the English
 * one produces "ghbdtn". The user has hit exactly the right keys and the box shows nothing, which is
 * among the most annoying ways for a search to fail — it is not that the answer is missing, it is that
 * nobody read the question.
 *
 * Unlike [fuzzyMatch] this is not a guess. The map from a key to what it prints is fixed, so the
 * converted query is not "something like what they meant" but *precisely* what those keys would have
 * printed under the other layout. That is why it sits above the guesses in the ladder and only a shade
 * below a direct hit — see `Search.kt`.
 *
 * Which way to convert is decided by what was typed rather than tried both ways, and that decides the
 * punctuation too. Going back to Latin, "ю" is the full stop and "б" the comma — unambiguous, because
 * Cyrillic has no full stop of its own to collide with. Going the other way a full stop is a full stop,
 * and is left alone: a person who meant to write Russian did not type one in the middle of a word.
 */

/** The Russian letters the US-QWERTY keys print, key for key. */
private const val QWERTY = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
private const val JCUKEN = "йцукенгшщзхъфывапролджэячсмитьбюё"

private val LATIN_TO_CYRILLIC: Map<Char, Char> =
    QWERTY.indices.associate { QWERTY[it] to JCUKEN[it] }

/**
 * And back — with the two keys whose Russian face is punctuation. "." is on the key that prints "ю"
 * and "," on the one that prints "б", so a host typed in the wrong layout ("пшегиИюсщь") comes back
 * whole rather than broken across its dot.
 */
private val CYRILLIC_TO_LATIN: Map<Char, Char> =
    JCUKEN.indices.associate { JCUKEN[it] to QWERTY[it] } + mapOf('ю' to '.', 'б' to ',')

/**
 * [query] as the other layout would have printed it, or null when there is nothing to read differently:
 * no letters at all, both scripts at once (so the layout was evidently not stuck), or a conversion that
 * changes nothing.
 *
 * The case of the input is preserved — the caller lowercases for matching anyway, and a converted query
 * that shouted where the original whispered would be a strange thing to hand back.
 */
fun swapLayout(query: String): String? {
    var latin = 0
    var cyrillic = 0
    for (c in query) {
        val lower = c.lowercaseChar()
        when {
            lower in 'a'..'z' -> latin++
            lower in 'а'..'я' || lower == 'ё' -> cyrillic++
        }
    }
    // Both at once is a query somebody typed on purpose, not a layout left on by mistake.
    if (latin == 0 && cyrillic == 0) return null
    if (latin > 0 && cyrillic > 0) return null

    val map = if (cyrillic > 0) CYRILLIC_TO_LATIN else LATIN_TO_CYRILLIC
    val swapped = buildString(query.length) {
        for (c in query) {
            val mapped = map[c.lowercaseChar()]
            append(
                when {
                    mapped == null -> c
                    c.isUpperCase() -> mapped.uppercaseChar()
                    else -> mapped
                },
            )
        }
    }
    return swapped.takeIf { it != query }
}
