@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlinx.browser.localStorage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import stramus.core.db.StramusStore
import stramus.core.platform.GoogleSignIn
import stramus.core.sync.ApiException
import stramus.core.sync.StramusApi
import stramus.core.sync.SyncEngine

/**
 * Where the server lives.
 *
 * [DEFAULT_SERVER_URL] is baked in at build time from `STRAMUS_SERVER_URL` (see build.gradle.kts) — blank
 * for a developer's own build, the deployed address for CI's. `localStorage` overrides either one, which is
 * how the extension — which cannot be given an environment variable at runtime — is pointed somewhere else
 * without a rebuild.
 */
fun serverBaseUrl(): String =
    localStorage.getItem("stramus.server") ?: DEFAULT_SERVER_URL.ifBlank { "http://localhost:8090" }

/**
 * The OAuth client id of this application, as registered with Google — the same one the server checks the
 * token's audience against.
 *
 * Blank until someone registers one, and while it is blank the Google button is simply not there. A button
 * that opens Google and comes back with "invalid client" is worse than no button.
 */
fun googleClientId(): String = localStorage.getItem("stramus.googleClientId") ?: ""

// The wrappers' InputType is opaque; the app names the ones it uses the way the rest of the UI does.
private val EMAIL_INPUT: InputType = "email".unsafeCast<InputType>()
private val PASSWORD_INPUT: InputType = "password".unsafeCast<InputType>()
private val TEXT_INPUT: InputType = "text".unsafeCast<InputType>()

/** What the badge in the corner is saying. */
enum class SyncStatus { SIGNED_OUT, IDLE, RUNNING, OFFLINE, ERROR }

/**
 * Everything the UI knows about synchronisation. It is *reported*, never depended on: nothing the user
 * does waits for it, and an app whose badge says "waiting for the network" is an app that works exactly
 * as it did before there was a server.
 */
data class SyncUi(
    val status: SyncStatus = SyncStatus.SIGNED_OUT,
    val email: String? = null,
    /** Local time of the last run that got through, for the tooltip. */
    val syncedAt: String? = null,
    val error: String? = null,
    /** Notes that came back doubled because two devices edited them at once. Worth telling the user. */
    val conflictCopies: Int = 0,
)

/**
 * The small round thing in the toolbar. It says one word, and clicking it opens the account.
 *
 * It is deliberately quiet: a dot and a title, not a banner. Synchronisation working is the normal state
 * of the world and does not deserve the user's attention; synchronisation *failing* does not deserve much
 * either, because nothing has been lost — the work is on this machine, and it will go up when it can.
 */
external interface SyncBadgeProps : Props {
    var strings: Strings
    var state: SyncUi
    var onOpen: () -> Unit
}

val SyncBadge = FC<SyncBadgeProps> { props ->
    val t = props.strings
    val state = props.state

    val (glyph, text) = when (state.status) {
        SyncStatus.SIGNED_OUT -> "○" to t.syncSignedOut
        SyncStatus.IDLE -> "●" to (state.syncedAt?.let { t.syncedAt(it) } ?: t.syncIdle)
        SyncStatus.RUNNING -> "◍" to t.syncRunning
        SyncStatus.OFFLINE -> "◌" to t.syncOffline
        SyncStatus.ERROR -> "◍" to (state.error ?: t.syncOffline)
    }

    button {
        className = ClassName("btn sync-badge sync-${state.status.name.lowercase()}")
        hint(text)
        onClick = { props.onOpen() }
        span { +glyph }
    }
}

external interface AccountDialogProps : Props {
    var strings: Strings
    var state: SyncUi
    var api: StramusApi
    var engine: SyncEngine
    var store: StramusStore
    /** Null where there is no way to reach Google — then that door is not offered. */
    var google: GoogleSignIn?

    /**
     * The account this browser has already been signed into elsewhere — on the landing page, before the
     * database was open — when the dialog opens straight on the one question that sign-in could not answer
     * on its own. Null for a dialog the user opened themselves, which starts at the sign-in form.
     */
    var joinPrompt: Uuid?
    /** Run after anything that changes the database, so the app redraws what the sync brought in. */
    var onSynced: () -> Unit
    var onState: (SyncUi) -> Unit
    var onClose: () -> Unit
}

