package stramus.core.topics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The other way to the level above a collection: ask the model on the machine.
 *
 * `SiteGroups` reads the shelf off the address, which is exact and shallow — it knows a shop is shopping
 * and nothing more. A model reads the names: "Калина замена топливной трубки", "Экстрактор М16", "Набор
 * оправок", "Блок питания makita" are four unrelated strings to every rule in this package and one
 * afternoon of repairs to anybody who reads them. That is an abstraction, and no amount of word counting
 * produces it.
 *
 * What is here is the question and what an answer has to survive; the session that asks it is in the jsMain
 * half, where the browser's model lives. Everything the model says is checked: a group must be made of
 * collections that were actually offered, each collection lands in one group at most, a name must be a
 * name. A collection the answer mangles or forgets simply has no group, which is the same failure this
 * whole feature is built to have — less done, never wrong.
 */

/** A group of one collection is that collection with a hat on; the level only means something above two. */
private const val MIN_IN_GROUP = 2

/**
 * Names that mean "the rest of them". The prompt says to leave out whatever fits nothing, and a model
 * asked for groups will cheerfully invent this instead — one real answer put a job search, a set of brake
 * parts and a pile of YouTube under "Разное". A sidebar section called that is the unsorted heap with a
 * heading on it, and worse than the heap, because this one gets saved. Refused here, those collections
 * fall through to what the addresses say, which had all three of them right.
 */
private val CATCH_ALL = setOf(
    "разное", "разные", "прочее", "прочие", "другое", "другие", "остальное", "всякое", "без категории",
    "misc", "miscellaneous", "other", "others", "various", "general", "uncategorized", "unsorted",
    "ungrouped", "everything else", "the rest", "divers", "varios", "sonstiges", "altro", "その他", "기타",
    "其他", "diğer", "outros",
)

/**
 * What the model is asked: the collection names, and for a few broad groups over them, named in
 * [language] — the name of the app's own language, written in English for a prompt that is in English.
 *
 * The language has to be said, and it is not the language of the folder names. Asked for "the language
 * the folders are written in", a real answer put "Авто" beside the "Shopping" and "Jobs" that this app's
 * own English interface had produced for the same sidebar. A group is a heading in the app, and every
 * other heading in it follows the language the user chose.
 */
fun groupingPrompt(collections: List<String>, language: String): String = buildString {
    append("These are folder names from one person's browser, each holding a few saved pages:\n")
    collections.forEach { append("- ").append(it.take(TOPIC_NAME_LIMIT)).append('\n') }
    append("Sort them into two to five broad groups — the kinds of thing this person is doing, ")
    append("such as errands, a car repair, work, entertainment. Name each group in one or two words, ")
    append("in ").append(language).append(", whatever language the folder names themselves are in. ")
    append("Leave out any folder that fits nothing; never put one folder in two groups, ")
    append("and never invent a folder that is not on the list.")
}

/** The shape of the answer — see [AiSession.askJson]: the browser holds the model to this while it writes. */
fun groupingSchema(): String =
    """
    {
      "type": "object",
      "properties": {
        "groups": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": { "type": "string" },
              "collections": { "type": "array", "items": { "type": "string" } }
            },
            "required": ["name", "collections"],
            "additionalProperties": false
          }
        }
      },
      "required": ["groups"],
      "additionalProperties": false
    }
    """.trimIndent()

/**
 * The answer as "this collection goes in that group", for the collections the model handled properly.
 *
 * [offered] is the list it was given, and nothing outside it is accepted: a model that answers with a
 * folder nobody has is inventing, and the invention would otherwise create a collection. Matching is by
 * name, trimmed and case-insensitive, because that is the only handle a name-based answer has.
 */
fun groupsFromAnswer(answer: String, offered: List<String>): Map<String, String> {
    val known = offered.associateBy { it.trim().lowercase() }
    val root = runCatching { Json.parseToJsonElement(answer) as? JsonObject }.getOrNull() ?: return emptyMap()
    val groups = root["groups"] as? JsonArray ?: return emptyMap()
    val placed = mutableMapOf<String, String>()
    groups.forEach { entry ->
        val group = entry as? JsonObject ?: return@forEach
        val name = (group["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return@forEach
        if (name.isBlank() || name.length > TOPIC_NAME_LIMIT) return@forEach
        if (name.lowercase() in CATCH_ALL) return@forEach
        val members = (group["collections"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.lowercase() }
            .mapNotNull { known[it] }
            // The first group claiming a collection keeps it; a model listing one twice has said nothing
            // about where it really belongs.
            .filterNot { it in placed }
            .distinct()
        if (members.size < MIN_IN_GROUP) return@forEach
        members.forEach { placed[it] = name }
    }
    return placed
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
