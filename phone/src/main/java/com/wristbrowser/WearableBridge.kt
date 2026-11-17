package com.wristbrowser

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

class WearableBridge(context: Context) {
    private val app = context.applicationContext
    @Volatile private var node: Node? = null

    fun connect(status: (String) -> Unit) {
        try {
            Wearable.getNodeApi(app).connectedNodes
                .addOnSuccessListener { nodes ->
                    node = nodes.firstOrNull()
                    status(if (node == null) "Часы не найдены. Проверь Notify for Xiaomi." else "Часы найдены: ${node?.name}")
                }
                .addOnFailureListener { status("Ошибка поиска часов: ${it.message ?: "неизвестно"}") }
        } catch (e: Exception) { status("Ошибка XMS: ${e.message ?: "неизвестно"}") }
    }

    fun send(page: WebPage, status: (String) -> Unit) {
        val target = node
        if (target == null) {
            connect { result ->
                if (node == null) status(result) else send(page, status)
            }
            return
        }
        val transferId = UUID.randomUUID().toString()
        val chunks = splitUtf16Safely(page.text, 900)
        val start = JSONObject()
            .put("protocol", 1).put("type", "page_start").put("id", transferId)
            .put("url", page.url).put("title", page.title).put("chunks", chunks.size)
            .toString()
        status("Подготовлено частей: ${chunks.size}")
        sendPayload(target.id, start) {
            sendPart(target.id, transferId, chunks, 0, status)
        } onFailure@{ error -> status("Не удалось начать передачу: $error") }
    }

    private fun sendPart(id: String, transferId: String, chunks: List<String>, index: Int, status: (String) -> Unit) {
        if (index >= chunks.size) {
            val end = JSONObject().put("protocol", 1).put("type", "page_end").put("id", transferId).toString()
            sendPayload(id, end, { status("Данные переданы транспорту. Ожидается проверка на часах.") },
                { status("Ошибка завершения передачи: $it") })
            return
        }
        val payload = JSONObject().put("protocol", 1).put("type", "page_chunk")
            .put("id", transferId).put("index", index).put("text", chunks[index]).toString()
        sendPayload(id, payload, {
            status("Отправка: ${index + 1}/${chunks.size}")
            sendPart(id, transferId, chunks, index + 1, status)
        }, { status("Ошибка части ${index + 1}: $it") })
    }

    private fun sendPayload(id: String, value: String, success: () -> Unit, failure: (String) -> Unit) {
        try {
            Wearable.getMessageApi(app).sendMessage(id, value.toByteArray(StandardCharsets.UTF_8))
                .addOnSuccessListener { success() }
                .addOnFailureListener { failure(it.message ?: "ошибка XMS") }
        } catch (e: Exception) { failure(e.message ?: "ошибка XMS") }
    }

    private fun splitUtf16Safely(value: String, max: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + max, value.length)
            if (end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
            result += value.substring(start, end)
            start = end
        }
        return result.ifEmpty { listOf("") }
    }
}
