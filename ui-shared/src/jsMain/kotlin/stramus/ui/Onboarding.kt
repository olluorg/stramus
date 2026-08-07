package stramus.ui

import react.ChildrenBuilder
import react.FC
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

/** One of the walkthrough's plain, non-CTA pages: a glyph, a headline, a line about what it means. */
private data class OnboardingPage(val glyphName: String, val title: String, val body: String)

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

/**
 * The walkthrough shown the first time stramus is opened — in the extension or on the web — and never
 * again after that, on whichever step it was closed on: what a collection is, how a tab becomes a card,
 * how the search box finds all of it.
 *
 * Only the first page differs by host, and it is the one thing worth saying before any of that. The web
 * app cannot read the browser's open tabs at all — that is the extension's alone — so its first page
 * points at installing it; the extension already has that, so its first page points at an account
 * instead. Signing in there ends the walkthrough on the spot: there is nothing left it would be telling
 * someone who just went and did the one thing it was about to suggest.
 *
 * Either page is a door, never a wall — skipping it costs nothing, and every page behind it works
 * exactly the same either way.
 */
val OnboardingModal = FC<OnboardingModalProps> { props ->
    val t = props.strings
    var step by useState(0)

    val pages = listOf(
        OnboardingPage("folder", t.onboardingOrganizeTitle, t.onboardingOrganizeBody),
        OnboardingPage("search", t.onboardingSearchTitle, t.onboardingSearchBody),
    )
    val lastStep = pages.size // step 0 is the host-specific CTA page; 1..lastStep are `pages`

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
            className = ClassName("onboarding-body")
            if (step == 0) {
                ctaPage()
            } else {
                val page = pages[step - 1]
                div { className = ClassName("onboarding-icon"); icon(page.glyphName) }
                h3 { +page.title }
                p { +page.body }
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
