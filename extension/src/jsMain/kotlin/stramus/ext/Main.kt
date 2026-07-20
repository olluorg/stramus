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
            // Reading the pages behind a collection's links, for a skill that summarises them. This is
            // the one capability that reaches the network, and the reason a fetching skill is offered
            // in the extension but not on the plain web page (which cannot read another origin).
            contentFetch = ChromeContentFetch
            // The user's own search engine, whichever it is: chrome.search asks the browser rather
            // than hardcoding one.
            webSearch = ChromeWebSearch
            // The browser's own favicon store, so that no icon service is ever told which hosts the
            // user keeps here — see [ChromeIcons].
            iconSources = ChromeIcons
            ai = builtInAi()
            // chrome.identity, but only where an OAuth client has actually been registered for this app.
            google = googleClientId().takeIf { it.isNotBlank() }?.let { ChromeGoogleSignIn(it) }
        },
    )
}
