package stramus.ext

import kotlinx.coroutines.await
import stramus.core.platform.ContentFetch
import kotlin.js.Promise

/**
 * `fetch`-backed [ContentFetch]: reads a page over the extension's host permission and hands back its
 * words. Reached through `dynamic` rather than declared globals — the same reason as [stramus.core.
 * platform.builtInAi]'s file — but the real work here is the *extraction*, because a page's text is
 * mostly not its article: it is navigation, menus, footers, cookie notices and "related stories", and a
 * model handed all of that summarises the furniture along with the room.
 *
 * So a page is read the way a reader mode reads it. First the structured answer, where the site gives
 * one — schema.org's `articleBody`, which many news sites embed and which is the article exactly. Where
 * it does not, the page's own markup is used against it: the furniture is dropped, and then the densest
 * block of prose is found — the element under which the real paragraphs cluster — and its text is taken,
 * the rest of the page left behind. A feed (RSS/Atom) is simpler still: its entries already are the
 * text, so they are read straight off.
 *
 * Nothing here runs anything — `DOMParser.parseFromString` builds a tree without fetching subresources
 * or executing scripts, which is what makes it safe to point at a page the app did not write.
 */
object ChromeContentFetch : ContentFetch {

    override suspend fun fetch(url: String): String? = runCatching {
        val response = fetchApi()(url).unsafeCast<Promise<dynamic>>().await()
        if (response.ok != true) return@runCatching null
        val body = response.text().unsafeCast<Promise<String>>().await()
        val contentType = (response.headers.get("content-type") as? String).orEmpty()
        extractText(body, contentType)
    }.getOrNull()

    private fun extractText(body: String, contentType: String): String {
        val head = body.trimStart()
        // A feed announces itself in its content type or its root element; anything else is read as a
        // page. Misjudging it is cheap — an HTML parse of a feed still yields its text, just less tidily.
        val looksLikeFeed = contentType.contains("xml", ignoreCase = true) ||
            head.startsWith("<?xml") || head.contains("<rss", ignoreCase = true) ||
            head.contains("<feed", ignoreCase = true)
        val parser = domParser()
        return if (looksLikeFeed) {
            feedText(parser.parseFromString(body, "application/xml"))
        } else {
            htmlText(parser.parseFromString(body, "text/html"))
        }
    }

    /** A feed's entries — RSS `item` and Atom `entry` alike — as a title and a summary each. */
    private fun feedText(doc: dynamic): String {
        val items = queryAll(doc, "item, entry")
        return buildString {
            for (item in items.take(FEED_ITEMS)) {
                textOf(item, "title")?.let { append("• ").append(it).append('\n') }
                val summary = textOf(item, "description") ?: textOf(item, "summary") ?: textOf(item, "content")
                summary?.let { append(stripTags(it)).append("\n\n") }
            }
        }.trim()
    }

    /**
     * A page reduced to its article. In order of trust: the site's own `articleBody`; the densest block
     * of prose once the furniture is gone; and, if neither yields enough, the page's description and
     * every substantial paragraph on it. Each step is a weaker signal than the last, and the first that
     * gives a real answer wins.
     */
    private fun htmlText(doc: dynamic): String {
        jsonLdArticleBody(doc)?.let { return collapse(it) }

        removeNoise(doc)
        val root = bestContainer(doc) ?: doc.body ?: doc.documentElement
        val main = blockText(root)
        if (main.length >= MIN_ARTICLE) return main

        // Thin — a page that is mostly script, or a layout the density heuristic did not fit. Take the
        // description as a lead and every paragraph that reads like prose, which is more than the body's
        // raw text and less than its noise.
        val lead = metaDescription(doc)
        val paragraphs = paragraphText(doc)
        val assembled = listOfNotNull(lead, paragraphs.ifBlank { null }, main.ifBlank { null })
            .joinToString("\n\n")
        return assembled.ifBlank { collapse((root?.textContent as? String).orEmpty()) }
    }

    /** schema.org's own article text, if the page embeds it as JSON-LD — the article, exactly, no heuristic. */
    private fun jsonLdArticleBody(doc: dynamic): String? {
        for (script in queryAll(doc, "script[type='application/ld+json']")) {
            val raw = (script.textContent as? String) ?: continue
            val parsed = runCatching { JSON.parse<Any?>(raw) }.getOrNull() ?: continue
            articleBodyOf(parsed)?.let { return it }
        }
        return null
    }

    /** `articleBody` however the document nests it — a bare object, an array of them, or an `@graph`. */
    private fun articleBodyOf(node: dynamic): String? {
        // Loose null: for a `dynamic`, `== null` matches JavaScript's undefined too — an absent `@graph`.
        if (node == null) return null
        if (jsIsArray()(node) == true) {
            val length = (node.length as? Int) ?: 0
            for (i in 0 until length) articleBodyOf(node[i])?.let { return it }
            return null
        }
        (node.articleBody as? String)?.trim()?.takeIf { it.length > MIN_ARTICLE }?.let { return it }
        return articleBodyOf(node["@graph"])
    }

    /**
     * Strip the furniture: the elements that are never the article (scripts, nav, asides, footers,
     * forms), and the containers a page labels as chrome — a `<div class="sidebar">`, an `id="comments"`.
     * The class/id rule is held to container tags so that a `<p class="lead">` is never mistaken for one.
     */
    private fun removeNoise(doc: dynamic) {
        for (el in queryAll(doc, NOISE_TAGS)) runCatching { el.remove() }
        for (el in queryAll(doc, "div, section, aside, ul, ol, header, footer, nav")) {
            val marker = (((el.className as? String) ?: "") + " " + ((el.id as? String) ?: "")).lowercase()
            if (JUNK.containsMatchIn(marker)) runCatching { el.remove() }
        }
    }

