package stramus.ui

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import react.FC
import react.Key
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
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.AiSession
import web.cssom.ClassName
import web.html.HTMLDivElement
import web.html.HTMLInputElement

/** One exchange with the model: what was asked, and what has been written back so far. */
private data class Turn(val question: String, val answer: String)

/**
 * A question on its way to the model. It is an object rather than the string itself so that asking
 * the same thing twice is twice — a repeated question is a new [Ask], and the effect below runs again.
 */
private data class Ask(val text: String)

/** A saved answer needs a name; the question is the obvious one, shortened to fit a card. */
private const val NOTE_TITLE_LIMIT = 60

external interface AiChatProps : Props {
    var strings: Strings
    var assistant: AiAssistant

    /** The question the search box was carrying when it opened this; follow-ups are typed in here. */
    var initialQuestion: String

    /** What the model is told once, before the first question — see `aiSystemPrompt` in App. */
    var systemPrompt: String

    /** False in a read-only collection (or with none selected): there is nowhere to save an answer to. */
    var canSave: Boolean

    var onSaveNote: (String, String) -> Unit
    var onClose: () -> Unit
}

/**
 * The conversation with the model: a window over the page, with the exchanges so far and a field to
 * ask the next question. The page underneath is left as it was — the collection the user was looking
 * at is still there when the window closes, which is also what the model was told about.
 *
 * The model is the browser's own and runs on this machine, so there is no key to configure and nothing
 * to send anywhere — but it is also a few hundred megabytes the browser fetches the first time anyone
 * asks it something, which is why the download has a progress bar of its own rather than a spinner
 * that appears to hang.
 *
 * The conversation is one session for as long as the window is up: a second question is a follow-up,
 * and the model still has the first one in mind. An answer worth keeping becomes a note — the same
 * markdown card the user writes by hand — which is what makes this part of the collection rather than
 * a chat window that happens to sit here.
 */
val AiChat = FC<AiChatProps> { props ->
    val s = props.strings
    val session = useRef<AiSession>(null)
    val logRef = useRef<HTMLDivElement>(null)
    val inputRef = useRef<HTMLInputElement>(null)

    // The question being answered right now; the one being typed is [draft].
    var ask by useState { props.initialQuestion.trim().takeIf { it.isNotBlank() }?.let(::Ask) }
    var draft by useState("")
    var turns by useState<List<Turn>>(emptyList())
    var pending by useState<Turn?>(null)
    var downloading by useState<Double?>(null)
    var error by useState<String?>(null)

    // The session belongs to the window, not to any one question: it is what makes the second question
    // a follow-up. It is given back when the window closes.
    useEffectOnce {
        val stopWatching = onKeyStroke { event ->
            if (event.key == "Escape") props.onClose()
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
            session.current?.close()
            session.current = null
        }
    }

    // Each question is asked in its own coroutine, which React cancels when the window closes — and a
    // cancelled collection stops the model mid-sentence, which is exactly right: nobody is waiting for
    // the answer to a question they have walked away from.
    useEffect(ask) {
        val question = ask?.text ?: return@useEffect

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

    // The answer is written a word at a time, and the newest words are the ones being read: the log
    // follows them down.
    useEffect(turns.size, pending) {
        val log = logRef.current ?: return@useEffect
        log.scrollTop = log.scrollHeight.toDouble()
    }

    // The field is closed while the model writes, and a disabled field is blurred by the browser. Focus
    // is taken back the moment it reopens, so a follow-up is typed rather than clicked for.
    useEffect(pending != null) {
        if (pending == null) inputRef.current?.focus()
    }

    fun submit() {
        val question = draft.trim()
        // One question at a time: the field is closed while the model is still writing.
        if (question.isBlank() || pending != null) return
        ask = Ask(question)
        draft = ""
    }

    modalShell(props.onClose, "modal ai-modal") {
        div {
            className = ClassName("modal-head")
            h3 {
                className = ClassName("ai-title")
                span { className = ClassName("ai-badge"); +s.aiChip }
                +s.aiHeading
            }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
        }

        div {
            className = ClassName("ai-log")
            ref = logRef

            downloading?.let { progress ->
                div {
                    className = ClassName("ai-download")
                    +s.aiDownloading((progress * 100).toInt())
                }
            }
            error?.let { message ->
                div { className = ClassName("empty"); +message }
            }
            if (turns.isEmpty() && pending == null && error == null) {
                div { className = ClassName("empty small"); +s.aiEmpty }
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
                            // These are the answer's own actions, so they sit in the answer's corner and
                            // say what they do in a tooltip — the width here belongs to the text.
                            div {
                                className = ClassName("ai-turn-tools")
                                button {
                                    className = ClassName("icon ai-tool")
                                    hint(s.aiCopy)
                                    onClick = { copyToClipboard(turn.answer) }
                                    icon("copy")
                                }
                                if (props.canSave) {
                                    button {
                                        className = ClassName("icon ai-tool")
                                        hint(s.aiSaveNote)
                                        onClick = {
                                            props.onSaveNote(turn.question.take(NOTE_TITLE_LIMIT), turn.answer)
                                        }
                                        icon("file-text")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        div {
            className = ClassName("modal-actions ai-ask")
            input {
                ref = inputRef
                className = ClassName("ai-input")
                placeholder = s.aiPlaceholder
                value = draft
                disabled = pending != null
                onChange = { e -> draft = e.target.value }
                onKeyDown = { e ->
                    if (e.key == "Enter") {
                        e.preventDefault()
                        submit()
                    }
                }
            }
            button {
                className = ClassName("btn primary")
                disabled = draft.isBlank() || pending != null
                onClick = { submit() }
                +s.aiSend
            }
        }
    }
}
