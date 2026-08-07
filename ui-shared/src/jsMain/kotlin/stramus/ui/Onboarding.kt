package stramus.ui

import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

/** Persisted the moment the onboarding modal is dismissed — by any door, on any step — so it never
 *  shows again on this browser. See [OnboardingModal]. */
internal const val ONBOARDING_SEEN_PREF = "onboardingSeen"

/** Where the extension listing lives — the door the web app's first page points at. */
private const val CHROME_STORE_URL =
    "https://chromewebstore.google.com/detail/stramus-%E2%80%94-tab-collections/accjfifjflckbinniekamhehcjdampjh"

external interface OnboardingModalProps : Props {
    var strings: Strings

    /** True in the extension (it alone can read the browser's tabs); false in the web app. Decides
     *  what the first page offers — the one thing that differs between the two hosts. */
    var isExtension: Boolean

    /** The first page's call to action in the extension. This modal never signs anyone in itself — it
     *  hands off to the real sign-in dialog ([AccountDialog]), which is what actually does that, join
     *  prompt and all. */
    var onSignIn: () -> Unit

    /** Dismiss for good, from any step: the ×, "Skip", and "Get started" all mean the same thing. */
    var onClose: () -> Unit
}

/** One skeleton bar standing in for a line of text — see the class comment on `.ob-skel` in
 *  index.html for why this is shapes and not a screenshot. [extra] adds a modifier ("sub", "title" …). */
private fun ChildrenBuilder.skel(extra: String? = null) {
    span { className = ClassName(if (extra != null) "ob-skel $extra" else "ob-skel") }
}

/**
 * The headline feature, demonstrated rather than described: a tab, nudging toward a collection that
 * lights up to meet it, on a loop — a tab becoming a card is the one gesture the rest of the app builds
 * on, so it is the one page that shows motion instead of only naming it. [dot] is the little handle
 * riding the tab's corner, there to read as "this is being picked up," not as a real cursor.
 */
private fun ChildrenBuilder.dragIllustration() {
    div {
        className = ClassName("onboarding-illus")
        // Collections on the left, the tab on the right, arrow pointing from one to the other — the
        // sidebar sits on the left in the app itself, and a mock-up that put it on the wrong side would
        // teach the opposite of the gesture it means to explain.
        div {
            className = ClassName("ob-sidebar")
            div { className = ClassName("ob-sidebar-sec"); skel() }
            div { className = ClassName("ob-sidebar-col"); skel() }
            div { className = ClassName("ob-sidebar-col ob-drop-target"); skel() }
        }
        div { className = ClassName("onboarding-arrow ob-drag-arrow"); icon("chevron-left") }
        div {
            className = ClassName("ob-tab-chip ob-drag-tab")
            div { className = ClassName("ob-tab-dot") }
            div {
                className = ClassName("ob-tab-lines")
                skel("title")
                skel("sub")
            }
            div { className = ClassName("ob-drag-cursor") }
        }
    }
}

/** The calmer, unanimated view of the same sidebar: a fuller set of sections and collections, one of
 *  them lit up as "the one you're in" rather than as a drop target — what the sidebar looks like once
 *  a few things have been saved into it. */
private fun ChildrenBuilder.sectionsIllustration() {
    div {
        className = ClassName("onboarding-illus")
        div {
            className = ClassName("ob-sidebar")
            div { className = ClassName("ob-sidebar-sec"); skel() }
            div { className = ClassName("ob-sidebar-col on"); skel() }
            div { className = ClassName("ob-sidebar-col"); skel() }
            div { className = ClassName("ob-sidebar-sec"); skel() }
            div { className = ClassName("ob-sidebar-col"); skel() }
            div { className = ClassName("ob-sidebar-col"); skel() }
        }
    }
}

/** A search bar and three kinds of result — a saved card, an open tab, a page from history — which is
 *  what one query over all of it looks like. */
private fun ChildrenBuilder.searchIllustration() {
    div {
        className = ClassName("onboarding-illus")
        div {
            className = ClassName("ob-search-wrap")
            div {
                className = ClassName("ob-search-bar")
                icon("search")
                skel()
            }
            div {
                className = ClassName("ob-search-row")
                icon("file-text")
                div { className = ClassName("ob-skel-group"); skel("title"); skel("sub") }
            }
            div {
                className = ClassName("ob-search-row")
                icon("layout")
                div { className = ClassName("ob-skel-group"); skel("title"); skel("sub") }
            }
            div {
                className = ClassName("ob-search-row dim")
                icon("clock")
                div { className = ClassName("ob-skel-group"); skel("title"); skel("sub") }
            }
        }
    }
}