    /**
     * The element the article lives under. Every substantial paragraph lends its length (and its commas,
     * a mark of prose over labels) to its parent and, halved, its grandparent; the container that gathers
     * the most, discounted by how much of it is links, is the one the paragraphs cluster in. This is the
     * core of every reader mode, kept to its essentials.
     */
    private fun bestContainer(doc: dynamic): dynamic {
        val scored = mutableListOf<dynamic>()
        for (p in queryAll(doc, "p")) {
            val text = ((p.textContent as? String) ?: "").trim()
            if (text.length < 25) continue
            val parent = p.parentElement ?: continue
            val base = 1.0 + minOf(text.count { it == ',' }, 3) + minOf(text.length / 100.0, 3.0)
            addScore(parent, base, scored)
            val grand = parent.parentElement
            if (grand != null) addScore(grand, base / 2, scored)
        }
        var best: dynamic = null
        var bestScore = 0.0
        for (candidate in scored) {
            val raw = (candidate._score as? Double) ?: 0.0
            val score = raw * (1.0 - linkDensity(candidate))
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun addScore(node: dynamic, delta: Double, seen: MutableList<dynamic>) {
        val current = node._score as? Double
        if (current == null) seen.add(node)
        node._score = (current ?: 0.0) + delta
    }

    /**
     * The block text of [node]: its paragraphs, headings and list items, one per line, in order. A
     * wrapper that only holds other blocks is skipped so its text is not counted twice, and a list item
     * that is mostly a link is dropped as the navigation it is.
     */
    private fun blockText(node: dynamic): String {
        if (node == null) return ""
        val blocks = queryAll(node, "p, h1, h2, h3, h4, li, blockquote, pre")
        if (blocks.isEmpty()) return collapse((node.textContent as? String).orEmpty())
        return buildString {
            for (el in blocks) {
                val tag = (el.tagName as? String)?.lowercase()
                // A non-paragraph block that wraps a paragraph would repeat it — take the inner one only.
                if (tag != "p" && el.querySelector("p") != null) continue
                if (tag == "li" && linkDensity(el) > 0.5) continue
                val text = collapse((el.textContent as? String).orEmpty())
                if (text.length < 2) continue
                append(text).append("\n\n")
            }
        }.trim().replace(BLANK_LINES, "\n\n")
    }

    /** Every paragraph that reads like prose — long enough, and not mostly a link. The last-resort body. */
    private fun paragraphText(doc: dynamic): String = buildString {
        for (p in queryAll(doc, "p")) {
            val text = collapse((p.textContent as? String).orEmpty())
            if (text.length < 30 || linkDensity(p) > 0.4) continue
            append(text).append("\n\n")
        }
    }.trim()

    private fun metaDescription(doc: dynamic): String? {
        val el = runCatching {
            doc.querySelector("meta[name='description'], meta[property='og:description']")
        }.getOrNull() ?: return null
        return (el.getAttribute("content") as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** How much of a node's text is inside links — a high share marks navigation, not an article. */
    private fun linkDensity(node: dynamic): Double {
        val total = (node.textContent as? String)?.length ?: return 0.0
        if (total == 0) return 0.0
        val linked = queryAll(node, "a").sumOf { (it.textContent as? String)?.length ?: 0 }
        return linked.toDouble() / total
    }

    private fun textOf(node: dynamic, selector: String): String? =
        (node.querySelector(selector)?.textContent as? String)?.trim()?.takeIf { it.isNotBlank() }

    /** A NodeList as a Kotlin list — the shape the rest of this file iterates. */
    private fun queryAll(root: dynamic, selector: String): List<dynamic> {
        val list = runCatching { root.querySelectorAll(selector) }.getOrNull() ?: return emptyList()
        val count = (list.length as? Int) ?: 0
        return (0 until count).mapNotNull { list.item(it) }
    }

    /** A feed summary may itself be HTML; the model wants its words, not its tags. */
    private fun stripTags(html: String): String = collapse(html.replace(TAG, " "))

    /** Whitespace runs (including newlines) to one space, trimmed — for text meant to be one blob. */
    private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

    // The global `fetch`, `DOMParser` and `Array.isArray`, taken as values so the Kotlin call passes its
    // own arguments — a name mentioned inside `js("…")` is the compiler's to mangle, but a value returned
    // from it is ours to call. See [stramus.core.platform.builtInAi].
    private fun fetchApi(): dynamic = js("fetch")
    private fun domParser(): dynamic = js("new DOMParser()")
    private fun jsIsArray(): dynamic = js("Array.isArray")
}

/** How many of a feed's entries are read: enough to summarise what it is running, not its whole month. */
private const val FEED_ITEMS = 20

/** Below this a block of extracted prose is treated as too thin to be the article, and the next signal tried. */
private const val MIN_ARTICLE = 200

private val WHITESPACE = Regex("\\s+")
private val BLANK_LINES = Regex("\\n{3,}")
private val TAG = Regex("<[^>]+>")

/** Elements that are never the article, dropped whole before the page is read. */
private const val NOISE_TAGS =
    "script, style, noscript, svg, template, iframe, form, button, input, select, " +
        "nav, aside, footer, header, figure figcaption, [role='navigation'], [role='banner'], " +
        "[role='contentinfo'], [role='complementary'], [aria-hidden='true']"

/** Container class/id markers that name page chrome rather than content. */
private val JUNK = Regex(
    "nav|menu|sidebar|footer|header|comment|share|social|promo|cookie|consent|banner|" +
        "newsletter|related|recirc|subscribe|paywall|advert|popup|modal|breadcrumb|\\bads?\\b",
)
