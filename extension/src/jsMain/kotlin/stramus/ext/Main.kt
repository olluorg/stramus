package stramus.ext

import react.create
import react.dom.client.createRoot
import stramus.core.platform.builtInAi
import stramus.ui.App
import stramus.ui.googleClientId
import web.dom.ElementId
import web.dom.document

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    // The extension can read the user's open tabs and browsing history; wire both capabilities into
    // the shared UI — they are the two panes of its right sidebar. The third capability, the browser's
    // own on-device model, is not the extension's to grant: it is there or it is not (`builtInAi()`),
    // and the search box offers to ask it only when it is.
    createRoot(root).render(
        App.create {
            tabCapture = ChromeTabCapture
            historyAccess = ChromeHistoryAccess
            // The user's own search engine, whichever it is: chrome.search asks the browser rather
            // than hardcoding one.
            webSearch = ChromeWebSearch
            // The browser's own favicon store, so that no icon service is ever told which hosts the
            // user keeps here — see [ChromeIcons].
            iconSources = ChromeIcons
            ai = builtInAi()
            // chrome.identity.getAuthToken needs no client id here — it is the one baked into the
            // manifest's own `oauth2` block. The Web application client id (possibly blank) is only for
            // the launchWebAuthFlow fallback; see ChromeGoogleSignIn.
            google = ChromeGoogleSignIn(googleClientId())
        },
    )
}
