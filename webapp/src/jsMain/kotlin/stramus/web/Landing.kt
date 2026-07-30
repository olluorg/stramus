package stramus.web

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.footer
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import stramus.core.platform.GoogleSignIn
import stramus.core.sync.ApiException
import stramus.core.sync.StramusApi
import stramus.ui.Lang
import stramus.ui.Strings
import web.cssom.ClassName
import web.html.InputType

/** Where the source and the extension live — the two links the page makes a promise about. */
private const val SOURCE_URL = "https://github.com/olluorg/stramus"

/**
 * The Web Store listing, not the GitHub release it used to be: a visitor who came here to get the
 * extension should end up on a page with an install button, not on a ZIP to be loaded unpacked. The id
 * is the store item's and is fixed for the life of the extension.
 */
private const val EXTENSION_URL = "https://chromewebstore.google.com/detail/accjfifjflckbinniekamhehcjdampjh"

// The wrappers' InputType is opaque; named here the way the account dialog names the same three.
private val EMAIL_INPUT: InputType = "email".unsafeCast<InputType>()
private val PASSWORD_INPUT: InputType = "password".unsafeCast<InputType>()
private val TEXT_INPUT: InputType = "text".unsafeCast<InputType>()

private val landingScope = MainScope()

external interface LandingProps : Props {
    /** The same client the app will be handed: whatever signs in here is signed in there. */
    var api: StramusApi

    /** Null when no Google client id is configured — then that door is not offered here either. */
    var google: GoogleSignIn?

    var lang: Lang
    var onLang: (Lang) -> Unit

    /** A session now exists. The app is what comes next; it picks the session up on its own. */
    var onSignedIn: () -> Unit

    /** No account, and none wanted. The app runs on the local database, exactly as it always has. */
    var onSkip: () -> Unit
}

/**
 * The page at the front door.
 *
 * It exists because the address is a public one and the app behind it is not self-explanatory: somebody
 * arriving at stramus.space has not installed anything and has not decided anything, and a wall of
 * somebody else's empty collections is no way to be met. So: what this is, what it does, and the two ways
 * in — with an account, or without one.
 *
 * The second one is not a lesser door. The app has never needed a server and does not need one now (see
 * `docs/sync-and-auth.md`); the account buys the same collections on a second machine, and nothing else.
 * A landing page that pretended otherwise would be lying about the product.
 */
val Landing = FC<LandingProps> { props ->
    val t: Strings = props.lang.strings
    val l = props.lang.landing

    // The session this browser may already hold — somebody signed in, went away, and came back to the
    // front page rather than to the app. Then there is nothing to fill in, only a door to walk through.
    var resumed by useState<String?>(null)
    useEffectOnce {
        if (props.api.hasSession()) {
            landingScope.launch {
                resumed = runCatching { props.api.resume() }.getOrNull()?.email
            }
        }
    }

    div {
        className = ClassName("lp")

        header {
            className = ClassName("lp-head")
            div {
                className = ClassName("lp-brand")
                img {
                    src = "logo-128.png"
                    alt = "stramus"
                }
                span { +"stramus" }
            }
            div {
                className = ClassName("lp-nav")
                a { href = EXTENSION_URL; +l.navExtension }
                a { href = SOURCE_URL; +l.navSource }
                a { href = "privacy.html"; +l.navPrivacy }
                div {
                    className = ClassName("lp-lang")
                    Lang.entries.forEach { lang ->
                        button {
                            className = ClassName(if (lang == props.lang) "lp-lang-on" else "")
                            onClick = { props.onLang(lang) }
                            +lang.id.uppercase()
                        }
                    }
                }
            }
        }

        section {
            className = ClassName("lp-hero")

            div {
                className = ClassName("lp-hero-text")
                h1 { +l.heroTitle }
                p { className = ClassName("lp-lead"); +l.heroLead }
                button {
                    className = ClassName("lp-cta")
                    onClick = { props.onSkip() }
                    +l.tryWithoutAccount
                }
                p { className = ClassName("lp-hint"); +l.tryWithoutAccountHint }
            }

            div {
                className = ClassName("lp-card")
                val email = resumed
                if (email != null) {
                    h2 { +l.signInTitle }
                    p { className = ClassName("lp-hint"); +l.signedInAs(email) }
                    button {
                        className = ClassName("lp-cta lp-primary")
                        onClick = { props.onSignedIn() }
                        +l.openApp
                    }
                } else {
                    h2 { +l.signInTitle }
                    p { className = ClassName("lp-hint"); +l.signInLead }
                    SignInForm {
                        api = props.api
                        google = props.google
                        strings = t
                        onSignedIn = props.onSignedIn
                    }
                }
            }
        }

        appPreview(l)

        section {
            className = ClassName("lp-features")
            h2 { +l.featuresTitle }
            div {
                className = ClassName("lp-grid")
                l.features.forEach { (title, body) ->
                    div {
                        className = ClassName("lp-feature")
                        h3 { +title }
                        p { +body }
                    }
                }
            }
        }

        section {
            className = ClassName("lp-band")
            div {
                className = ClassName("lp-band-item")
                h3 { +l.syncTitle }
                p { +l.syncLead }
            }
            div {
                className = ClassName("lp-band-item")
                h3 { +l.extensionTitle }
                p { +l.extensionLead }
                a { className = ClassName("lp-link"); href = EXTENSION_URL; +l.navExtension }
            }
        }

        footer {
            className = ClassName("lp-foot")
            span { +l.footerNote }
            div {
                className = ClassName("lp-nav")
                a { href = SOURCE_URL; +l.navSource }
                a { href = "privacy.html"; +l.navPrivacy }
            }
        }
    }
}

