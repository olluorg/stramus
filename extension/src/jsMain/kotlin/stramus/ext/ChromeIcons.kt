package stramus.ext

import stramus.ui.IconSources

/** The global `encodeURIComponent`: the page URL travels as a query parameter and has to survive it. */
private external fun encodeURIComponent(s: String): String

/**
 * The size asked of the browser's favicon store. Icons are drawn small (16–20 CSS pixels), and 32
 * covers that on a retina screen; the store keeps its icons at multiples of 16.
 */
private const val ICON_SIZE = 32

/**
 * The icons of the pages this browser has already seen, read from the browser's own favicon store
 * (the `favicon` permission, served from `_favicon/` on the extension's origin).
 *
 * This is the whole reason the extension does not use the icon services the web app uses: asking
 * google.com or favicone.com for the icon of a host tells them the user has that host open or saved —
 * the browsing of a person who installed a tab manager, handed to a third party one request at a time.
 * The browser already has these icons, from the visits that put the pages in front of the user in the
 * first place. So it is asked instead, and nothing about what the user keeps here leaves the machine.
 *
 * A page the browser holds no icon for gets its default globe rather than nothing, so the chain ends
 * here: there is no second source, by design.
 */
internal val ChromeIcons = IconSources { pageUrl, _ ->
    listOf(chrome.runtime.getURL("/_favicon/?pageUrl=${encodeURIComponent(pageUrl)}&size=$ICON_SIZE"))
}
