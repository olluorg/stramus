package stramus.web

import react.create
import react.dom.client.createRoot
import stramus.core.platform.builtInAi
import stramus.ui.App
import web.dom.ElementId
import web.dom.document

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    // Web app has no tab-capture capability; that action stays hidden. The built-in model is a
    // capability of the browser rather than of the extension, so it is offered here too — on the
    // browsers that have it, and nowhere else.
    createRoot(root).render(
        App.create {
            ai = builtInAi()
        },
    )
}
