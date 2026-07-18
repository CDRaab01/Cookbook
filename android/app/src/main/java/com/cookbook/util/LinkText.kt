package com.cookbook.util

/**
 * Client-side mirror of the server's `lists/link_items.py` URL splitting — used ONLY to render
 * the optimistic/offline local row for a pasted product link. The server owns the real parse
 * (and the best-effort title fetch); its result overwrites this on reconcile. Clients display,
 * never compute — this is display.
 */
object LinkText {
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCT = ".,;:)]}\"'"
    private val idSegmentRegex = Regex("""^(?:\d+|[0-9a-f]{8,})$""", RegexOption.IGNORE_CASE)

    /** (typed text with URLs removed, first URL or null). No URL ⇒ the text comes back intact. */
    fun splitLink(text: String): Pair<String, String?> {
        val match = urlRegex.find(text) ?: return text.trim().split(Regex("\\s+"))
            .joinToString(" ") to null
        val url = match.value.trimEnd(*TRAILING_PUNCT.toCharArray())
        val remaining = urlRegex.replace(text, " ")
            .split(Regex("\\s+"))
            .filter { token -> token.any { it.isLetterOrDigit() } }
            .joinToString(" ")
        return remaining to url
    }

    /** Readable fallback name for a URL-only add: the last human path segment, else the host. */
    fun nameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val afterScheme = withoutQuery.substringAfter("://", withoutQuery)
        val parts = afterScheme.split('/')
        val host = parts.firstOrNull().orEmpty().substringBefore(':').removePrefix("www.")
        for (segment in parts.drop(1).asReversed()) {
            val candidate = java.net.URLDecoder.decode(segment, "UTF-8")
                .replace(Regex("""\.(html?|php|aspx?)$""", RegexOption.IGNORE_CASE), "")
            if (candidate.isEmpty() || idSegmentRegex.matches(candidate)) continue
            val words = candidate.split(Regex("[-_+]+")).filter { it.isNotBlank() }
                .joinToString(" ")
            if (words.isNotBlank()) return words.take(255)
        }
        return (host.ifEmpty { url }).take(255)
    }

    /** The compact display host for a link chip ("meijer.com"). */
    fun displayHost(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore(':')
            .removePrefix("www.").ifEmpty { url }
}
