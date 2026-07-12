package stramus.ext

import react.create
import react.dom.client.createRoot
import stramus.ui.App
import web.dom.ElementId
import web.dom.document

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    // The extension can read the user's open tabs and browsing history; wire both capabilities into
    // the shared UI — they are the two panes of its right sidebar.
    createRoot(root).render(
        App.create {
            tabCapture = ChromeTabCapture
            historyAccess = ChromeHistoryAccess
        },
    )
}