/**
 * The walkthrough shown the first time stramus is opened — in the extension or on the web — and never
 * again after that, on whichever step it was closed on.
 *
 * Only the first page differs by host, and it is the one thing worth saying before any of that. The web
 * app cannot read the browser's open tabs at all — that is the extension's alone — so its first page
 * points at installing it; the extension already has that, so its first page points at an account
 * instead. Signing in there ends the walkthrough on the spot: there is nothing left it would be telling
 * someone who just went and did the one thing it was about to suggest.
 *
 * The three pages behind it lead with the one gesture the rest of the app is built on — a tab, dragged
 * onto a collection — animated rather than only described, before it steps back to how those collections
 * are organized and found again. `Landing.kt` draws the app itself to make its point; this borrows the
 * same trick at modal scale — bars and chips in the app's own colours, not a screenshot to keep in step
 * with every redesign.
 *
 * Either page is a door, never a wall — skipping it costs nothing, and every page behind it works
 * exactly the same either way.
 */
val OnboardingModal = FC<OnboardingModalProps> { props ->
    val t = props.strings
    var step by useState(0)
    val lastStep = 3 // 0 = the host-specific CTA page; 1 = drag; 2 = sections; 3 = search

    fun ChildrenBuilder.ctaPage() {
        if (props.isExtension) {
            div { className = ClassName("onboarding-icon"); icon("user") }
            h3 { +t.onboardingSignInTitle }
            p { +t.onboardingSignInBody }
            button {
                className = ClassName("btn primary onboarding-cta")
                onClick = { props.onSignIn() }
                +t.signInWithGoogle
            }
            button {
                className = ClassName("onboarding-skip")
                onClick = { step = 1 }
                +t.onboardingSkip
            }
        } else {
            div { className = ClassName("onboarding-icon"); icon("download") }
            h3 { +t.onboardingInstallTitle }
            p { +t.onboardingInstallBody }
            a {
                className = ClassName("btn primary onboarding-cta")
                href = CHROME_STORE_URL
                // See `About.kt`'s own use of this: `target` has no public constructor to set typed.
                asDynamic().target = "_blank"
                rel = "noopener"
                +t.onboardingInstallCta
            }
            button {
                className = ClassName("onboarding-skip")
                onClick = { step = 1 }
                +t.onboardingContinueInBrowser
            }
        }
    }

    modalShell(props.onClose, "modal onboarding-modal") {
        div {
            className = ClassName("modal-head")
            span { className = ClassName("onboarding-brand"); +"stramus" }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
        }

        div {
            // Keyed by step so React tears the div down and back up on every change, rather than
            // patching its children in place — which is what makes the fade-and-rise entrance
            // (`.onboarding-body`'s `animation` in index.html) replay on every page rather than once.
            key = step.toString().unsafeCast<Key>()
            className = ClassName("onboarding-body")
            when (step) {
                0 -> ctaPage()
                1 -> {
                    dragIllustration()
                    h3 { +t.onboardingOrganizeTitle }
                    p { +t.onboardingOrganizeBody }
                }
                2 -> {
                    sectionsIllustration()
                    h3 { +t.onboardingSectionsTitle }
                    p { +t.onboardingSectionsBody }
                }
                else -> {
                    searchIllustration()
                    h3 { +t.onboardingSearchTitle }
                    p { +t.onboardingSearchBody }
                }
            }
        }

        div {
            className = ClassName("onboarding-actions")
            div {
                className = ClassName("onboarding-dots")
                (0..lastStep).forEach { i ->
                    span { className = ClassName(if (i == step) "onboarding-dot active" else "onboarding-dot") }
                }
            }
            if (step > 0) {
                div {
                    className = ClassName("onboarding-nav")
                    button { className = ClassName("btn"); onClick = { step -= 1 }; +t.onboardingBack }
                    if (step < lastStep) {
                        button { className = ClassName("btn primary"); onClick = { step += 1 }; +t.onboardingNext }
                    } else {
                        button { className = ClassName("btn primary"); onClick = { props.onClose() }; +t.onboardingGetStarted }
                    }
                }
            }
        }
    }
}
