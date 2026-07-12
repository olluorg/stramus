package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType

/**
 * The section PIN lock, on screen: [LockScreen] is what a locked section shows instead of anything it
 * holds, [PinModal] is where its PIN is chosen, and [LockMenuModal] is what an already protected
 * section offers its owner.
 *
 * The lock is the UI's, not the database's: it keeps a section's collections unnamed in the sidebar
 * and their cards out of the grid, the global search and any export. The rows themselves stay
 * readable in the local database — see `stramus.core.crypto` for what that does and does not buy.
 */

/** Short enough to remember, long enough not to be guessed over a shoulder. */
private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12

// Like the file input in Modals.kt: the type is a value class over a string, and a cast sidesteps a
// fiddly member lookup.
private val PASSWORD_INPUT_TYPE: InputType = "password".unsafeCast<InputType>()

/** A PIN is digits only — the field simply refuses anything else rather than complaining after the fact. */
private fun pinOf(value: String): String = value.filter { it.isDigit() }.take(MAX_PIN_LENGTH)

external interface LockScreenProps : Props {
    var strings: Strings
    /** The section being unlocked — named, so it is clear which PIN is being asked for. */
    var sectionTitle: String
    /** Set after a wrong PIN; cleared by the next attempt. */
    var error: String?
    var onSubmit: (String) -> Unit
}

/** Stands in for a whole locked section: nothing it holds is drawn behind it. */
val LockScreen = FC<LockScreenProps> { props ->
    var pin by useState("")
    val s = props.strings

    fun submit() {
        if (pin.isNotBlank()) props.onSubmit(pin)
    }

    div {
        className = ClassName("lock-screen")
        div { className = ClassName("lock-icon"); +"🔒" }
        div { className = ClassName("lock-title"); +props.sectionTitle }
        p { className = ClassName("lock-hint"); +s.enterPinToView }
        input {
            className = ClassName("pin-input")
            type = PASSWORD_INPUT_TYPE
            placeholder = s.pinPlaceholder
            autoFocus = true
            value = pin
            onChange = { e -> pin = pinOf(e.target.value) }
            onKeyDown = { e -> if (e.key == "Enter") submit() }
        }
        div { className = ClassName("pin-error"); props.error?.let { +it } }
        button {
            className = ClassName("btn primary")
            disabled = pin.isBlank()
            onClick = { submit() }
            +s.unlock
        }
    }
}

external interface PinModalProps : Props {
    var strings: Strings
    /** true = the section already has a PIN and this one replaces it. */
    var change: Boolean
    var onSave: (String) -> Unit
    var onClose: () -> Unit
}

/**
 * Sets (or replaces) a section's PIN. Asked for twice: a typo here would lock the user out of their
 * own collections, and there is no one to reset it for them.
 */
val PinModal = FC<PinModalProps> { props ->
    var pin by useState("")
    var repeated by useState("")
    var error by useState<String?>(null)
    val s = props.strings

    fun save() {
        when {
            pin.length < MIN_PIN_LENGTH -> error = s.pinTooShort(MIN_PIN_LENGTH)
            pin != repeated -> error = s.pinMismatch
            else -> props.onSave(pin)
        }
    }

    modalShell(props.onClose, "modal pin-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +(if (props.change) s.changePinHeading else s.setPinHeading) }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }
        div {
            className = ClassName("pin-body")
            p { className = ClassName("lock-hint"); +s.pinNote }
            input {
                className = ClassName("pin-input")
                type = PASSWORD_INPUT_TYPE
                placeholder = s.newPinLabel
                autoFocus = true
                value = pin
                onChange = { e -> pin = pinOf(e.target.value); error = null }
            }
            input {
                className = ClassName("pin-input")
                type = PASSWORD_INPUT_TYPE
                placeholder = s.repeatPinLabel
                value = repeated
                onChange = { e -> repeated = pinOf(e.target.value); error = null }
                onKeyDown = { e -> if (e.key == "Enter") save() }
            }
            span { className = ClassName("pin-error"); error?.let { +it } }
        }
        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.cancel }
            button {
                className = ClassName("btn primary")
                onClick = { save() }
                +s.save
            }
        }
    }
}

external interface LockMenuModalProps : Props {
    var strings: Strings
    var sectionTitle: String
    /** Put the lock back on now, rather than waiting for the idle timer or a reload. */
    var onLockNow: () -> Unit
    var onChangePin: () -> Unit
    var onRemove: () -> Unit
    var onClose: () -> Unit
}

/**
 * What an unlocked-but-protected section offers: lock it again, change the PIN, or drop the
 * protection. Reached from the lock glyph in the sidebar, which is only there once a PIN is set.
 */
val LockMenuModal = FC<LockMenuModalProps> { props ->
    val s = props.strings

    modalShell(props.onClose, "modal pin-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.sectionProtection }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }
        div {
            className = ClassName("pin-body")
            div { className = ClassName("lock-title"); +props.sectionTitle }
            button {
                className = ClassName("btn primary lock-action")
                onClick = { props.onLockNow() }
                +s.lockNow
            }
            button {
                className = ClassName("btn lock-action")
                onClick = { props.onChangePin() }
                +s.changePin
            }
            button {
                className = ClassName("btn lock-action danger")
                onClick = { props.onRemove() }
                +s.removeProtection
            }
        }
        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
        }
    }
}
