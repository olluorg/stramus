package stramus.core.platform

/**
 * Getting an ID token out of Google — the one part of signing in with Google that the two hosts do
 * differently.
 *
 * The extension has `chrome.identity`, which opens the window, follows the redirect and hands back the URL;
 * the web app has none of that and has to open a popup and listen for it to talk back. Both end with the
 * same thing in hand: a token signed by Google, which the *server* then checks. Nothing here is trusted —
 * see the server's `Google.kt` for what is.
 *
 * Null when the user closed the window, or Google refused. That is not an error worth a dialog: they know
 * they closed it.
 */
fun interface GoogleSignIn {
    suspend fun idToken(): String?
}
