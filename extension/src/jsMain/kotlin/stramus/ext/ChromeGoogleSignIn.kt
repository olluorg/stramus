package stramus.ext

import kotlin.js.json
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import stramus.core.platform.GoogleSignIn

/**
 * Signing in with Google from the extension — three attempts, quietest first.
 *
 * 1. `chrome.identity.getAuthToken`, silent: an access token for the account already signed into Chrome
 *    itself, using the `oauth2` client registered in the manifest. Nothing is ever drawn for this one, not
 *    even a hidden window — it either has an answer or it doesn't.
 * 2. `chrome.identity.launchWebAuthFlow`, silent: for the case Chrome's own sign-in can't answer (a work
 *    profile with `getAuthToken` disabled, say) but there is still a Google *web* session and prior
 *    consent. Chrome opens the window, follows Google through it and hands back the URL Google finally
 *    redirected to, but `interactive = false` means nothing is drawn unless it must be.
 * 3. `launchWebAuthFlow`, visible: first-ever consent, or nothing above found a session to answer with.
 *
 * The redirect for (2) and (3) goes to `https://<extension-id>.chromiumapp.org/`, a URL that exists only
 * inside Chrome and that no one but this extension can be redirected to — what makes the flow safe without
 * a server of ours in the middle of it.
 *
 * The two doors hand back different things — (1) an opaque access token, (2)/(3) a signed ID token — and
 * that is fine: the server checks each the way it must be checked (see `Google.kt`), and this interface
 * only promises "a token Google will vouch for," not which kind.
 *
 * **Not every browser has (1).** `getAuthToken` asks the *browser's own* signed-in account, and in Edge that
 * account is a Microsoft one: the call is not ours to make there, and it may fail, or answer nothing, or —
 * the reason for the timeout below — never answer at all. Whatever it does, (2) and (3) must still be
 * reached, because they are what actually works outside Chrome.
 *
 * Each step says what it did in the console (`[stramus:google]`). Three fallbacks that all end in a quiet
 * null are three ways for a button to do nothing, and "nothing happened" is not a thing anyone can debug.
 */
class ChromeGoogleSignIn(private val webClientId: String) : GoogleSignIn {

    override suspend fun idToken(): String? {
        attemptAuthToken()?.let {
            trace("getAuthToken answered; signing in with it")
            return it
        }
        // No Web application client id configured means (2) and (3) have no client to ask Google as —
        // there is nothing left to try.
        if (webClientId.isBlank()) {
            trace("no web client id in this build, so the window flow cannot be tried — giving up")
            return null
        }
        val redirectUri = chrome.identity.getRedirectURL()
        trace("falling back to the window flow, redirecting to $redirectUri")
        val silent = attemptFlow(redirectUri, interactive = false)
        if (silent != null) return silent
        return attemptFlow(redirectUri, interactive = true)
    }

    /**
     * Null covers "no `oauth2` client registered in the manifest", "this browser's identity is not Google's"
     * and "no session to answer silently with" — either way, the caller falls through to [attemptFlow].
     *
     * The timeout is not belt-and-braces: a promise that never settles would hang the whole sign-in here,
     * with no window ever opening and nothing on screen to say why.
     */
    private suspend fun attemptAuthToken(): String? {
        val result = withTimeoutOrNull(SILENT_TIMEOUT_MS) {
            runCatching { chrome.identity.getAuthToken(json("interactive" to false)).await() }
                .onFailure { trace("getAuthToken refused: ${it.message}") }
                .getOrNull()
        }
        if (result == null) trace("getAuthToken had nothing to give (refused, empty, or never answered)")
        return (result?.token as? String)?.ifEmpty { null }
    }

    private suspend fun attemptFlow(redirectUri: String, interactive: Boolean): String? {
        val nonce = randomNonce()
        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$webClientId" +
            "&response_type=id_token" +
            "&redirect_uri=${encodeURIComponent(redirectUri)}" +
            "&scope=${encodeURIComponent("openid email")}" +
            "&nonce=$nonce" +
            // The account chooser, but only on the visible attempt: a shared machine where the second person
            // is silently signed in as the first is a worse failure than one extra click. The silent attempt
            // never shows a chooser or anything else, so there is nothing to ask for there.
            (if (interactive) "&prompt=select_account" else "")

        // Null covers three different things — no session to answer silently with, the user closed the
        // window, Google refused — and none of them is an error worth a dialog: the caller just tries the
        // next thing, or gives up quietly. It is worth a line in the console, though: a redirect URI Google
        // does not recognise fails exactly here, and looks from the outside like nothing happening.
        val redirected = runCatching {
            chrome.identity.launchWebAuthFlow(json("url" to url, "interactive" to interactive)).await()
        }.onFailure {
            trace("launchWebAuthFlow (interactive=$interactive) ended with: ${it.message}")
        }.getOrNull() ?: return null

        val token = redirected.substringAfter("id_token=", "").substringBefore('&').ifEmpty { null }
        if (token == null) trace("Google redirected back without a token: $redirected")
        return token
    }
}

/** How long the silent, browser-account attempt is given before the visible flow takes over. */
private const val SILENT_TIMEOUT_MS = 4000L

private fun trace(message: String) {
    console.info("[stramus:google] $message")
}

private fun randomNonce(): String {
    val bytes = js("crypto.getRandomValues(new Uint8Array(16))")
    return js("Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')") as String
}

private external fun encodeURIComponent(value: String): String
