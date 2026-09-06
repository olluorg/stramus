package stramus.core.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the four files stramus takes in.
 *
 * These are other people's formats, written by other people's exporters, and every one of the shapes
 * below came out of a real file rather than out of a specification: a folder whose title runs over two
 * lines, an anchor carrying an icon and three timestamps, a CSV quoting a comma, a Toby export whose
 * keys are named differently depending on which year it was made in.
 *
 * What matters as much as reading them is refusing what must not be read: a `javascript:` bookmarklet
 * and a `chrome://` page are in every bookmarks file ever exported, and neither is a link anybody wants
 * saved as a card.
 */
class ImportParseTest {

    // ---- Netscape bookmarks HTML ----------------------------------------------------------------

    @Test
    fun `folders become section, collection and card section, in that nesting`() {
        val links = parseBookmarks(
            """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
                <DT><H3>Работа</H3>
                <DL><p>
                    <DT><H3>Frontend</H3>
                    <DL><p>
                        <DT><H3>Docs</H3>
                        <DL><p>
                            <DT><A HREF="https://developer.mozilla.org/">MDN</A>
                        </DL><p>
                        <DT><A HREF="https://vite.dev/">Vite</A>
                    </DL><p>
                </DL><p>
            </DL><p>
            """.trimIndent(),
        )

        assertEquals(2, links.size)
        val mdn = links.first { it.url.contains("mozilla") }
        assertEquals("Работа", mdn.section)
        assertEquals("Frontend", mdn.collection)
        assertEquals("Docs", mdn.cardSection)

        // One folder shallower: still in the collection, under no card section.
        val vite = links.first { it.url.contains("vite") }
        assertEquals("Frontend", vite.collection)
        assertEquals(null, vite.cardSection)
    }

    @Test
    fun `a link at the top of the file belongs to no folder at all`() {
        val links = parseBookmarks("""<DL><p><DT><A HREF="https://example.org/">Loose</A></DL><p>""")
        assertEquals(1, links.size)
        assertEquals(null, links.single().section)
        assertEquals(null, links.single().collection)
    }

    @Test
    fun `the noise a real exporter writes is not part of the title`() {
        val links = parseBookmarks(
            """
            <DL><p>
                <DT><A HREF="https://example.org/" ADD_DATE="1700000000" ICON="data:image/png;base64,AAA"
                       LAST_MODIFIED="1700000001">Ampersand &amp; friends</A>
            </DL><p>
            """.trimIndent(),
        )
        assertEquals("Ampersand & friends", links.single().title)
        assertEquals("https://example.org/", links.single().url)
    }

    @Test
    fun `a title that runs over a line is still one title`() {
        val links = parseBookmarks("<DL><p><DT><A HREF=\"https://example.org/\">two\nlines</A></DL><p>")
        assertTrue(links.single().title.isNotBlank())
    }

    @Test
    fun `what a browser can never open is not imported`() {
        val links = parseBookmarks(
            """
            <DL><p>
                <DT><A HREF="javascript:alert(1)">A bookmarklet</A>
                <DT><A HREF="chrome://bookmarks/">The bookmark manager</A>
                <DT><A HREF="about:blank">Nothing</A>
                <DT><A HREF="">Empty</A>
                <DT><A HREF="https://example.org/">A real one</A>
            </DL><p>
            """.trimIndent(),
        )
        assertEquals(listOf("https://example.org/"), links.map { it.url })
    }

    @Test
    fun `an empty file is no links rather than a failure`() {
        assertEquals(emptyList(), parseBookmarks(""))
        assertEquals(emptyList(), parseBookmarks("<DL><p></DL><p>"))
    }

    // ---- OneTab ----------------------------------------------------------------------------------

    @Test
    fun `each blank-line-separated group of a OneTab export is a collection`() {
        val links = parseOneTab(
            """
            https://kotlinlang.org/ | Kotlin
            https://github.com/ | GitHub

            https://example.org/ | Example
            """.trimIndent(),
        )

        assertEquals(3, links.size)
        assertEquals("Kotlin", links.first().title)
        // The first two share a group, the third is in its own.
        assertTrue(links[0].collection == links[1].collection)
        assertTrue(links[2].collection != links[0].collection)
    }

    @Test
    fun `a OneTab line with no title still carries its address`() {
        val links = parseOneTab("https://example.org/")
        assertEquals("https://example.org/", links.single().url)
        assertTrue(links.single().title.isNotBlank(), "a card with no name at all is a card nobody can read")
    }

    // ---- CSV --------------------------------------------------------------------------------------

    @Test
    fun `a csv is read by its header, whatever order the columns are in`() {
        val links = parseCsv(
            """
            url,title,section,collection,card section
            https://example.org/,Example,Работа,Frontend,Docs
            """.trimIndent(),
        )
        val link = links.single()
        assertEquals("https://example.org/", link.url)
        assertEquals("Example", link.title)
        assertEquals("Работа", link.section)
        assertEquals("Frontend", link.collection)
        assertEquals("Docs", link.cardSection)
    }

    @Test
    fun `a quoted field keeps its commas, its quotes and its newlines`() {
        val links = parseCsv(
            "url,title\n" +
                "\"https://example.org/a\",\"Smith, John\"\n" +
                "\"https://example.org/b\",\"He said \"\"hello\"\"\"\n" +
                "\"https://example.org/c\",\"two\nlines\"\n",
        )
        assertEquals(listOf("Smith, John", "He said \"hello\"", "two\nlines"), links.map { it.title })
    }

    @Test
    fun `a csv with no rows under its header imports nothing`() {
        assertEquals(emptyList(), parseCsv("url,title\n"))
    }

    // ---- Toby -------------------------------------------------------------------------------------

    @Test
    fun `each toby list is a collection, and its first label the section`() {
        val links = parseToby(
            """
            {"lists":[{"title":"Frontend","labels":[{"title":"Работа"}],
              "cards":[{"url":"https://vite.dev/","title":"Vite"}]}]}
            """.trimIndent(),
        )
        val link = links.single()
        assertEquals("Frontend", link.collection)
        assertEquals("Работа", link.section)
        assertEquals("Vite", link.title)
    }

    @Test
    fun `a toby export that is not one is read as nothing rather than thrown at the user`() {
        assertEquals(emptyList(), parseToby("{}"))
        assertEquals(emptyList(), parseToby("not json at all"))
        assertEquals(emptyList(), parseToby("""{"lists":[]}"""))
    }
}
