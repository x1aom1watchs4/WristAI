package com.wristbrowser

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

data class WebPage(val url: String, val title: String, val text: String)

object PageLoader {
    fun load(input: String): WebPage {
        val normalized = if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        val uri = URL(normalized)
        require(uri.protocol == "https") { "Поддерживаются только HTTPS-сайты" }
        val connection = (uri.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WristBrowser/0.1 (Android)")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            connection.connect()
            require(connection.url.protocol == "https") { "Перенаправление на HTTP запрещено" }
            require(connection.responseCode in 200..299) { "Сайт ответил: HTTP ${connection.responseCode}" }
            val type = connection.contentType.orEmpty()
            require(type.contains("text/html", true) || type.contains("application/xhtml", true)) {
                "Этот тип страницы пока не поддерживается"
            }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0
                while (total < 1_500_000) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, 1_500_000 - total))
                    if (count < 0) break
                    out.append(buffer, 0, count)
                    total += count
                }
                out.toString()
            }
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), " ")?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
                .orEmpty().ifBlank { uri.host ?: "Страница" }
            val cleaned = html.replace(Regex("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\\n")
                .replace(Regex("(?is)<[^>]+>"), " ")
            val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT).toString()
                .replace('\u00a0', ' ')
                .replace(Regex("[\\t ]+"), " ")
                .replace(Regex(" *\\n *"), "\\n")
                .replace(Regex("\\n{3,}"), "\\n\\n")
                .trim()
            require(text.isNotBlank()) { "На странице не найден читаемый текст" }
            return WebPage(uri.toString(), title, text.take(180_000))
        } finally {
            connection.disconnect()
        }
    }
}
