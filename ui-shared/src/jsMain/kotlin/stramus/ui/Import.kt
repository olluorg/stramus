@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import stramus.core.db.StramusStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One link out of an imported file, with the place the file said it belongs in: [section] →
 * [collection] → [cardSection], any of which may be missing — a bookmarks file with loose links at
 * the top has no folder to name, and a CSV need not carry the column.
 */
internal data class ImportedLink(
    val section: String?,
    val collection: String?,
    val cardSection: String?,
    val title: String,
    val url: String,
)

/** What an import did, so the user is told rather than left guessing at a silently changed sidebar. */
internal data class ImportResult(val added: Int, val skipped: Int)

/**
 * The tags an import cares about, in the order the file writes them: a folder name, a link, and the
 * `<DL>` nesting that says which folder the link is in. Everything else in a bookmarks file —
 * `<DT>`, `<p>`, the icons and timestamps hung off an anchor — is noise here.
 */
// A title may run over a line, so the two title groups match anything at all — `.` in a JS regex does
// not cross a newline, and the flag that would let it is not one Kotlin/JS offers.
private val BOOKMARK_TOKEN = Regex(
    """<h3[^>]*>([\s\S]*?)</h3>|<a\s[^>]*href\s*=\s*"([^"]*)"[^>]*>([\s\S]*?)</a>|<dl|</dl""",
    RegexOption.IGNORE_CASE,
)

private val TAG = Regex("<[^>]*>")

