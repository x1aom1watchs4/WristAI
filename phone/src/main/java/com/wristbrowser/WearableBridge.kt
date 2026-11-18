package com.wristbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WearableBridge(context: Context) {
    private val app = context.applicationContext
    @Volatile private var node: Node? = null

    fun connect(status: (String) -> Unit) {
        findNode(status) { found ->
            authorizeAndOpen(found, status) {}
        }
    }

    fun send(page: WebPage, status: (String) -> Unit) {
        val connected = node
        if (connected != null) {
            status("Часы подключены: ${connected.name}. Отправляем страницу…")
            transferPage(connected, page, status)
            return
        }

        status("Ищем часы и настраиваем связь…")
        findNode(status) { found ->
            authorizeAndOpen(found, status) {
                status("Отправляем страницу…")
                transferPage(found, page, status)
            }
        }
    }

    private fun findNode(status: (String) -> Unit, onFound: (Node) -> Unit) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                status("Часы не ответили за 12 секунд. Проверь подключение в Notify for Xiaomi.")
            }
        }
        handler.postDelayed(timeout, 12_000)

        try {
            Wearable.getNodeApi(app).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                    handler.removeCallbacks(timeout)
                    val found = nodes.firstOrNull()
                    if (found == null) {
                        node = null
                        status("Часы не найдены. Проверь, что они подключены в Notify for Xiaomi.")
                    } else {
                        node = found
                        status("Нашёл часы: ${found.name}. Проверяю разрешение…")
                        onFound(found)
                    }
                }
                .addOnFailureListener { error ->
                    if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                    handler.removeCallbacks(timeout)
                    node = null
                    status("Не удалось найти часы: ${error.message ?: "неизвестная ошибка"}")
                }
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                status("Ошибка поиска часов: ${error.message ?: "неизвестная ошибка"}")
            }
        }
    }

    private fun authorizeAndOpen(target: Node, status: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getAuthApi(app)
                .checkPermissions(target.id, arrayOf(Permission.DEVICE_MANAGER))
                .addOnSuccessListener { grants ->
                    if (grants.any { !it }) {
                        status("Запрашиваю разрешение на связь с часами…")
                        try {
                            Wearable.getAuthApi(app)
                                .requestPermission(target.id, Permission.DEVICE_MANAGER)
                                .addOnSuccessListener {
                                    status("Запрос разрешения отправлен. Если Notify покажет запрос — подтверди его.")
                                    openWatchApp(target, status, onReady)
                                }
                                .addOnFailureListener { error ->
                                    status("Не удалось запросить разрешение: ${error.message ?: "ошибка"}")
                                }
                        } catch (error: Throwable) {
                            status("Ошибка запроса разрешения: ${error.message ?: "ошибка"}")
                        }
                    } else {
                        openWatchApp(target, status, onReady)
                    }
                }
                .addOnFailureListener { error ->
                    status("Не удалось проверить разрешение часов: ${error.message ?: "ошибка"}")
                }
        } catch (error: Throwable) {
            status("Ошибка проверки разрешения: ${error.message ?: "ошибка"}")
        }
    }

    private fun openWatchApp(target: Node, status: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getNodeApi(app)
                .launchWearApp(target.id, "/pages/index")
                .addOnSuccessListener {
                    status("WristBrowser открыт на часах. Можно передавать страницу.")
                    onReady()
                }
                .addOnFailureListener { error ->
                    status("Не удалось открыть WristBrowser на часах: ${error.message ?: "ошибка"}")
                }
        } catch (error: Throwable) {
            status("Ошибка запуска WristBrowser: ${error.message ?: "ошибка"}")
        }
    }

    private fun transferPage(target: Node, page: WebPage, status: (String) -> Unit) {
        val transferId = UUID.randomUUID().toString()
        val chunks = splitUtf16Safely(page.text, 900)
        val start = JSONObject()
            .put("protocol", 1).put("type", "page_start").put("id", transferId)
            .put("url", page.url).put("title", page.title).put("chunks", chunks.size)
            .toString()
        status("Подготовлено частей: ${chunks.size}")
        sendPayload(target.id, start,
            { sendPart(target.id, transferId, chunks, 0, status) },
            { error -> status("Не удалось начать передачу: $error") }
        )
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
        } catch (error: Throwable) {
            failure(error.message ?: "ошибка XMS")
        }
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
