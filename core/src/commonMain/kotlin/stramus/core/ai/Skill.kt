@file:OptIn(ExperimentalUuidApi::class)

package stramus.core.ai

import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.url.hostOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * A skill is a saved AI action, filed as a card in the *section* it works over (see [CardKind.SKILL]).
 * A section is one card-group inside a collection — the divider a run of cards sits under — so a skill
 * reads the cards under its own divider, not the whole collection: two skills in one collection can do
 * two different jobs over two different runs of cards. Everything here is the part that can be decided
 * without a browser or a model: what a skill *is* (its source and its prompt, read back from the card's
 * own fields), and what the model is *shown* (the section's cards, or the pages behind their links).
 * Asking the model, and fetching those pages, is `runSkill` in the jsMain half — this is common code
 * with tests under it, because the shape of the context is the whole difference between a useful answer
 * and a confident wrong one.
 *
 * The context is bounded the same way the tab triage's prompts are (see `TabTriage`): a section or a
 * feed can be any size, and a model has a context that is not, so what is quoted grows with a budget
 * rather than with the section. What does not fit is left out — the answer is then about less, never
 * about something that overran the window.
 */

/**
 * Where a skill gets what the model reads.
 *
 *  - [SECTION] is the section the skill sits in, as it stands — the titles, links and note bodies of
 *    the cards under its own divider. It needs nothing but the app itself, so it works in the web app
 *    too, and nothing leaves the machine.
 *  - [FETCH] is the *pages behind* that section's links: their text, fetched and handed to the model.
 *    This is the one that makes "summarise these articles" real — and the one that needs the host
 *    permission to reach them, so it is offered only where a [stramus.core.platform.ContentFetch] is
 *    present (the extension), and falls back to nothing where it is not.
 */
enum class SkillSource(val id: String) {
    SECTION("section"),
    FETCH("fetch"),
    ;

    companion object {
        fun from(id: String?): SkillSource = entries.firstOrNull { it.id == id } ?: SECTION
    }
}

/** A skill, as read back from its card: what it runs over, and what it tells the model to do. */
data class SkillDef(val source: SkillSource, val prompt: String)

/**
 * The `url` a skill card carries — `skill:<source>`. It is not a real address; it is where the source
 * is kept without a column of its own, and it is what [parseSkill] reads back. A client too old to know
 * the [CardKind.SKILL] kind shows it as a link to this string, which does nothing rather than something
 * wrong.
 */
fun skillUrl(source: SkillSource): String = "$SKILL_URL_PREFIX${source.id}"

private const val SKILL_URL_PREFIX = "skill:"

/**
 * The skill a card describes, or null if this card is not one. [Card.url] carries `skill:<source>` and
 * [Card.content] the prompt; a skill with no prompt is not a skill worth running, so it reads back null.
 */
fun parseSkill(card: Card): SkillDef? {
    if (card.kind != CardKind.SKILL) return null
    val source = card.url.removePrefix(SKILL_URL_PREFIX).takeIf { card.url.startsWith(SKILL_URL_PREFIX) }
    val prompt = card.content?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return SkillDef(SkillSource.from(source), prompt)
}

/** A page fetched for a [SkillSource.FETCH] skill: where it came from, and the readable text of it. */
data class FetchedDoc(val title: String, val url: String, val text: String)

/** The cards of the section the skill runs over — its own card-group, the skill card itself included. */
fun sectionCards(all: List<Card>, cardSectionId: Uuid?): List<Card> =
    all.filter { it.cardSectionId == cardSectionId }

/** Roughly how much of the source is described to the model — the budget the context is filled to. */
const val SKILL_CONTEXT_BUDGET: Int = 6000

/** How much of one fetched page is quoted. A whole article is more than the model needs to summarise it. */
const val FETCH_DOC_LIMIT: Int = 2000

/** How many of a collection's links are fetched at all — a feed of hundreds is not a skill's whole job. */
const val FETCH_MAX_DOCS: Int = 12

/** A note's body is quoted this far: the point of the note, not the whole of it. */
private const val NOTE_SAMPLE_LIMIT: Int = 300

/**
 * The links in [cards] — the cards of one section — in the order they sit, deduped by address: what a
 * [SkillSource.FETCH] skill fetches. Only [CardKind.LINK] cards have a page behind them; a note or a
 * file is skipped, and so is the skill card itself. At most [FETCH_MAX_DOCS] of them, because the
 * budget is spent on reading a few pages well rather than glancing at many.
 */
fun linksToFetch(cards: List<Card>): List<String> =
    cards.asSequence()
        .filter { it.kind == CardKind.LINK && it.url.startsWith("http") }
        .map { it.url }
        .distinct()
        .take(FETCH_MAX_DOCS)
        .toList()

/**
 * The section as the model reads it: one line per card of this card-group — its title, and where it
 * points or what it says. Skill cards are left out (a skill does not describe itself), and the whole is
 * held to [SKILL_CONTEXT_BUDGET] so a large section does not overrun the model's context.
 */
fun sectionContext(cards: List<Card>): String = buildString {
    for (card in cards) {
        if (card.kind == CardKind.SKILL) continue
        val line = buildString {
            append("- ").append(card.title.ifBlank { hostOf(card.url) })
            when (card.kind) {
                CardKind.LINK -> if (card.url.isNotBlank()) append(" — ").append(card.url)
                CardKind.NOTE -> card.content?.replace("\n", " ")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { append(": ").append(it.take(NOTE_SAMPLE_LIMIT)) }
                CardKind.FILE -> card.mime?.let { append(" (").append(it).append(')') }
                CardKind.SKILL -> {}
            }
            append('\n')
        }
        if (length + line.length > SKILL_CONTEXT_BUDGET) break
        append(line)
    }
}

/**
 * The fetched pages as the model reads them: each under its title and address, its text quoted to
 * [FETCH_DOC_LIMIT], the lot held to [SKILL_CONTEXT_BUDGET]. A page that came back empty carries no
 * text and so is not quoted — it is named, so the model knows it was there and could not be read.
 */
fun fetchedContext(docs: List<FetchedDoc>): String = buildString {
    for (doc in docs) {
        val body = doc.text.trim()
        val block = buildString {
            append("## ").append(doc.title.ifBlank { hostOf(doc.url) }).append('\n')
            append(doc.url).append('\n')
            if (body.isNotBlank()) append(body.take(FETCH_DOC_LIMIT)).append('\n')
            append('\n')
        }
        if (length + block.length > SKILL_CONTEXT_BUDGET) break
        append(block)
    }
}
