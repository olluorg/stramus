package stramus.web

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.browser.window
import stramus.core.platform.GoogleSignIn

/**
 * Signing in with Google from the web app, which has no `chrome.identity` and must do it the ordinary way:
 * a popup to Google, and a page of ours for Google to land on ([oauth.html]) that hands the token back to
 * the window that opened it.
 *
 * Two things here are load-bearing:
 *
 *  - The token comes back in the URL **fragment**, which browsers do not send to servers. It reaches this
 *    page and nowhere else — not our host, not GitHub Pages' logs.
 *  - The message from the popup is only believed if it came from **our own origin**. Any page on the
 *    internet may post a message to a window it can reach; without that check, one of them could hand us a
 *    token of its choosing, and we would sign the user into an account of its choosing.
 *
 * A popup the user closes resolves to null. That is not an error: they closed it.
 */
class WebGoogleSignIn(private val clientId: String) : GoogleSignIn {

    override suspend fun idToken(): String? = suspendCoroutine { continuation ->
        val redirectUri = window.location.origin + window.location.pathname.substringBeforeLast('/') + "/oauth.html"
        val nonce = randomNonce()

        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$clientId" +
            "&response_type=id_token" +
            "&redirect_uri=" + encodeURIComponent(redirectUri) +
            "&scope=" + encodeURIComponent("openid email") +
            "&nonce=$nonce" +
            // The account chooser, every time: on a shared machine, silently signing the second person in as
            // the first is a worse failure than one extra click.
            "&prompt=select_account"

        var settled = false
        var listener: ((dynamic) -> Unit)? = null
        var poll = 0

        fun settle(token: String?) {
            if (settled) return
            settled = true
            listener?.let { window.asDynamic().removeEventListener("message", it) }
            if (poll != 0) window.clearInterval(poll)
            continuation.resume(token)
        }

        listener = { event: dynamic ->
            // Anyone may post to this window. Only our own page is believed.
            if (event.origin == window.location.origin && event.data?.type == "stramus.google") {
                settle(event.data.idToken as? String)
            }
        }
        window.asDynamic().addEventListener("message", listener)

        val popup = window.open(url, "stramus-google", "width=480,height=640")
        if (popup == null) {
            // A blocked popup is the user's browser saying no, and there is nothing to wait for.
            settle(null)
        } else {
            // The popup can be closed without ever reaching Google's redirect, and then no message ever
            // comes. Watching for it to disappear is the only way to hear about that.
            poll = window.setInterval({
                if (popup.closed) settle(null)
            }, 500)
        }
    }
}

private fun randomNonce(): String = js(
    "Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('')",
) as String

private external fun encodeURIComponent(value: String): String
