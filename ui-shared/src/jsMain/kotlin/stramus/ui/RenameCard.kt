package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.model.Card
import stramus.core.model.CardKind
import web.cssom.ClassName
import web.html.HTMLInputElement

external interface RenameCardProps : Props {
    var strings: Strings
    var card: Card

    var onSave: (title: String, url: String) -> Unit
    var onClose: () -> Unit
}

/**
 * Editing a card: its title in a field and, for a link, its address — folded away behind a toggle
 * until asked for, since a real address runs to tracking parameters and percent-escapes and is not
 * something every rename needs to look at.
 */
val RenameCardModal = FC<RenameCardProps> { props ->
    val s = props.strings
    val card = props.card
    // Not named `title`/`value`: inside an `input { }` builder those bind to the element's own
    // attributes, and the setter would then run during render.
    var draft by useState(card.title)
    var urlDraft by useState(card.url)
    // The address is asked for, not shown: a real one runs to tracking parameters and percent-escapes,
    // and a box for editing a title is not where a wall of that belongs.
    var urlShown by useState(false)
    val inputRef = useRef<HTMLInputElement>(null)

    // The whole title is selected, not merely reached: renaming is more often replacing than editing.
    useEffectOnce {
        inputRef.current?.focus()
        inputRef.current?.select()
    }

    fun commit() {
        val renamed = draft.trim()
        if (renamed.isNotBlank()) props.onSave(renamed, urlDraft.trim())
    }

    modalShell(props.onClose, "modal rename-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.renameHeading }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
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
        if (card.kind == CardKind.LINK) {
            button {
                className = ClassName("rename-url-toggle")
                onClick = { urlShown = !urlShown }
                +(if (urlShown) s.renameHideUrl else s.renameShowUrl)
            }
            if (urlShown) {
                input {
                    className = ClassName("rename-url")
                    placeholder = s.renameUrlPrompt
                    value = urlDraft
                    onChange = { e -> urlDraft = e.target.value }
                    onKeyDown = { e ->
                        if (e.key == "Enter") {
                            e.preventDefault()
                            commit()
                        }
                    }
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
