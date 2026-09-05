package stramus.core.url

/**
 * Best-effort host extraction from a raw URL string, without constructing a URL of any kind.
 *
 * It lives here, rather than next to the UI that draws favicons with it, because the tab triage
 * ([stramus.core.ai]) groups by site and has no browser under it: this is the one piece of URL
 * knowledge shared by code that runs in a page and code that runs in a test.
 */
fun hostOf(url: String): String {
    val afterProto = if ("://" in url) url.substringAfter("://") else url
    return afterProto.substringBefore('/').substringBefore('?').removePrefix("www.").ifBlank { url }
}

/** The YouTube hosts a video address is ever written with, once [hostOf] has taken `www.` off. */
private val YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "youtu.be",
)

/** A video id is eleven characters of URL-safe base64, and nothing else is one. */
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * The video a YouTube address points at, or null when it points at anything else — a channel, a
 * playlist page, the home page, or a site that is not YouTube at all.
 *
 * This is what a link card's preview is fetched by: YouTube publishes every video's still frame at a
 * fixed address built from this id, so a card knows what it shows without anybody being asked. The
 * several shapes below are all one video: `watch?v=`, the `youtu.be` short form, and the path forms
 * (`/shorts/`, `/embed/`, `/live/`, `/v/`) that the site itself hands out in its share and embed menus.
 *
 * It lives beside [hostOf] rather than next to the card that draws the preview because it is the same
 * kind of knowledge — reading a URL without a browser to parse it — and, like [hostOf], it is worth
 * testing away from the DOM.
 */
fun youtubeVideoId(url: String): String? {
    if (hostOf(url) !in YOUTUBE_HOSTS) return null

    val afterProto = if ("://" in url) url.substringAfter("://") else url
    val afterHost = afterProto.substringAfter('/', "").substringBefore('#')
    val path = afterHost.substringBefore('?')
    val query = afterHost.substringAfter('?', "")

    // `watch?v=`, and `youtu.be/x?v=y` too — a `v` parameter names the video wherever it appears.
    val fromQuery = query.split('&')
        .firstOrNull { it.startsWith("v=") }
        ?.removePrefix("v=")
    if (fromQuery != null) return fromQuery.takeIf { VIDEO_ID.matches(it) }

    val segments = path.split('/').filter { it.isNotBlank() }
    val candidate = when {
        // The short form is nothing but the id: `youtu.be/dQw4w9WgXcQ`.
        hostOf(url) == "youtu.be" -> segments.firstOrNull()
        segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live", "v") -> segments[1]
        else -> null
    }
    return candidate?.takeIf { VIDEO_ID.matches(it) }
}

/** Query parameters that identify a campaign, not a page: two links differing only in these are one. */
private val TRACKING_PARAMS = listOf("utm_", "fbclid", "gclid", "yclid", "msclkid", "mc_eid", "_hsenc")

/**
 * The one identity of a page: lowercase host without `www.`, no scheme, no fragment, no trailing
 * slash, no tracking parameters. It is the key of the usage table, how a card, an open tab and a
 * visited page are recognised as the same thing, and — through `DuplicateMerge` — how two saved cards
 * are recognised as one. So it must not depend on which of those the link happened to arrive from.
 *
 * The path keeps its case: a host is case-insensitive, a path very often is not.
 */
fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim().substringBefore('#')
    if (trimmed.isBlank()) return ""
    val afterScheme = if ("://" in trimmed) trimmed.substringAfter("://") else trimmed
    val path = afterScheme.substringBefore('?')
    val query = afterScheme.substringAfter('?', "")

    val slash = path.indexOf('/')
    val host = (if (slash < 0) path else path.take(slash)).lowercase().removePrefix("www.")
    val rest = (if (slash < 0) "" else path.substring(slash)).trimEnd('/')
    val keptParams = query.split('&')
        .filter { param -> param.isNotBlank() && TRACKING_PARAMS.none { param.startsWith(it) } }

    return buildString {
        append(host)
        append(rest)
        if (keptParams.isNotEmpty()) {
            append('?')
            append(keptParams.joinToString("&"))
        }
    }
}
