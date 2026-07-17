package stramus.ext

import kotlin.js.json
import kotlinx.coroutines.await
import stramus.core.platform.GoogleSignIn

/**
 * Signing in with Google from the extension.
 *
 * `chrome.identity.launchWebAuthFlow` is what an extension has instead of a popup: Chrome opens the window,
 * follows Google through it, and hands back the URL Google finally redirected to — from which the token is
 * read. The redirect goes to `https://<extension-id>.chromiumapp.org/`, a URL that exists only inside Chrome
 * and that no one but this extension can be redirected to, which is what makes the flow safe without a
 * server of ours in the middle of it.
 *
 * `response_type=id_token` because an ID token is all we want: it says who the person is, signed. We are not
 * asking for access to anything of theirs at Google, and so we ask for no access token and no refresh token
 * — there is nothing for us to do with them, and nothing for us to leak.
 */
class ChromeGoogleSignIn(private val clientId: String) : GoogleSignIn {

    override suspend fun idToken(): String? {
        val redirectUri = chrome.identity.getRedirectURL()
        val nonce = randomNonce()

        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$clientId" +
            "&response_type=id_token" +
            "&redirect_uri=${encodeURIComponent(redirectUri)}" +
            "&scope=${encodeURIComponent("openid email")}" +
            "&nonce=$nonce" +
            // The account chooser, every time. A shared machine where the second person is silently signed in
            // as the first is a worse failure than one extra click.
            "&prompt=select_account"

        val redirected = runCatching {
            chrome.identity.launchWebAuthFlow(json("url" to url, "interactive" to true)).await()
        }.getOrNull() ?: return null // the user closed the window; they know they did

        return redirected.substringAfter("id_token=", "").substringBefore('&').ifEmpty { null }
    }
}

private fun randomNonce(): String {
    val bytes = js("crypto.getRandomValues(new Uint8Array(16))")
    return js("Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')") as String
}

private external fun encodeURIComponent(value: String): String
