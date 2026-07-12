package stramus.ext

import react.create
import react.dom.client.createRoot
import stramus.ui.App
import web.dom.ElementId
import web.dom.document

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    // The extension can read the user's open tabs; wire that capability into the shared UI.
    createRoot(root).render(App.create { tabCapture = ChromeTabCapture })
}
