package stramus.core.platform

/**
 * Reading the text of a page the app does not own — the one capability a skill needs that the browser
 * does not hand a plain web page. The extension backs it with `fetch` over the host permission it was
 * granted; the web app has none (a page cannot read another origin), so it passes null and a skill that
 * would fetch is simply not offered there.
 *
 * This is the line the on-device story is drawn at: everything else a skill reads is already on the
 * machine, and this is the one thing that reaches out to the network. It is deliberately narrow — text
 * out, nothing in — so that what a skill can do with the web is *read* it, and the model that reads it
 * is still the browser's own.
 */
interface ContentFetch {
    /**
     * The readable text of [url] — an article stripped to its words, or a feed reduced to its entries —
     * or null if the page could not be read at all (the request failed, or the host refused it). Blank
     * text (a page that loaded but held nothing to read) is a valid answer, not a failure.
     */
    suspend fun fetch(url: String): String?
}
