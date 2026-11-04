package br.com.pessoasaqui.core.link

import java.net.URI
import java.util.Locale

/** Só URLs web são abertas fora do aplicativo: nunca file:, javascript:, intent:, etc. */
data class WebLink(val start: Int, val endExclusive: Int, val displayText: String, val url: String)

object WebLinkPolicy {
    // http(s), www e domínios comuns sem esquema (ex.: exemplo.com.br).
    // O lookbehind evita transformar partes de endereços de e-mail em links.
    private val urls = Regex(
        """(?<![A-Za-z0-9@._-])(?:(?:https?://|www\.)[^\s<>"'`]+|(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,63}(?::[0-9]{1,5})?(?:[/#?][^\s<>"'`]*)?)""",
        RegexOption.IGNORE_CASE
    )

    private fun removePunctuation(raw: String): String {
        var candidate = raw.trim()
        while (candidate.isNotEmpty()) {
            val c = candidate.last()
            val trim = when (c) {
                '.', ',', ';', ':', '!', '?' -> true
                ')' -> candidate.count { it == ')' } > candidate.count { it == '(' }
                ']' -> candidate.count { it == ']' } > candidate.count { it == '[' }
                '}' -> candidate.count { it == '}' } > candidate.count { it == '{' }
                else -> false
            }
            if (!trim) break
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    fun normalizeWebUrl(input: String): String? {
        val raw = removePunctuation(input)
        if (raw.isBlank() || raw.length > 4096 ||
            raw.any { it.isWhitespace() || it.code < 32 }) return null
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.contains("://") && !lower.startsWith("https://") && !lower.startsWith("http://")) {
            return null
        }
        val url = if (lower.startsWith("https://") || lower.startsWith("http://")) raw
                  else "https://$raw"
        return try {
            val parsed = URI(url)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            val host = parsed.host
            if (scheme !in setOf("http", "https") || host.isNullOrBlank() ||
                parsed.rawUserInfo != null || parsed.fragment?.contains("\u0000") == true) null
            else parsed.toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun findWebLinks(text: String): List<WebLink> =
        urls.findAll(text).mapNotNull { match ->
            val visible = removePunctuation(match.value)
            val normalized = normalizeWebUrl(visible)
            if (normalized == null || visible.isEmpty()) null
            else WebLink(match.range.first, match.range.first + visible.length, visible, normalized)
        }.toList()
}
