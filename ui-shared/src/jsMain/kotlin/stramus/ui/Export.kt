@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import stramus.core.db.StramusStore
import kotlin.uuid.ExperimentalUuidApi

/** One CSV field, quoted and with embedded quotes doubled per RFC 4180. */
private fun csvField(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

private fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * Export every saved link to a CSV file (section, collection, card-section, title, url, createdAt)
 * and trigger a download. Reads the store fresh so the export always reflects current data.
 */
internal suspend fun exportCsv(store: StramusStore) {
    val sections = store.sections.all()
    val collections = store.collections.all()
    val sectionTitle = sections.associate { it.id to it.title }

    val rows = StringBuilder()
    rows.append("Section,Collection,CardSection,Title,URL,CreatedAt\n")
    for (col in collections) {
        val cardSections = store.cardSections.byCollection(col.id).associate { it.id to it.title }
        for (card in store.cards.byCollection(col.id)) {
            rows.append(csvField(sectionTitle[col.sectionId] ?: "")).append(',')
            rows.append(csvField(col.title)).append(',')
            rows.append(csvField(card.cardSectionId?.let { cardSections[it] } ?: "")).append(',')
            rows.append(csvField(card.title)).append(',')
            rows.append(csvField(card.url)).append(',')
            rows.append(csvField(card.createdAt.toString())).append('\n')
        }
    }
    downloadFile("stramus.csv", "text/csv", rows.toString())
}

/**
 * Export all links as a Netscape bookmarks file (importable into Chrome/Firefox), nesting
 * Section → Collection folders. Triggers a download.
 */
internal suspend fun exportBookmarks(store: StramusStore) {
    val sections = store.sections.all().sortedBy { it.position }
    val collections = store.collections.all().sortedBy { it.position }

    val html = StringBuilder()
    html.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
    html.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
    html.append("<TITLE>Bookmarks</TITLE>\n<H1>stramus</H1>\n<DL><p>\n")
    for (section in sections) {
        val cols = collections.filter { it.sectionId == section.id }
        if (cols.isEmpty()) continue
        html.append("    <DT><H3>").append(htmlEscape(section.title)).append("</H3>\n    <DL><p>\n")
        for (col in cols) {
            html.append("        <DT><H3>").append(htmlEscape(col.title)).append("</H3>\n        <DL><p>\n")
            for (card in store.cards.byCollection(col.id)) {
                html.append("            <DT><A HREF=\"").append(htmlEscape(card.url)).append("\">")
                    .append(htmlEscape(card.title)).append("</A>\n")
            }
            html.append("        </DL><p>\n")
        }
        html.append("    </DL><p>\n")
    }
    html.append("</DL><p>\n")
    downloadFile("stramus-bookmarks.html", "text/html", html.toString())
}
