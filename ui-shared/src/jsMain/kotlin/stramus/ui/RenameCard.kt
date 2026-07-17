package stramus.ui

import kotlinx.coroutines.flow.lastOrNull
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import web.cssom.ClassName
import web.html.HTMLInputElement
import kotlin.coroutines.cancellation.CancellationException

/**
 * A title the model has written back is a title, not an essay: anything longer than this is the model
 * having answered some other question, and is thrown away.
 */
private const val CLEAN_TITLE_LIMIT = 120

/**
 * Ask the browser's own model for [title] with the rubbish taken out of it — the site's name trailing
 * after a dash, the "(3)" of unread counters, the marketing tail. Null when there is nothing to offer:
 * no model, a model that would have to be downloaded first, an answer that did not survive
 * [cleanedTitle].
 *
 * The model is asked only when it is [AiAvailability.AVAILABLE] — already on this machine. A rename box
 * is no place to start a several-hundred-megabyte download the user did not ask for, so a model that is
 * merely *downloadable* is treated here as no model at all.
 *
 * The session lives for this one question and is given back immediately: there is no conversation here,
 * and a session left open holds the model.
 */
internal suspend fun cleanTitle(ai: AiAssistant, systemPrompt: String, title: String, url: String): String? {
    if (ai.availability() != AiAvailability.AVAILABLE) return null
    val session = ai.start(systemPrompt)
    return try {
        // `ask` streams the whole answer so far, so the last value is the finished one.
        val answer = session.ask(
            buildString {
                append("Title: ").append(title)
                if (url.isNotBlank()) append("\nURL: ").append(url)
            },
        ).lastOrNull()
        answer?.let { cleanedTitle(it, title) }
    } finally {
        session.close()
    }
}

/**
 * What the model wrote back, as a title — or null, if what it wrote is not one.
 *
 * A small model asked for a title will now and then answer with a sentence about the title, wrap it in
 * quotes or a code fence, translate it, or simply make something up. None of that may reach the field
 * the user is about to save, and none of it can be told apart by asking the model more nicely. So the
 * answer is *checked* instead, against the only thing that is certain about a cleaned-up title: it is
 * made of the words that were already there. Every word must occur in the original, and the whole must
 * be no longer than it — cleaning takes things out, it never puts anything in. An answer that fails is
 * not shown at all, which is why the failure of this feature is invisible rather than wrong.
 */
internal fun cleanedTitle(answer: String, original: String): String? {
    val line = answer.lineSequence()
        .map { it.trim().trim('`').trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return null
    val cleaned = line
        .removePrefix("Title:")
        .trim()
        .trim('"', '\'', '«', '»', '“', '”')
        .trim()
    if (cleaned.isBlank() || cleaned.length > CLEAN_TITLE_LIMIT) return null

    val source = original.lowercase()
    if (cleaned.length > original.length) return null
    // No word the original did not have. This is what rejects a translation, a paraphrase and an
    // apology alike, without having to recognise any of them.
    if (cleaned.split(' ').any { word -> word.isNotBlank() && !source.contains(word.lowercase()) }) return null

    // Nothing was taken out: there is nothing to suggest, and a row offering the title the user is
    // already looking at is noise.
    return cleaned.takeUnless { it == original.trim() }
}

external interface RenameCardProps : Props {
    var strings: Strings
    var card: Card

    /**
     * The browser's own model, where the machine has one *ready* — null otherwise, and then this is a
     * plain box with the title in it. It is passed regardless of who the user chose to answer questions
     * (see [AiProvider]): a title is cleaned here, on this machine, and never sent to a web chat.
     */
    var ai: AiAssistant?

    var onSave: (String) -> Unit
    var onClose: () -> Unit
}

/**
 * Renaming a card: the title in a field, and — for a link, where the browser has a model of its own —
 * the same title with the rubbish taken out of it, offered on a button.
 *
 * The suggestion is *offered*, never applied: page titles are what the page called itself, and the user
 * is the one who knows which half of "Kotlin coroutines — a guide | JetBrains Blog · 2024" they meant
 * to keep. It arrives a moment after the box opens (the model has to be asked), so the box is usable
 * from the first keystroke and the row simply appears under it, or — where the model has nothing to
 * take out, cannot answer, or is not there — never does.
 */
val RenameCardModal = FC<RenameCardProps> { props ->
    val s = props.strings
    val card = props.card
    // Not named `title`/`value`: inside an `input { }` builder those bind to the element's own
    // attributes, and the setter would then run during render.
    var draft by useState(card.title)
    var suggestion by useState<String?>(null)
    var cleaning by useState(false)
    // The address is asked for, not shown: a real one runs to tracking parameters and percent-escapes,
    // and a box for editing a title is not where a wall of that belongs.
    var urlShown by useState(false)
    val inputRef = useRef<HTMLInputElement>(null)

    // The whole title is selected, not merely reached: renaming is more often replacing than editing.
    useEffectOnce {
        inputRef.current?.focus()
        inputRef.current?.select()
    }

    // Only a link has a title somebody else wrote — a note's and a file's are the user's own, and there
    // is nothing in them to clean.
    val assistant = props.ai.takeIf { card.kind == CardKind.LINK && card.title.isNotBlank() }

    useEffect(assistant, card.id) {
        val ai = assistant ?: return@useEffect
        cleaning = true
        try {
            suggestion = cleanTitle(ai, s.aiTitleSystemPrompt, card.title, card.url)
        } catch (e: CancellationException) {
            throw e // the box was closed: nobody is waiting for this
        } catch (e: Throwable) {
            // A model that could not answer is not news the user asked for: the row simply never
            // appears, and the field is the plain rename box it would have been anyway.
            suggestion = null
        } finally {
            cleaning = false
        }
    }

    fun commit() {
        val renamed = draft.trim()
        if (renamed.isNotBlank()) props.onSave(renamed)
    }

    // Gone once taken: the row offers what the field does not already say.
    val offer = suggestion?.takeUnless { it == draft.trim() }

    modalShell(props.onClose, "modal rename-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.renameHeading }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }
        input {
            ref = inputRef
            className = ClassName("modal-title-input")
            placeholder = s.cardNamePrompt
            value = draft
            onChange = { e -> draft = e.target.value }
            onKeyDown = { e ->
                if (e.key == "Enter") {
                    e.preventDefault()
                    commit()
                }
            }
        }
        // The page the title names, for whoever needs it to judge the title — folded away until then.
        if (card.kind == CardKind.LINK && card.url.isNotBlank()) {
            button {
                className = ClassName("rename-url-toggle")
                onClick = { urlShown = !urlShown }
                +(if (urlShown) s.renameHideUrl else s.renameShowUrl)
            }
            if (urlShown) {
                div { className = ClassName("rename-url"); +card.url }
            }
        }
        when {
            cleaning -> div {
                className = ClassName("ai-suggest")
                span { className = ClassName("ai-badge"); +s.aiChip }
                span { className = ClassName("ai-suggest-wait"); +s.aiTitleCleaning }
            }
            offer != null -> div {
                className = ClassName("ai-suggest")
                span { className = ClassName("ai-badge"); +s.aiChip }
                span { className = ClassName("ai-suggest-text"); +offer }
                button {
                    className = ClassName("btn")
                    hint(s.aiTitleUseHint)
                    onClick = {
                        draft = offer
                        inputRef.current?.focus()
                    }
                    +s.aiTitleUse
                }
            }
        }
        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.cancel }
            button {
                className = ClassName("btn primary")
                disabled = draft.isBlank()
                onClick = { commit() }
                +s.save
            }
        }
    }
}
