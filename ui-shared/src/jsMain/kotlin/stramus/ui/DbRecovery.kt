package stramus.ui

import io.github.kidx.DatabaseTooNewException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useMemo
import react.useState
import stramus.core.db.deleteStramusDatabase
import stramus.core.db.exportStramusBackup
import stramus.core.sync.StramusApi
import web.cssom.ClassName

/**
 * Set the moment the user asks for the database to be thrown away and fetched back from their account,
 * and read on the next start ([App]) — the database that comes up then is a first install as far as
 * anything else can tell, and the welcome note it seeds itself would otherwise be pushed up to an
 * account that already has years of collections. Cleared as soon as it has been acted on.
 */
internal const val RESTORE_FROM_SERVER_PREF = "restoreFromServer"

external interface DbRecoveryProps : Props {
    var strings: Strings

    /** What opening the database actually threw — shown as it is, under the explanation. */
    var failure: Throwable

    /** Asked whether this browser still holds a session, which decides if the server is a way back. */
    var api: StramusApi
}

/**
 * The screen for the one failure the app cannot work around: the local database would not open.
 *
 * It exists because the alternative was what used to happen — the coroutine that opens the store threw,
 * nothing caught it, and the app sat there showing the sidebar it had painted from its cache, with every
 * click doing nothing and no word about why. The database not opening is rare and is nobody's fault, but
 * it must never be *silent*, and the user must never be left with only "clear site data" as a way out.
 *
 * So it says what happened and offers, in the order a person actually wants them:
 *
 *  - a backup, taken underneath kidx and so still possible when kidx itself will not open the database
 *    (see `Backup.kt`) — always first, because everything below it is irreversible;
 *  - the account, if this browser still holds a session: the copy on the server is *already* a backup,
 *    and a database thrown away here comes back on the first sync of the next start;
 *  - starting over, for a browser with no account, which is the honest last resort — with the backup
 *    above and the settings page's import as the way back in.
 *
 * The commonest cause by far, and the reason this exists at all, is a database written by a *newer*
 * build than the one running: another profile, an update rolled back, or a developer moving between
 * branches. IndexedDB does not downgrade, and nothing in the app can make it.
 */
val DbRecovery = FC<DbRecoveryProps> { props ->
    val t = props.strings
    val scope = useMemo { MainScope() }

    // The address of the session this browser still holds, if any: the account is the one remedy here
    // that loses nothing, so it is looked for before anything is offered.
    var email by useState<String?>(null)
    var busy by useState(false)
    var note by useState<String?>(null)
    var error by useState<String?>(null)

    useEffectOnce {
        scope.launch { email = runCatching { props.api.resume() }.getOrNull()?.email }
    }

    /**
     * Delete the database and start the page over. Bounded, because a delete is blocked for as long as
     * any other tab holds the database open and would otherwise leave a button spinning for ever — the
     * remedy for that is the user's ("close the others"), so it has to be said rather than waited on.
     */
    fun dropAndReload(restoreFromServer: Boolean) {
        scope.launch {
            busy = true
            error = null
            val dropped = withTimeoutOrNull(4_000) {
                runCatching { deleteStramusDatabase() }.isSuccess
            } == true
            if (dropped) {
                if (restoreFromServer) prefSet(RESTORE_FROM_SERVER_PREF, "1")
                reloadPage()
            } else {
                error = t.dbFailureBlocked
                busy = false
            }
        }
    }

    // No close: there is nothing behind this to go back to, and the Escape key must not leave the user
    // in an app whose every action would fail.
    modalShell({}, "modal account-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +t.dbFailureTitle }
        }

        p { +if (props.failure is DatabaseTooNewException) t.dbFailureTooNew else t.dbFailureGeneric }
        props.failure.message?.let { p { className = ClassName("muted"); +it } }

        div {
            className = ClassName("row")
            button {
                className = ClassName("btn primary")
                disabled = busy
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        note = null
                        runCatching { downloadLargeFile("stramus-backup.json", "application/json", exportStramusBackup()) }
                            .onSuccess { note = t.dbFailureBackupDone }
                            .onFailure { error = t.dbFailureBackupFailed }
                        busy = false
                    }
                }
                +t.dbFailureBackup
            }
            button {
                className = ClassName("btn")
                disabled = busy
                onClick = { reloadPage() }
                +t.dbFailureReload
            }
        }

        note?.let { p { className = ClassName("muted"); +it } }
        error?.let { p { className = ClassName("error"); +it } }

        val signedInAs = email
        if (signedInAs != null) {
            p { className = ClassName("muted"); +t.dbFailureRestoreServerHint(signedInAs) }
            button {
                className = ClassName("btn danger")
                disabled = busy
                onClick = {
                    if (confirmDialog(t.dbFailureRestoreServerConfirm)) dropAndReload(restoreFromServer = true)
                }
                +t.dbFailureRestoreServer
            }
        } else {
            p { className = ClassName("muted"); +t.dbFailureResetHint }
            button {
                className = ClassName("btn danger")
                disabled = busy
                onClick = {
                    if (confirmDialog(t.dbFailureResetConfirm)) dropAndReload(restoreFromServer = false)
                }
                +t.dbFailureReset
            }
        }
    }
}
