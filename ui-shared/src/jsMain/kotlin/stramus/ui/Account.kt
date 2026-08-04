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
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
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
 * One door: Google. The password and the mailed code are switched off for now — on the server too, which is
 * where switching them off means anything (`ServerConfig.emailAuthEnabled`) — so this offers the one way in
 * that works rather than three fields ending in a refusal.
 */
private val accountScope = MainScope()

val AccountDialog = FC<AccountDialogProps> { props ->
    val t = props.strings
    val scope = accountScope

    // Not typed in any more — it comes back from the server with the session, and it is what the dialog
    // and the badge say afterwards about whose account this is.
    var email by useState("")
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

    /**
     * Sign the local database in and take the first run, which is where everything actually moves.
     *
     * The address is passed in rather than read out of [email]: a `useState` variable read in the same
     * closure that just set it is still the value this render was drawn with, and the account would end
     * up nameless in the badge.
     */
    fun start(userId: Uuid, address: String, discardLocal: Boolean) {
        scope.launch {
            runCatching {
                props.engine.signIn(userId, props.api.deviceId, discardLocal)
                props.onState(SyncUi(SyncStatus.RUNNING, address))
                val result = props.engine.syncNow()
                props.onState(SyncUi(SyncStatus.IDLE, address, nowLocalTime(), conflictCopies = result?.conflictCopies ?: 0))
                props.onSynced()
                props.onClose()
            }.onFailure(::fail)
        }
    }

    /** After the server says who you are: does this browser's existing work join the account, or step aside? */
    suspend fun authenticated(userId: Uuid, address: String) {
        val hasLocalWork = props.store.collections.all().any { collection ->
            props.store.cards.count(collection.id) > 0
        }
        if (hasLocalWork) {
            // The choice below is made in a later render, and [email] will have arrived by then.
            joining = userId
            busy = false
        } else {
            start(userId, address, discardLocal = false)
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
                    onClick = { start(choosing, email, discardLocal = false) }
                    +t.joinAccountKeep
                }
                button {
                    className = ClassName("btn")
                    onClick = { start(choosing, email, discardLocal = true) }
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

        val google = props.google
        if (google == null) {
            // No client id in this build, and Google is the only door there is at the moment: say so,
            // rather than showing a panel with nothing in it.
            p { className = ClassName("muted"); +t.signInUnavailable }
            return@modalShell
        }

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
                            .onSuccess { me ->
                                email = me.email
                                authenticated(Uuid.parse(me.userId), me.email)
                            }
                            .onFailure(::fail)
                    }
                }
            }
            +t.signInWithGoogle
        }

        error?.let { p { className = ClassName("error"); +it } }
    }
}

/** The wall clock, as the user reads it — the only thing the badge says about time. */
internal fun nowLocalTime(): String = js("new Date().toLocaleTimeString()") as String

private fun confirmDialog(message: String): Boolean = js("window.confirm(message)") as Boolean
