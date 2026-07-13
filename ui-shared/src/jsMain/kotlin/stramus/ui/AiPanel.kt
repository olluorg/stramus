package stramus.ui

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.AiSession
import web.cssom.ClassName

/** One exchange with the model: what was asked, and what has been written back so far. */
private data class Turn(val question: String, val answer: String)

/** A saved answer needs a name; the question is the obvious one, shortened to fit a card. */
private const val NOTE_TITLE_LIMIT = 60

external interface AiPanelProps : Props {
    var strings: Strings
    var assistant: AiAssistant

    /** The question just asked. A new one continues the same conversation. */
    var question: String

    /** What the model is told once, before the first question — see `aiSystemPrompt` in App. */
    var systemPrompt: String

    /** False in a read-only collection (or with none selected): there is nowhere to save an answer to. */
    var canSave: Boolean

    var onSaveNote: (String, String) -> Unit
    var onClose: () -> Unit
}

/**
 * The model's answer, in the content area, while the search box above stays a question box.
 *
 * The model is the browser's own and runs on this machine, so there is no key to configure and nothing
 * to send anywhere — but it is also a few hundred megabytes the browser fetches the first time anyone
 * asks it something, which is why the download has a progress bar of its own rather than a spinner
 * that appears to hang.
 *
 * The conversation is one session for as long as the panel is up: a second question is a follow-up,
 * and the model still has the first one in mind. An answer worth keeping becomes a note — the same
 * markdown card the user writes by hand — which is what makes this part of the collection rather than
 * a chat window that happens to sit here.
 */
val AiPanel = FC<AiPanelProps> { props ->
    val s = props.strings
    val session = useRef<AiSession>(null)

    var turns by useState<List<Turn>>(emptyList())
    var pending by useState<Turn?>(null)
    var downloading by useState<Double?>(null)
    var error by useState<String?>(null)

    // The session belongs to the panel, not to any one question: it is what makes the second question
    // a follow-up. It is given back when the panel goes away.
    useEffectOnce {
        try {
            awaitCancellation()
        } finally {
            session.current?.close()
            session.current = null
        }
    }

    // Each question is asked in its own coroutine, which React cancels when the next one arrives or the
    // panel closes — and a cancelled collection stops the model mid-sentence, which is exactly right:
    // nobody is waiting for the answer to a question they have already replaced.
    useEffect(props.question) {
        val question = props.question.trim()
        if (question.isBlank()) return@useEffect

        error = null
        pending = Turn(question, "")
        try {
            if (session.current == null) {
                if (props.assistant.availability() == AiAvailability.UNAVAILABLE) {
                    error = s.aiUnavailable
                    pending = null
                    return@useEffect
                }
                session.current = props.assistant.start(props.systemPrompt) { progress ->
                    downloading = progress
                }
                downloading = null
            }
            var answer = ""
            session.current?.ask(question)?.collect { text ->
                answer = text
                pending = Turn(question, text)
            }
            turns = turns + Turn(question, answer)
            pending = null
        } catch (e: Throwable) {
            error = e.message ?: s.aiFailed
            pending = null
            // A session that failed is not a session to ask the next question of.
            session.current?.close()
            session.current = null
        }
    }

    div {
        className = ClassName("ai-panel")
        div {
            className = ClassName("content-head")
            h2 {
                span { className = ClassName("ai-badge"); +s.aiChip }
                +s.aiHeading
            }
            div {
                className = ClassName("actions")
                button {
                    className = ClassName("btn")
                    onClick = { props.onClose() }
                    +s.aiClose
                }
            }
        }

        downloading?.let { progress ->
            div {
                className = ClassName("ai-download")
                +s.aiDownloading((progress * 100).toInt())
            }
        }
        error?.let { message ->
            div { className = ClassName("empty"); +message }
        }

        (turns + listOfNotNull(pending)).forEachIndexed { i, turn ->
            div {
                key = "$i:${turn.question}".unsafeCast<Key>()
                className = ClassName("ai-turn")
                div { className = ClassName("ai-question"); +turn.question }
                if (turn.answer.isBlank()) {
                    div { className = ClassName("ai-thinking"); +s.aiThinking }
                } else {
                    markdownBlock("ai-answer", turn.answer)
                    // Only a finished answer is offered up: half a thought is not worth a card, and
                    // copying one is a footgun.
                    if (turn !== pending) {
                        div {
                            className = ClassName("ai-turn-actions")
                            button {
                                className = ClassName("btn")
                                onClick = { copyToClipboard(turn.answer) }
                                +s.aiCopy
                            }
                            if (props.canSave) {
                                button {
                                    className = ClassName("btn")
                                    onClick = {
                                        props.onSaveNote(turn.question.take(NOTE_TITLE_LIMIT), turn.answer)
                                    }
                                    +s.aiSaveNote
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
