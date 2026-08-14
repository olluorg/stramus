package stramus.ui

import react.ChildrenBuilder
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/** Kept in step with `version` in extension/src/jsMain/resources/manifest.json by hand — there is no
 *  build step that reads one into the other. */
const val APP_VERSION = "1.3.4"

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
            // The two libraries the collection-icon picker ships, named because their licences ask to
            // be: Lucide is ISC, Twemoji's graphics are CC-BY 4.0. Not translated — these are names and
            // licence tags, and they read the same in every language the app speaks.
            span {
                className = ClassName("about-credits")
                +"Icons: Lucide (ISC) · Emoji: Twemoji (CC-BY 4.0)"
            }
        }
    }
}
