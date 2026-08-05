package stramus.ui

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

/**
 * A deliberately small markdown renderer — enough for notes and section descriptions: headings,
 * bold, italic, ==highlight==, `code`, [text](url) links, bare URLs, and `-` bullet lists. HTML in
 * the source is escaped first, so the output is safe to inject. Markdown is our *storage* format;
 * this turns it into HTML both for read-only display and to seed the WYSIWYG editor.
 */
internal fun renderMarkdown(src: String): String {
    val out = StringBuilder()
    var inList = false
    fun closeList() { if (inList) { out.append("</ul>"); inList = false } }

    for (rawLine in escapeHtml(src).split("\n")) {
        val line = rawLine.trimEnd()
        when {
            line.isBlank() -> closeList()
            line.startsWith("### ") -> { closeList(); out.append("<h4>").append(inline(line.substring(4))).append("</h4>") }
            line.startsWith("## ") -> { closeList(); out.append("<h3>").append(inline(line.substring(3))).append("</h3>") }
            line.startsWith("# ") -> { closeList(); out.append("<h2>").append(inline(line.substring(2))).append("</h2>") }
            line.startsWith("- ") || line.startsWith("* ") -> {
                if (!inList) { out.append("<ul>"); inList = true }
                out.append("<li>").append(inline(line.substring(2))).append("</li>")
            }
            else -> { closeList(); out.append("<p>").append(inline(line)).append("</p>") }
        }
    }
    closeList()
    return out.toString()
}

/** Render just the inline formatting (links + highlights + emphasis) — for one-line descriptions. */
internal fun renderInline(src: String): String = inline(escapeHtml(src))

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

// Kept in an object rather than as top-level vals: object properties are initialized reliably on
// first access, avoiding a Kotlin/JS top-level-init ordering bug that left these `undefined`.
private object MdRe {
    val code = Regex("`([^`]+)`")
    val bold = Regex("""\*\*([^*]+)\*\*""")
    val highlight = Regex("==([^=]+)==")
    val italic = Regex("""(?<![*\w])[*_]([^*_]+)[*_](?![*\w])""")
    // The closing `]` must be escaped: Kotlin/JS compiles with the unicode flag, where a lone `]`
    // is a syntax error ("Lone quantifier brackets").
    val mdLink = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)""")
    val bareUrl = Regex("""(https?://[^\s<)]+)""")
    val placeholder = Regex(""" (\d+) """)
}

/**
 * Apply inline tokens. Markdown links are pulled out to numeric placeholders first so bare-URL
 * autolinking never touches a URL already inside an anchor; the placeholders are restored last.
 */
private fun inline(src: String): String {
    val links = mutableListOf<String>()
    var s = MdRe.mdLink.replace(src) { m ->
        links += anchor(m.groupValues[2], m.groupValues[1])
        " ${links.size - 1} "
    }
    s = MdRe.bareUrl.replace(s) { m -> anchor(m.value, m.value) }
    s = MdRe.code.replace(s) { "<code>${it.groupValues[1]}</code>" }
    s = MdRe.bold.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
    s = MdRe.highlight.replace(s) { "<mark>${it.groupValues[1]}</mark>" }
    s = MdRe.italic.replace(s) { "<em>${it.groupValues[1]}</em>" }
    s = MdRe.placeholder.replace(s) { m ->
        val i = m.groupValues[1].toInt()
        if (i < links.size) links[i] else m.value
    }
    return s
}

// No `target` — a link in a note opens where every other link in the app opens: this tab (see
// `navigateTo`). `rel` stays: the note's text is the user's, but its links point outside the app.
private fun anchor(href: String, text: String): String =
    "<a href=\"$href\" rel=\"noopener noreferrer\">$text</a>"

/**
 * Convert the WYSIWYG editor's DOM back to our markdown storage format. [root] is the contenteditable
 * element (accessed dynamically to walk its `childNodes` without wrapper type friction).
 */
internal fun htmlToMarkdown(root: dynamic): String {
    if (root == null) return ""
    val md = childrenMd(root)
    return md
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun childrenMd(node: dynamic): String {
    val sb = StringBuilder()
    val children = node.childNodes ?: return ""
    val n = children.length as Int
    for (i in 0 until n) sb.append(nodeMd(children.item(i)))
    return sb.toString()
}

private fun nodeMd(node: dynamic): String {
    val type = node.nodeType as Int
    if (type == 3) return (node.textContent as? String) ?: "" // text node
    if (type != 1) return "" // ignore comments etc.
    return when ((node.nodeName as String).lowercase()) {
        "br" -> "\n"
        "strong", "b" -> wrap(node, "**", "**")
        "em", "i" -> wrap(node, "*", "*")
        "mark" -> wrap(node, "==", "==")
        "code" -> wrap(node, "`", "`")
        "a" -> "[${childrenMd(node)}](${attr(node, "href")})"
        "h1" -> "\n# ${childrenMd(node)}\n\n"
        "h2" -> "\n## ${childrenMd(node)}\n\n"
        "h3", "h4", "h5", "h6" -> "\n### ${childrenMd(node)}\n\n"
        "li" -> "- ${childrenMd(node).trim()}\n"
        "ul", "ol" -> "\n${childrenMd(node)}\n"
        "p", "div" -> "${childrenMd(node)}\n"
        // Chrome's hiliteColor emits a styled <span>; treat a background style as a highlight.
        "span" -> if (attr(node, "style").contains("background")) wrap(node, "==", "==") else childrenMd(node)
        else -> childrenMd(node)
    }
}

private fun wrap(node: dynamic, pre: String, post: String): String {
    val inner = childrenMd(node)
    return if (inner.isBlank()) inner else "$pre$inner$post"
}

private fun attr(node: dynamic, name: String): String = (node.getAttribute(name) as? String) ?: ""

/** A `{ __html: ... }` object for React's `dangerouslySetInnerHTML`, built without wrapper helpers. */
internal fun innerHtml(html: String): dynamic {
    val o = js("({})")
    o.__html = html
    return o
}

/** Render markdown [md] into a div with [className], via `dangerouslySetInnerHTML`. */
internal fun ChildrenBuilder.markdownBlock(className: String, md: String) {
    div {
        this.className = ClassName(className)
        dangerouslySetInnerHTML = innerHtml(renderMarkdown(md))
    }
}

/** Render inline markdown [md] into a div with [className], via `dangerouslySetInnerHTML`. */
internal fun ChildrenBuilder.inlineMarkdown(className: String, md: String) {
    div {
        this.className = ClassName(className)
        dangerouslySetInnerHTML = innerHtml(renderInline(md))
    }
}
