package stramus.ui

import react.ChildrenBuilder
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/** Kept in step with `version` in extension/src/jsMain/resources/manifest.json by hand — there is no
 *  build step that reads one into the other. */
const val APP_VERSION = "1.4.0"

private fun currentYear(): Int = js("new Date().getFullYear()") as Int

/** The About pane of the settings page: what this is, which build of it, and whose it is. */
internal fun ChildrenBuilder.aboutPane(s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.about }
        div {
            className = ClassName("about-pane")
            brandMark("brand-logo")
            span { className = ClassName("about-name"); +"stramus" }
            span { className = ClassName("about-version"); +s.aboutVersion(APP_VERSION) }
            span { className = ClassName("about-copyright"); +s.aboutCopyright(currentYear().toString()) }
            a {
                className = ClassName("about-link")
                href = "https://stramus.space/"
                // `target` is typed as a value class (`WindowTarget`) that kotlin-wrappers does not
                // expose a public constructor for; setting the raw string dynamically sidesteps that.
                asDynamic().target = "_blank"
                rel = "noopener"
                +s.aboutHomepage
            }
            // What the collection-icon picker is made of, named because the licences ask to be. Not
            // translated — these are names and licence tags, and they read the same in every language
            // the app speaks.
            //
            // CC-BY asks for three things and not one: the author, the licence *as a link*, and a word
            // on whether the material was changed. It was — `tools/icon-data/generate.py` keeps each
            // drawing exactly as Twemoji drew it and throws away the `<svg>` around it, `Icon.kt`
            // supplying one of its own — so the note says so rather than leaving a reader to assume
            // these are the files as published. The keywords the picker searches by are a third party
            // again, and their own licence (Unicode's) asks for a notice too.
            span {
                className = ClassName("about-credits")
                +"Icons: Lucide (ISC), © Lucide Contributors. Emoji: Twemoji, © Twitter and "
                +"contributors, graphics licensed "
                a {
                    href = "https://creativecommons.org/licenses/by/4.0/"
                    asDynamic().target = "_blank"
                    rel = "noopener"
                    +"CC-BY 4.0"
                }
                +" — each drawing as published, rewrapped in this app's own SVG. Emoji keywords: "
                +"Unicode CLDR, by way of emojibase."
            }
        }
    }
}
