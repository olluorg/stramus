package stramus.web

import react.create
import react.dom.client.createRoot
import stramus.ui.App
import web.dom.ElementId
import web.dom.document

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    // Web app has no tab-capture capability; that action stays hidden.
    createRoot(root).render(App.create())
}
