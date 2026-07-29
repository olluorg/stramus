package stramus.web

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.w3c.dom.events.Event
import react.FC
import react.Props
import react.create
import react.dom.client.createRoot
import react.useEffect
import react.useEffectOnce
import react.useMemo
import react.useState
import stramus.core.platform.builtInAi
import stramus.core.sync.StramusApi
import stramus.ui.App
import stramus.ui.Lang
import stramus.ui.googleClientId
import stramus.ui.serverBaseUrl
import web.dom.ElementId
import web.dom.document
import kotlinx.browser.document as pageDocument

/**
 * Remembered once the front page has been through: whoever has been inside gets the app on the next
 * visit, signed in or not. The landing page is for people who have not arrived yet.
 */
private const val LANDING_SEEN = "stramus.landingSeen"

/** The way back to the front page from an app that has taken over the address. */
private const val ABOUT_HASH = "#about"

/**
 * Which of the two this visit is.
 *
 * `#about` is deliberate and wins over everything: it is how somebody who uses the app gets back to the
 * page that describes it. Otherwise a session — or a previous visit that ended in the app — means the
 * app, and only a genuine first arrival gets the landing page.
 */
private fun wantsLanding(api: StramusApi): Boolean = when {
    window.location.hash == ABOUT_HASH -> true
    api.hasSession() -> false
    else -> localStorage.getItem(LANDING_SEEN) != "1"
}

/**
 * The web app's root: the landing page, or the app.
 *
 * The two share one bundle and one address, so signing in on the front page and using the app are not two
 * places with two sessions — the [StramusApi] made here is the one the app is handed, and the app attaches
 * whatever session it finds to the local database on its own (see `App.kt`).
 */
val WebRoot = FC<Props> {
    // Made once, at the root, because both sides need it: the landing page signs in with it, and the app
    // syncs with it. The session itself lives in localStorage, so nothing is lost by them sharing.
    val api = useMemo { StramusApi(serverBaseUrl()) }

    // Only where somebody has registered an OAuth client for this app: a button that opens Google and comes
    // back with "invalid client" is worse than no button at all.
    val google = useMemo { googleClientId().takeIf { it.isNotBlank() }?.let { WebGoogleSignIn(it) } }

    var landing by useState(wantsLanding(api))
    var lang by useState(Lang.from(localStorage.getItem("lang")))

    // The app applies the saved theme itself, once it is up; the landing page is on screen before that and
    // would otherwise spend its first visit in the light theme regardless of what the user chose.
    useEffectOnce {
        pageDocument.documentElement?.setAttribute("data-theme", localStorage.getItem("theme") ?: "auto")
    }

    // The language switch on the landing page is the app's own setting, written where the app reads it —
    // choosing Russian here is not undone by the app coming up in English a moment later.
    useEffect(lang) {
        pageDocument.documentElement?.setAttribute("lang", lang.id)
        localStorage.setItem("lang", lang.id)
    }

    // Typing `#about` into the address bar of a page that is already open changes the hash and nothing
    // else — no load, no reload. Without this, the one way back to the front page would appear not to work.
    useEffectOnce {
        val onHashChange: (Event) -> Unit = { landing = wantsLanding(api) }
        window.addEventListener("hashchange", onHashChange)
        try {
            awaitCancellation()
        } finally {
            window.removeEventListener("hashchange", onHashChange)
        }
    }

    /** Into the app, by either door, and without the front page standing in the way again. */
    fun enter() {
        localStorage.setItem(LANDING_SEEN, "1")
        // `#about` is what brought this page up; left in place it would bring it up again on every reload.
        if (window.location.hash == ABOUT_HASH) {
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
        landing = false
    }

    if (landing) {
        Landing {
            this.api = api
            this.google = google
            this.lang = lang
            onLang = { lang = it }
            onSignedIn = { enter() }
            onSkip = { enter() }
        }
    } else {
        // The web app has no tab-capture capability; that action stays hidden. The built-in model is a
        // capability of the browser rather than of the extension, so it is offered here too — on the
        // browsers that have it, and nowhere else.
        App {
            ai = builtInAi()
            this.google = google
            this.api = api
        }
    }
}

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("#root element is missing")
    createRoot(root).render(WebRoot.create())
}
