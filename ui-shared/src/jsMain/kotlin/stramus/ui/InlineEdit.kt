package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLInputElement

/** How long a single click waits to see whether it is the first half of a double click. */
internal const val DOUBLE_CLICK_MS = 220

external interface InlineEditProps : Props {
    var initial: String
    var onCommit: (String) -> Unit
    var onCancel: () -> Unit
}

/**
 * Renames a title in place, without a modal. The field sits in a one-cell grid stacked on a hidden
 * copy of its own text, so it is exactly as wide as what it holds — the header keeps its size when
 * the edit starts and follows the text as it is typed, instead of the layout jumping.
 *
 * Enter or a click away commits; Escape reverts; a blank name is a revert. The header behind the
 * field toggles a section on click, so every click inside the field is stopped from reaching it.
 */
val InlineEdit = FC<InlineEditProps> { props ->
    var draft by useState(props.initial)
    val inputRef = useRef<HTMLInputElement>(null)
    // Enter commits and unmounts the field; the blur that may follow must not commit a second time.
    val settled = useRef(false)

    useEffectOnce {
        val el = inputRef.current
        el?.focus()
        el?.select()
    }

    fun commit() {
        if (settled.current == true) return
        settled.current = true
        val name = draft.trim()
        if (name.isEmpty() || name == props.initial) props.onCancel() else props.onCommit(name)
    }

    fun cancel() {
        if (settled.current == true) return
        settled.current = true
        props.onCancel()
    }

    span {
        className = ClassName("inline-edit")
        onClick = { it.stopPropagation() }
        onDoubleClick = { it.stopPropagation() }
        span { className = ClassName("inline-edit-sizer"); +draft }
        input {
            ref = inputRef
            className = ClassName("inline-edit-input")
            value = draft
            onChange = { e -> draft = e.target.value }
            onBlur = { commit() }
            onKeyDown = { e ->
                when (e.key) {
                    "Enter" -> { e.preventDefault(); commit() }
                    "Escape" -> { e.preventDefault(); cancel() }
                }
            }
        }
    }
}
