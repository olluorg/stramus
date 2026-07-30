package stramus.ext

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import stramus.core.url.hostOf
import stramus.ui.IconSource
import stramus.ui.IconSourceKind
import stramus.ui.IconSources
import stramus.ui.faviconProxyUrl
import stramus.ui.readIconDataUri

/** The global `encodeURIComponent`: the page URL travels as a query parameter and has to survive it. */
private external fun encodeURIComponent(s: String): String

/**
 * The size asked of the browser's favicon store. Icons are drawn small (16–20 CSS pixels), and 32
 * covers that on a retina screen; the store keeps its icons at multiples of 16.
 */
private const val ICON_SIZE = 32

/** The address of the browser's own favicon store for [pageUrl]. */
private fun browserStoreUrl(pageUrl: String): String =
    chrome.runtime.getURL("/_favicon/?pageUrl=${encodeURIComponent(pageUrl)}&size=$ICON_SIZE")

/**
 * The icons of the pages this browser has already seen, read from the browser's own favicon store
 * (the `favicon` permission, served from `_favicon/` on the extension's origin) — and, for the pages it has
 * not seen, a chain that gets less private with every step and is walked no further than it has to be.
 *
 * The browser's store comes first because it is free and tells nobody anything: these icons are already
 * here, from the visits that put the pages in front of the user in the first place. A saved link to a site
 * this browser has visited therefore never causes a request at all.
 *
 * A site it has *not* visited is the case this chain exists for — a link imported from somewhere else, a tab
 * opened from a search, a collection restored on a new machine. The store has nothing for those, and the
 * question is who gets asked instead. Not google.com or favicone.com directly: asking them tells them the
 * user has that host saved, one request per host, which for a tab manager amounts to handing over the list.
 * Our own server is asked instead, and it asks them — so what they see is a server asking about a host, with
 * no way to tell whose link it was or whether anyone else asked for the same one.
 *
 * Only when that server cannot be reached at all does the chain fall through to the services directly. That
 * is the one case where a third party learns a host from the user's own address, and it is the price of the
 * alternative being a page of blank squares while the server is down.
 */
internal val ChromeIcons: IconSources = object : IconSources {

    override fun sourcesFor(pageUrl: String, stored: String?): List<IconSource> {
        val host = hostOf(pageUrl)
        return listOf(
            IconSource(browserStoreUrl(pageUrl)),
            IconSource(faviconProxyUrl(host), IconSourceKind.AUTHORITATIVE),
            IconSource("https://favicone.com/$host?s=64"),
            IconSource("https://www.google.com/s2/favicons?domain=$host&sz=64", IconSourceKind.DISPLAY_ONLY),
        )
    }

    /**
     * Chrome's favicon store answers for every page, whether it holds an icon for it or not: a page it has
     * never seen gets the browser's own grey document, under a perfectly successful status. Taken for an
     * icon, that document is cached for a month, drawn in place of the real one, and outlives the user
     * finally visiting the site — which is exactly what used to happen here.
     *
     * There is no flag on the response to tell the two apart, so the reply is compared against a known one:
     * [defaultIcon] is what the store hands back for a page that certainly cannot exist.
     */
    override fun isBlank(dataUri: String): Boolean = defaultIcon != null && dataUri == defaultIcon
}

/**
 * The browser's stand-in icon, learned once by asking about a page that cannot exist — `.invalid` is
 * reserved by RFC 2606 precisely so that nothing ever resolves there, and the store has certainly never
 * seen it.
 *
 * Null until the probe comes back, and null forever if it fails. Both mean the same thing to
 * [IconSources.isBlank]: nothing is filtered. That is the right way to be wrong — a stand-in drawn as an
 * icon is a cosmetic bug, while a real icon mistaken for a stand-in would throw away the very thing being
 * looked for.
 */
private var defaultIcon: String? = null

/**
 * Run the probe. Called once, from `Main`, before the first card is drawn — the check it feeds is used on
 * every fetched icon, and an answer that arrives late simply means the first few are not filtered.
 */
internal fun probeBrowserDefaultIcon() {
    MainScope().launch {
        defaultIcon = readIconDataUri(browserStoreUrl("https://probe-${js("Date.now()")}.invalid/"))
    }
}
