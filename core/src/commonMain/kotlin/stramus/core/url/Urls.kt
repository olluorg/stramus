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