/**
 * Signing in, signing out, and the one question a second device has to be asked.
 *
 * Two doors, as the server has: a password, or a six-digit code on the mail. The code is offered first for
 * a new user — it is one field and no password to invent — and the password is there for anyone who wants
 * one.
 */
private val accountScope = MainScope()

val AccountDialog = FC<AccountDialogProps> { props ->
    val t = props.strings
    val scope = accountScope

    var email by useState("")
    var password by useState("")
    var code by useState("")
    var usingCode by useState(true)
    var codeSent by useState(false)
    var busy by useState(false)
    var error by useState<String?>(null)

    // Signing in on a browser that already has collections asks a question that has no safe default: are
    // these the account's, or is the account's what should be here? Nobody but the user knows. It may
    // already be waiting when the dialog opens: see [AccountDialogProps.joinPrompt].
    var joining by useState(props.joinPrompt)

    fun fail(e: Throwable) {
        error = (e as? ApiException)?.message ?: e.message ?: "…"
        busy = false
    }

    /** Sign the local database in and take the first run, which is where everything actually moves. */
    fun start(userId: Uuid, discardLocal: Boolean) {
        scope.launch {
            runCatching {
                props.engine.signIn(userId, props.api.deviceId, discardLocal)
                props.onState(SyncUi(SyncStatus.RUNNING, email))
                val result = props.engine.syncNow()
                props.onState(SyncUi(SyncStatus.IDLE, email, nowLocalTime(), conflictCopies = result?.conflictCopies ?: 0))
                props.onSynced()
                props.onClose()
            }.onFailure(::fail)
        }
    }

    /** After the server says who you are: does this browser's existing work join the account, or step aside? */
    suspend fun authenticated(userId: Uuid) {
        val hasLocalWork = props.store.collections.all().any { collection ->
            props.store.cards.count(collection.id) > 0
        }
        if (hasLocalWork) {
            joining = userId
            busy = false
        } else {
            start(userId, discardLocal = false)
        }
    }

    modalShell(props.onClose, "modal account-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +t.account }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        val choosing = joining
        if (choosing != null) {
            // The one screen in the app that cannot pick for you.
            p { +t.joinAccountTitle }
            p {
                className = ClassName("muted")
                +t.joinAccountHint
            }
            div {
                className = ClassName("row")
                button {
                    className = ClassName("btn primary")
                    onClick = { start(choosing, discardLocal = false) }
                    +t.joinAccountKeep
                }
                button {
                    className = ClassName("btn")
                    onClick = { start(choosing, discardLocal = true) }
                    +t.joinAccountDiscard
                }
            }
            return@modalShell
        }

        val signedInAs = props.state.email
        if (signedInAs != null && props.state.status != SyncStatus.SIGNED_OUT) {
            p { +signedInAs }
            props.state.syncedAt?.let { p { className = ClassName("muted"); +t.syncedAt(it) } }

            div {
                className = ClassName("row")
                button {
                    className = ClassName("btn")
                    disabled = busy
                    onClick = {
                        scope.launch {
                            props.onState(props.state.copy(status = SyncStatus.RUNNING))
                            runCatching { props.engine.syncNow() }
                                .onSuccess { result ->
                                    props.onState(
                                        props.state.copy(
                                            status = SyncStatus.IDLE,
                                            syncedAt = nowLocalTime(),
                                            conflictCopies = result?.conflictCopies ?: 0,
                                            error = null,
                                        ),
                                    )
                                    props.onSynced()
                                }
                                .onFailure { props.onState(props.state.copy(status = SyncStatus.ERROR, error = it.message)) }
                        }
                    }
                    +t.syncNow
                }
                button {
                    className = ClassName("btn")
                    onClick = {
                        scope.launch {
                            // The account is forgotten; the data is not. It was the user's before there
                            // was an account and it is theirs afterwards.
                            runCatching { props.api.signOut() }
                            props.engine.signOut()
                            props.onState(SyncUi(SyncStatus.SIGNED_OUT))
                            props.onClose()
                        }
                    }
                    +t.signOut
                }
            }

            p {
                className = ClassName("muted")
                +t.deleteAccountHint
            }
            button {
                className = ClassName("btn danger")
                onClick = {
                    if (confirmDialog(t.deleteAccountConfirm)) {
                        scope.launch {
                            runCatching { props.api.deleteAccount() }.onFailure(::fail)
                            props.engine.signOut()
                            props.onState(SyncUi(SyncStatus.SIGNED_OUT))
                            props.onClose()
                        }
                    }
                }
                +t.deleteAccount
            }
            return@modalShell
        }

        p {
            className = ClassName("muted")
            +t.accountSignedOutHint
        }

        props.google?.let { google ->
            button {
                className = ClassName("btn google")
                disabled = busy
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        // Null means the user closed Google's window. They know they did; there is nothing
                        // to tell them, and an error message here would be the app arguing with them.
                        val token = runCatching { google.idToken() }.getOrNull()
                        if (token == null) {
                            busy = false
                        } else {
                            runCatching { props.api.signInWithGoogle(token) }
                                .onSuccess { authenticated(Uuid.parse(it.userId)) }
                                .onFailure(::fail)
                        }
                    }
                }
                +t.signInWithGoogle
            }
        }

        fun proceed() {
            busy = true
            error = null
            scope.launch {
                runCatching {
                    val me = when {
                        usingCode && codeSent -> props.api.verifyCode(email.trim(), code.trim())
                        usingCode -> {
                            // The address may or may not have an account; the server answers the same
                            // either way, and the code that arrives makes one if it did not.
                            props.api.requestCode(email.trim())
                            codeSent = true
                            busy = false
                            null
                        }
                        else -> props.api.login(email.trim(), password)
                    }
                    me?.let { authenticated(Uuid.parse(it.userId)) }
                }.onFailure(::fail)
            }
        }

        label {
            +t.email
            input {
                type = EMAIL_INPUT
                value = email
                onChange = { e -> email = e.target.value }
            }
        }

        if (usingCode) {
            if (codeSent) {
                p { className = ClassName("muted"); +t.codeSent }
                label {
                    +t.codeFromEmail
                    input {
                        type = TEXT_INPUT
                        value = code
                        onChange = { e -> code = e.target.value }
                    }
                }
            }
        } else {
            label {
                +t.password
                input {
                    type = PASSWORD_INPUT
                    value = password
                    onChange = { e -> password = e.target.value }
                }
            }
        }

        error?.let { p { className = ClassName("error"); +it } }

        div {
            className = ClassName("modal-actions")
            button {
                className = ClassName("btn primary")
                disabled = busy
                onClick = { proceed() }
                +when {
                    usingCode && codeSent -> t.signIn
                    usingCode -> t.sendCode
                    else -> t.signIn
                }
            }
            if (!usingCode) {
                button {
                    className = ClassName("btn")
                    disabled = busy
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { props.api.register(email.trim(), password) }
                                .onSuccess { authenticated(Uuid.parse(it.userId)) }
                                .onFailure(::fail)
                        }
                    }
                    +t.signUp
                }
            }
        }

        button {
            className = ClassName("btn link")
            onClick = {
                usingCode = !usingCode
                codeSent = false
                error = null
            }
            +if (usingCode) t.signInWithPassword else t.signInWithCode
        }
    }
}

/** The wall clock, as the user reads it — the only thing the badge says about time. */
internal fun nowLocalTime(): String = js("new Date().toLocaleTimeString()") as String

private fun confirmDialog(message: String): Boolean = js("window.confirm(message)") as Boolean