/**
 * The same two doors the account dialog has, on the page in front of the app: a one-time code on the mail,
 * or a password. The wording is the app's own ([Strings]) so that signing in here and signing in there are
 * plainly the same act.
 *
 * What it does *not* do is touch the database — there is none yet on this page. It gets a session, and the
 * app attaches that session to whatever database it finds when it opens (see `App.kt`), which is also where
 * the one question a second device has to be asked gets asked.
 */
private external interface SignInFormProps : Props {
    var api: StramusApi
    var google: GoogleSignIn?
    var strings: Strings
    var onSignedIn: () -> Unit
}

private val SignInForm = FC<SignInFormProps> { props ->
    val t = props.strings

    var email by useState("")
    var password by useState("")
    var code by useState("")
    var usingCode by useState(true)
    var codeSent by useState(false)
    var busy by useState(false)
    var error by useState<String?>(null)

    fun fail(e: Throwable) {
        error = (e as? ApiException)?.message ?: e.message ?: "…"
        busy = false
    }

    props.google?.let { google ->
        button {
            className = ClassName("lp-btn lp-google")
            disabled = busy
            onClick = {
                busy = true
                error = null
                landingScope.launch {
                    // Null means the user closed Google's window. They know they did.
                    val token = runCatching { google.idToken() }.getOrNull()
                    if (token == null) {
                        busy = false
                    } else {
                        runCatching { props.api.signInWithGoogle(token) }
                            .onSuccess { props.onSignedIn() }
                            .onFailure(::fail)
                    }
                }
            }
            +t.signInWithGoogle
        }
    }

    fun proceed() {
        busy = true
        error = null
        landingScope.launch {
            runCatching {
                val me = when {
                    usingCode && codeSent -> props.api.verifyCode(email.trim(), code.trim())
                    usingCode -> {
                        // The address may or may not have an account; the server answers the same either
                        // way, and the code that arrives makes one if it did not.
                        props.api.requestCode(email.trim())
                        codeSent = true
                        busy = false
                        null
                    }
                    else -> props.api.login(email.trim(), password)
                }
                if (me != null) props.onSignedIn()
            }.onFailure(::fail)
        }
    }

    label {
        +t.email
        input {
            type = EMAIL_INPUT
            value = email
            autoFocus = true
            onChange = { e -> email = e.target.value }
        }
    }

    if (usingCode) {
        if (codeSent) {
            p { className = ClassName("lp-hint"); +t.codeSent }
            label {
                +t.codeFromEmail
                input {
                    type = TEXT_INPUT
                    value = code
                    onChange = { e -> code = e.target.value }
                }
            }
        }
    } else {
        label {
            +t.password
            input {
                type = PASSWORD_INPUT
                value = password
                onChange = { e -> password = e.target.value }
            }
        }
    }

    error?.let { p { className = ClassName("lp-error"); +it } }

    div {
        className = ClassName("lp-row")
        button {
            className = ClassName("lp-btn lp-primary")
            disabled = busy
            onClick = { proceed() }
            +when {
                usingCode && codeSent -> t.signIn
                usingCode -> t.sendCode
                else -> t.signIn
            }
        }
        if (!usingCode) {
            button {
                className = ClassName("lp-btn")
                disabled = busy
                onClick = {
                    busy = true
                    error = null
                    landingScope.launch {
                        runCatching { props.api.register(email.trim(), password) }
                            .onSuccess { props.onSignedIn() }
                            .onFailure(::fail)
                    }
                }
                +t.signUp
            }
        }
    }

    button {
        className = ClassName("lp-btn lp-plain")
        onClick = {
            usingCode = !usingCode
            codeSent = false
            error = null
        }
        +if (usingCode) t.signInWithPassword else t.signInWithCode
    }
}

/**
 * A picture of the app, drawn rather than photographed: the sidebar with its sections, the search box over
 * everything, and a grid of cards. A screenshot would be a file to keep in step with the app and a
 * different file for the dark theme; this is neither — it is the app's own colours and the app's own shapes,
 * and it follows the theme because it is made of the same variables.
 */
private fun react.ChildrenBuilder.appPreview(l: LandingStrings) {
    section {
        className = ClassName("lp-shot")

        div {
            className = ClassName("lp-shot-side")
            div { className = ClassName("lp-shot-brand"); +"stramus" }
            l.previewSections.forEachIndexed { index, title ->
                div { className = ClassName("lp-shot-sec"); +title }
                // Two collections under each section, taken in order from the same list.
                l.previewCollections.drop(index * 2).take(2).forEachIndexed { position, name ->
                    div {
                        className = ClassName(if (index == 0 && position == 0) "lp-shot-col lp-shot-col-on" else "lp-shot-col")
                        +name
                    }
                }
            }
        }

        div {
            className = ClassName("lp-shot-main")
            div { className = ClassName("lp-shot-bar"); +l.previewSearch }
            div {
                className = ClassName("lp-shot-grid")
                l.previewCards.forEach { title ->
                    div {
                        className = ClassName("lp-shot-card")
                        span { className = ClassName("lp-shot-dot") }
                        span { +title }
                    }
                }
            }
        }
    }
}
