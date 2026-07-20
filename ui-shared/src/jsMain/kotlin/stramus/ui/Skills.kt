@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import stramus.core.ai.SkillDef
import stramus.core.ai.SkillSource
import stramus.core.ai.SkillStep
import stramus.core.ai.parseSkill
import stramus.core.ai.runSkill
import stramus.core.model.Card
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.ContentFetch
import web.cssom.ClassName
import web.html.InputType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

// The wrappers' InputType is opaque; named here the way `App` names the checkbox it uses.
private val RADIO_INPUT: InputType = "radio".unsafeCast<InputType>()

external interface SkillEditorProps : Props {
    var strings: Strings

    /** The skill being edited, or null for a new one. */
    var existing: Card?

    /**
     * Whether this host can fetch the pages behind links (the extension can, the web app cannot). The
     * "read the pages" source is offered only where it can actually run — elsewhere it is shown, greyed,
     * so the user knows the skill exists but not here.
     */
    var fetchAvailable: Boolean
    var onSave: (title: String, source: SkillSource, prompt: String) -> Unit
    var onClose: () -> Unit
}

/**
 * The box a skill is written in: a name, what it runs over, and the prompt it runs. A skill is an
 * ordinary card once saved, so this is the only place its two hidden fields — its source and its
 * prompt — are ever edited; everything else about it (its place, its order) is edited the way any
 * card's is.
 */
val SkillEditor = FC<SkillEditorProps> { props ->
    val s = props.strings
    val existingDef: SkillDef? = props.existing?.let(::parseSkill)

    var title by useState(props.existing?.title ?: "")
    var source by useState(existingDef?.source ?: SkillSource.SECTION)
    var prompt by useState(existingDef?.prompt ?: s.skillDefaultPrompt)

    fun save() {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        // An unnamed skill still needs a title on its card; the placeholder is a reasonable one.
        props.onSave(title.trim().ifBlank { s.skillTitlePlaceholder }, source, cleanPrompt)
    }

    modalShell(props.onClose, "modal skill-editor") {
        div {
            className = ClassName("modal-head")
            h3 {
                className = ClassName("ai-title")
                span { className = ClassName("ai-badge"); +s.aiChip }
                +(if (props.existing != null) s.skillEdit else s.skillNew)
            }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("skill-field")
            label { className = ClassName("skill-label"); +s.skillTitleLabel }
            input {
                className = ClassName("skill-input")
                value = title
                placeholder = s.skillTitlePlaceholder
                onChange = { e -> title = e.target.value }
            }
        }

        div {
            className = ClassName("skill-field")
            label { className = ClassName("skill-label"); +s.skillSourceLabel }
            sourceOption(
                selected = source == SkillSource.SECTION,
                enabled = true,
                title = s.skillSourceSection,
                hint = s.skillSourceSectionHint,
                onPick = { source = SkillSource.SECTION },
            )
            sourceOption(
                selected = source == SkillSource.FETCH,
                enabled = props.fetchAvailable,
                title = s.skillSourceFetch,
                hint = if (props.fetchAvailable) s.skillSourceFetchHint else s.skillSourceFetchUnavailable,
                onPick = { if (props.fetchAvailable) source = SkillSource.FETCH },
            )
        }

        div {
            className = ClassName("skill-field")
            label { className = ClassName("skill-label"); +s.skillPromptLabel }
            textarea {
                className = ClassName("skill-prompt")
                value = prompt
                placeholder = s.skillPromptPlaceholder
                rows = 4
                onChange = { e -> prompt = e.target.value }
            }
        }

        div {
            className = ClassName("modal-actions")
            button {
                className = ClassName("btn primary")
                disabled = prompt.isBlank()
                onClick = { save() }
                +s.skillSave
            }
        }
    }
}

/** One "runs over" choice: a radio, its name and a line on what it means (or why it is unavailable). */
private fun react.ChildrenBuilder.sourceOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    hint: String,
    onPick: () -> Unit,
) {
    label {
        className = ClassName(if (enabled) "skill-source" else "skill-source disabled")
        input {
            type = RADIO_INPUT
            checked = selected
            disabled = !enabled
            onChange = { onPick() }
        }
        div {
            className = ClassName("skill-source-text")
            div { className = ClassName("skill-source-title"); +title }
            div { className = ClassName("skill-source-hint"); +hint }
        }
    }
}

external interface SkillRunProps : Props {
    var strings: Strings
    var assistant: AiAssistant
    var def: SkillDef

    /** The skill's name, for the window's heading. */
    var title: String

    /** The cards of the collection the skill runs over — its source, whichever kind. */
    var cards: List<Card>

    /** Present in the extension; null on the web app, where a fetching skill falls back to the collection. */
    var contentFetch: ContentFetch?

    /** What the model is framed with before the skill's own prompt — see `App.skillSystemPrompt`. */
    var systemPrompt: String
    var onClose: () -> Unit
}

/**
 * A skill running, in a window over the collection. It reads the pages (if it fetches), then streams the
 * answer the same way the chat does — and that is all: the answer is shown and forgotten, there being
 * nowhere it was asked to be kept. Closing the window cancels the run, mid-fetch or mid-sentence.
 */
val SkillRun = FC<SkillRunProps> { props ->
    val s = props.strings

    var answer by useState("")
    var fetching by useState<Pair<Int, Int>?>(null)
    var downloading by useState<Double?>(null)
    var error by useState<String?>(null)
    var done by useState(false)

    // The run lives for as long as the window is up; React cancels this coroutine when it closes, which
    // stops the fetch or the generation — nobody is waiting for an answer they have walked away from.
    react.useEffectOnce {
        try {
            if (props.assistant.availability() == AiAvailability.UNAVAILABLE) {
                error = s.aiUnavailable
            } else {
                runSkill(props.assistant, props.def, props.cards, props.contentFetch, props.systemPrompt) { progress ->
                    downloading = progress
                }.collect { step ->
                    when (step) {
                        is SkillStep.Fetching -> {
                            downloading = null
                            fetching = step.done to step.total
                        }
                        is SkillStep.Answer -> {
                            downloading = null
                            fetching = null
                            answer = step.text
                        }
                    }
                }
                done = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: s.aiFailed
        }
    }

    modalShell(props.onClose, "modal ai-modal") {
        div {
            className = ClassName("modal-head")
            h3 {
                className = ClassName("ai-title")
                span { className = ClassName("ai-badge"); +s.aiChip }
                +props.title
            }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("ai-log")

            downloading?.let { progress ->
                div { className = ClassName("ai-download"); +s.aiDownloading((progress * 100).toInt()) }
            }
            fetching?.let { (fetchDone, total) ->
                div { className = ClassName("ai-thinking"); +s.skillFetching(fetchDone, total) }
            }
            error?.let { message ->
                div { className = ClassName("empty"); +message }
            }

            if (answer.isBlank() && error == null && fetching == null && downloading == null) {
                div { className = ClassName("ai-thinking"); +s.aiThinking }
            } else if (answer.isNotBlank()) {
                div {
                    className = ClassName("ai-turn")
                    markdownBlock("ai-answer", answer)
                    // Copyable only once the answer is whole: half a thought is not worth copying.
                    if (done) {
                        div {
                            className = ClassName("ai-turn-tools")
                            button {
                                className = ClassName("icon ai-tool")
                                hint(s.aiCopy)
                                onClick = { copyToClipboard(answer) }
                                +"⧉"
                            }
                        }
                    }
                }
            }
        }
    }
}