/** The entities a bookmarks (or any HTML) file writes, undone. Numeric ones are rare enough to skip. */
private fun htmlUnescape(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&") // last: an escaped "&amp;lt;" must not turn into a tag

/** The text of a title as it should be read: no markup, no entities, no surrounding space. */
private fun plainText(html: String): String = htmlUnescape(TAG.replace(html, "")).trim()

/**
 * A link worth importing: a page, not a bookmarklet and not a browser-internal entry (Firefox writes
 * its "recently bookmarked" queries as `place:` URIs, Chrome its apps as `chrome://`).
 */
private fun importable(url: String): Boolean {
    val u = url.trim().lowercase()
    return "://" in u && !u.startsWith("javascript:") && !u.startsWith("place:") &&
        !u.startsWith("chrome://") && !u.startsWith("about:")
}

/**
 * Read a Netscape bookmarks file — what every browser exports, and what [exportBookmarks] writes.
 *
 * Folder depth is what says where a link goes, mirroring the export: the outermost folder is a
 * section, the one inside it a collection, the one inside *that* a card section. Deeper folders have
 * nowhere further to go, so their names are joined into the card section's, and nothing is lost.
 * Loose links, in no folder at all, carry no names and land in the imported collection.
 */
internal fun parseBookmarks(html: String): List<ImportedLink> {
    val links = mutableListOf<ImportedLink>()
    // The folders currently open, outermost first. A `<DL>` with no `<H3>` before it — the file's own
    // root — is pushed as a blank, so it holds the nesting straight without counting as a folder.
    val open = mutableListOf<String>()
    var folderName: String? = null

    for (match in BOOKMARK_TOKEN.findAll(html)) {
        val token = match.value.lowercase()
        when {
            token.startsWith("<h3") -> folderName = plainText(match.groupValues[1])
            token.startsWith("</dl") -> open.removeLastOrNull()
            token.startsWith("<dl") -> {
                open.add(folderName.orEmpty())
                folderName = null
            }
            else -> {
                val url = htmlUnescape(match.groupValues[2]).trim()
                if (!importable(url)) continue
                val path = open.filter { it.isNotBlank() }
                links += ImportedLink(
                    section = path.getOrNull(0),
                    collection = path.getOrNull(1) ?: path.getOrNull(0),
                    cardSection = path.drop(2).joinToString(" / ").takeIf { it.isNotBlank() },
                    title = plainText(match.groupValues[3]).ifBlank { hostOf(url) },
                    url = url,
                )
            }
        }
    }
    return links
}

/** One CSV row, split on commas that are not inside quotes, with doubled quotes undone (RFC 4180). */
private fun csvRow(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                fields.add(field.toString())
                field.clear()
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields
}

/**
 * Split CSV text into rows. A newline inside a quoted field is part of the field — a note-like title
 * with a line break in it is one row, not two — so the split cannot be a plain `lines()`.
 */
private fun csvRows(text: String): List<String> {
    val rows = mutableListOf<String>()
    val row = StringBuilder()
    var quoted = false
    for (c in text) {
        when {
            c == '"' -> {
                quoted = !quoted
                row.append(c)
            }
            c == '\n' && !quoted -> {
                rows.add(row.toString().removeSuffix("\r"))
                row.clear()
            }
            else -> row.append(c)
        }
    }
    if (row.isNotBlank()) rows.add(row.toString().removeSuffix("\r"))
    return rows
}

/**
 * Read a CSV export — this app's own (see [exportCsv]), or any file that names its columns the same
 * way. The header decides which column is which, so a file with the columns in another order, or
 * without the ones this does not need, still reads; a file with no header at all is taken in the
 * order the export writes them.
 */
internal fun parseCsv(text: String): List<ImportedLink> {
    val rows = csvRows(text).filter { it.isNotBlank() }
    if (rows.isEmpty()) return emptyList()

    val header = csvRow(rows[0]).map { it.trim().lowercase() }
    val hasHeader = header.any { it == "url" || it == "address" }
    fun columnOf(vararg names: String): Int =
        if (hasHeader) header.indexOfFirst { it in names } else -1

    // Where the export puts them, for a file that does not say: Section, Collection, CardSection,
    // Title, URL, CreatedAt.
    val sectionAt = columnOf("section").takeIf { it >= 0 } ?: 0
    val collectionAt = columnOf("collection", "folder").takeIf { it >= 0 } ?: 1
    val cardSectionAt = columnOf("cardsection", "card section", "group").takeIf { it >= 0 } ?: 2
    val titleAt = columnOf("title", "name").takeIf { it >= 0 } ?: 3
    val urlAt = columnOf("url", "address").takeIf { it >= 0 } ?: 4

    return rows.drop(if (hasHeader) 1 else 0).mapNotNull { line ->
        val fields = csvRow(line)
        val url = fields.getOrNull(urlAt)?.trim().orEmpty()
        if (!importable(url)) return@mapNotNull null
        ImportedLink(
            section = fields.getOrNull(sectionAt)?.trim()?.takeIf { it.isNotBlank() },
            collection = fields.getOrNull(collectionAt)?.trim()?.takeIf { it.isNotBlank() },
            cardSection = fields.getOrNull(cardSectionAt)?.trim()?.takeIf { it.isNotBlank() },
            title = fields.getOrNull(titleAt)?.trim()?.takeIf { it.isNotBlank() } ?: hostOf(url),
            url = url,
        )
    }
}

/** Whether the file is a bookmarks file rather than a CSV — what it holds, not what it is called. */
private fun looksLikeBookmarks(name: String, text: String): Boolean {
    val head = text.take(2000).lowercase()
    return "<dl" in head || "netscape-bookmark" in head || name.endsWith(".html", ignoreCase = true) ||
        name.endsWith(".htm", ignoreCase = true)
}

/**
 * Take in a file the user picked — a bookmarks file from any browser, or a CSV — and put its links
 * where it says they go, creating the sections, collections and card sections it names as they are
 * needed. Existing ones are found by name (ignoring case), so importing into an app that already has
 * a "Work" section adds to that section rather than making a second one.
 *
 * A link already in the collection it would land in is skipped: re-importing the same file, or a
 * newer export of the same bookmarks, adds what is new instead of doubling what is there. Sameness is
 * [normalizeUrl]'s — the same page saved from two places is one page.
 *
 * [importedTitle] names the section and collection for links whose file gave them no folder.
 */
internal suspend fun importFile(
    store: StramusStore,
    fileName: String,
    text: String,
    importedTitle: String,
): ImportResult {
    val links = if (looksLikeBookmarks(fileName, text)) parseBookmarks(text) else parseCsv(text)
    if (links.isEmpty()) return ImportResult(added = 0, skipped = 0)

    var sections = store.sections.all()
    var collections = store.collections.all()
    val defaultSectionId = store.sections.defaultSectionId()

    // The collections a section is given when it is created (one, named after the section). They are
    // there to be used, but a file whose folders are named otherwise leaves them empty — and an empty
    // collection nobody asked for is swept up at the end.
    val autoCollections = mutableSetOf<Uuid>()
    val usedCollections = mutableSetOf<Uuid>()
    val cardSectionIds = mutableMapOf<Pair<Uuid, String>, Uuid>()
    val urlsIn = mutableMapOf<Uuid, MutableSet<String>>()

    suspend fun sectionFor(title: String?): Uuid {
        if (title == null) return defaultSectionId
        sections.firstOrNull { it.title.equals(title, ignoreCase = true) }?.let { return it.id }
        val created = store.sections.create(title)
        sections = store.sections.all()
        collections = store.collections.all()
        collections.filter { it.sectionId == created.id }.forEach { autoCollections.add(it.id) }
        return created.id
    }

    suspend fun collectionFor(sectionId: Uuid, title: String?): Uuid {
        val name = title ?: importedTitle
        collections.firstOrNull { it.sectionId == sectionId && it.title.equals(name, ignoreCase = true) }
            ?.let { return it.id }
        val created = store.collections.create(name, sectionId)
        collections = collections + created
        return created.id
    }

    suspend fun cardSectionFor(collectionId: Uuid, title: String?): Uuid? {
        if (title == null) return null
        cardSectionIds[collectionId to title.lowercase()]?.let { return it }
        val existing = store.cardSections.byCollection(collectionId)
            .firstOrNull { it.title.equals(title, ignoreCase = true) }
        val id = existing?.id ?: store.cardSections.create(collectionId, title, null).id
        cardSectionIds[collectionId to title.lowercase()] = id
        return id
    }

    suspend fun urlsOf(collectionId: Uuid): MutableSet<String> = urlsIn.getOrPut(collectionId) {
        store.cards.byCollection(collectionId).mapTo(mutableSetOf()) { normalizeUrl(it.url) }
    }

    var added = 0
    var skipped = 0
    for (link in links) {
        val sectionId = sectionFor(link.section)
        val collectionId = collectionFor(sectionId, link.collection ?: link.section)
        usedCollections.add(collectionId)

        val known = urlsOf(collectionId)
        val key = normalizeUrl(link.url)
        if (!known.add(key)) {
            skipped++
            continue
        }
        store.cards.add(
            collectionId,
            link.title,
            link.url,
            faviconFor(link.url),
            cardSectionFor(collectionId, link.cardSection),
        )
        added++
    }

    for (id in autoCollections - usedCollections) {
        if (store.cards.count(id) == 0) store.collections.delete(id)
    }
    return ImportResult(added, skipped)
}
