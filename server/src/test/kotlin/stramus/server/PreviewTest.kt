package stramus.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The address a preview may be asked about.
 *
 * `/v1/preview` makes this server fetch a URL the caller names — the same shape of hazard `/v1/favicon`
 * has, with a wider opening, since a whole URL is named rather than a host. The guard is the same one,
 * reached through [normaliseUrl] before anything is resolved, and a change that loosens it is a change
 * worth arguing about.
 */
class PreviewUrlTest {

    @Test
    fun `ordinary addresses are allowed through`() {
        listOf(
            "https://example.com",
            "https://news.ycombinator.com/item?id=1",
            "http://example.co.uk/a/b?c=d&e=f",
        ).forEach { assertTrue(normaliseUrl(it) != null, "$it should be fetchable") }
    }

    @Test
    fun `only the web's own schemes`() {
        // `file:` would read this server's disk; `data:` and `javascript:` are not addresses to fetch at all.
        listOf(
            "file:///etc/passwd",
            "data:text/html,<title>x</title>",
            "javascript:alert(1)",
            "ftp://example.com/x",
            "//example.com/x",
            "example.com/x",
        ).forEach { assertNull(normaliseUrl(it), "$it should not be fetchable") }
    }

    @Test
    fun `addresses inside the network are refused`() {
        listOf(
            "http://localhost/admin",
            "http://127.0.0.1/",
            "http://169.254.169.254/latest/meta-data/",
            "https://db.internal/dump",
            "http://10.0.0.1/",
        ).forEach { assertNull(normaliseUrl(it), "$it should not be fetchable") }
    }

    @Test
    fun `the fragment is dropped and the query is not`() {
        // `#section` never reaches a server, so keeping it would split one page into a cache row per anchor.
        // The query is another matter: for a great many sites it *is* which page you are on.
        assertEquals("https://example.com/a", normaliseUrl("  https://example.com/a#section-2  "))
        assertEquals("https://example.com/a?id=7", normaliseUrl("https://example.com/a?id=7"))
    }

    @Test
    fun `two addresses that differ only in their anchor share one cache row`() {
        assertEquals(
            hashOf(normaliseUrl("https://example.com/a#one")!!),
            hashOf(normaliseUrl("https://example.com/a#two")!!),
        )
    }

    @Test
    fun `the cache key is a hash and not the address`() {
        val key = hashOf("https://example.com/private/page")
        assertEquals(64, key.length)
        assertTrue("example" !in key)
    }
}

/** Reading a page's own description of itself out of its head. */
class OpenGraphTest {

    private val base = "https://example.com/article"

    @Test
    fun `the three tags are read`() {
        val preview = parseOpenGraph(
            """
            <html><head>
            <meta property="og:title" content="A Title">
            <meta property="og:description" content="What it is about.">
            <meta property="og:image" content="https://cdn.example.com/cover.png">
            </head><body>…</body></html>
            """.trimIndent(),
            base,
        )
        assertEquals("A Title", preview.title)
        assertEquals("What it is about.", preview.description)
        assertEquals("https://cdn.example.com/cover.png", preview.image)
    }

    @Test
    fun `twitter's cards and the plain old title stand in`() {
        val preview = parseOpenGraph(
            """
            <head>
            <title>Tab Title</title>
            <meta name="description" content="The old kind.">
            <meta name="twitter:image" content="https://cdn.example.com/t.png">
            </head>
            """.trimIndent(),
            base,
        )
        assertEquals("Tab Title", preview.title)
        assertEquals("The old kind.", preview.description)
        assertEquals("https://cdn.example.com/t.png", preview.image)
    }

    @Test
    fun `open graph wins over the fallbacks`() {
        val preview = parseOpenGraph(
            """
            <head>
            <title>Tab Title — Example</title>
            <meta property="og:title" content="A Title">
            </head>
            """.trimIndent(),
            base,
        )
        assertEquals("A Title", preview.title)
    }

    @Test
    fun `a page that says nothing about itself is empty rather than wrong`() {
        assertTrue(parseOpenGraph("<html><head></head><body><h1>Hello</h1></body></html>", base).isEmpty())
    }

    @Test
    fun `tags in the body do not describe the page`() {
        // A syndicated comment, an advertisement, a user-submitted post: anything below `<body>` is content,
        // not the page's own account of itself.
        val preview = parseOpenGraph(
            """
            <head><title>Real Title</title></head>
            <body><meta property="og:title" content="Injected"></body>
            """.trimIndent(),
            base,
        )
        assertEquals("Real Title", preview.title)
    }

    @Test
    fun `a relative image is resolved against the page it was found on`() {
        val preview = parseOpenGraph("""<head><meta property="og:image" content="/static/cover.png"></head>""", base)
        assertEquals("https://example.com/static/cover.png", preview.image)
    }

    @Test
    fun `an image the client could never draw is dropped`() {
        // Mixed content the browser blocks, an address on the local network, a scheme that is not a fetch —
        // each of which would be a broken frame cached for a fortnight.
        listOf(
            "http://cdn.example.com/cover.png",
            "https://192.168.1.1/cover.png",
            "https://localhost/cover.png",
            "javascript:alert(1)",
        ).forEach {
            val preview = parseOpenGraph("""<head><meta property="og:image" content="$it"></head>""", base)
            assertNull(preview.image, "$it should not be drawn")
        }
    }

    @Test
    fun `the title still arrives when the image does not`() {
        // Dropping the picture must not drop the page: a card with words and no frame is what `Preview.kt`
        // already draws when a frame will not load.
        val preview = parseOpenGraph(
            """<head><meta property="og:title" content="A Title"><meta property="og:image" content="/x.png#"></head>""",
            "http://example.com/a",
        )
        assertEquals("A Title", preview.title)
        assertNull(preview.image)
    }

    @Test
    fun `entities are decoded`() {
        val preview = parseOpenGraph(
            """<head><meta property="og:title" content="Tea &amp; Coffee &mdash; &#171;Best&#187; &#x2026;"></head>""",
            base,
        )
        assertEquals("Tea & Coffee — «Best» …", preview.title)
    }

    @Test
    fun `single quotes and bare attribute values are read too`() {
        val preview = parseOpenGraph(
            "<head><meta property='og:title' content='Quoted Once'><meta property=og:description content=Bare></head>",
            base,
        )
        assertEquals("Quoted Once", preview.title)
        assertEquals("Bare", preview.description)
    }

    @Test
    fun `the first image of several is the one the page leads with`() {
        val preview = parseOpenGraph(
            """
            <head>
            <meta property="og:image" content="https://cdn.example.com/first.png">
            <meta property="og:image" content="https://cdn.example.com/second.png">
            </head>
            """.trimIndent(),
            base,
        )
        assertEquals("https://cdn.example.com/first.png", preview.image)
    }

    @Test
    fun `a page truncated mid-head still gives up what it had`() {
        // 128 KB in and no `</head>` yet: everything read so far is still the head, and the tags are usually
        // in the first few hundred bytes of it.
        val preview = parseOpenGraph("""<html><head><meta property="og:title" content="Cut Short">""", base)
        assertEquals("Cut Short", preview.title)
    }

    @Test
    fun `a hostile page cannot fill a cache row with one title`() {
        val preview = parseOpenGraph("""<head><meta property="og:title" content="${"x".repeat(5_000)}"></head>""", base)
        assertEquals(200, preview.title?.length)
    }
}

/** Which encoding a page's bytes are actually in. A mojibake title is worse than none: it is drawn as if right. */
class HtmlDecodingTest {

    @Test
    fun `the header is believed first`() {
        val bytes = "<title>Привет</title>".toByteArray(Charsets.UTF_8)
        assertTrue("Привет" in decodeHtml(bytes, "text/html; charset=UTF-8"))
    }

    @Test
    fun `a page declaring windows-1251 is read as windows-1251`() {
        // Still served across a good deal of the Russian-language web, and the case UTF-8-by-default gets wrong.
        val cp1251 = Charsets.ISO_8859_1.let { java.nio.charset.Charset.forName("windows-1251") }
        val html = """<html><head><meta charset="windows-1251"><title>Привет</title></head>"""
        assertTrue("Привет" in decodeHtml(html.toByteArray(cp1251), "text/html"))
    }

    @Test
    fun `the older http-equiv declaration is read too`() {
        val cp1251 = java.nio.charset.Charset.forName("windows-1251")
        val html = """<head><meta http-equiv="Content-Type" content="text/html; charset=windows-1251"><title>Привет</title></head>"""
        assertTrue("Привет" in decodeHtml(html.toByteArray(cp1251), "text/html"))
    }

    @Test
    fun `a page declaring nothing is read as UTF-8`() {
        val bytes = "<title>Привет</title>".toByteArray(Charsets.UTF_8)
        assertTrue("Привет" in decodeHtml(bytes, "text/html"))
    }

    @Test
    fun `an encoding this machine has never heard of does not throw`() {
        val bytes = "<title>Hello</title>".toByteArray(Charsets.UTF_8)
        assertTrue("Hello" in decodeHtml(bytes, "text/html; charset=x-nonsense-9000"))
    }
}
