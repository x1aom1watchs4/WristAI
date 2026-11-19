package com.wristbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class WearableBridge(context: Context) {
    private val app = context.applicationContext
    @Volatile private var node: Node? = null
    @Volatile private var commandListener: ((String) -> Unit)? = null
    @Volatile private var listeningNodeId: String? = null
    private val imageChunkAcks = ConcurrentHashMap<String, () -> Unit>()

    fun setCommandListener(listener: ((String) -> Unit)?) {
        commandListener = listener
    }

    fun connect(status: (String) -> Unit) {
        findNode(status) { found ->
            registerCommandListener(found)
            authorizeAndOpen(found, status) {}
        }
    }

    fun send(page: WebPage, images: List<PageImage> = emptyList(), status: (String) -> Unit) {
        val connected = node
        if (connected != null) {
            status("Watch connected: ${connected.name}. Sending page...")
            transferPage(connected, page, images, status)
            return
        }
        status("Finding watch and setting up connection...")
        findNode(status) { found ->
            registerCommandListener(found)
            authorizeAndOpen(found, status) {
                status("Sending page...")
                transferPage(found, page, images, status)
            }
        }
    }

    fun acknowledgeImageChunk(transferId: String, index: Int) {
        imageChunkAcks.remove("$transferId:$index")?.invoke()
    }

    private fun transferImages(nodeId: String, images: List<PageImage>, imageIndex: Int, status: (String) -> Unit) {
        if (imageIndex >= images.size) {
            status("All page data and images sent. Images: ${images.size}.")
            return
        }
        val image = images[imageIndex]
        val encoded = Base64.encodeToString(image.jpeg, Base64.NO_WRAP)
        val chunks = encoded.chunked(300)
        val transferId = UUID.randomUUID().toString()
        val start = JSONObject().put("protocol", 1).put("type", "browser_image_start")
            .put("id", transferId).put("title", image.title.take(120))
            .put("chunks", chunks.size).toString()
        status("Sending image ${imageIndex + 1}/${images.size}...")
        sendPayload(nodeId, start, {
            sendImagePart(nodeId, transferId, chunks, 0, imageIndex, images, status)
        }, { status("Could not start image transfer: $it") })
    }

    private fun sendImagePart(
        nodeId: String,
        transferId: String,
        chunks: List<String>,
        index: Int,
        imageIndex: Int,
        images: List<PageImage>,
        status: (String) -> Unit
    ) {
        if (index >= chunks.size) {
            val end = JSONObject().put("protocol", 1).put("type", "browser_image_end")
                .put("id", transferId).toString()
            sendPayload(nodeId, end, {
                transferImages(nodeId, images, imageIndex + 1, status)
            }, { status("Image transfer error: $it") })
            return
        }
        val payload = JSONObject().put("protocol", 1).put("type", "browser_image_chunk")
            .put("id", transferId).put("index", index).put("data", chunks[index]).toString()
        val ackKey = "$transferId:$index"
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        fun failChunk(reason: String) {
            if (completed.compareAndSet(false, true)) {
                imageChunkAcks.remove(ackKey)
                status("Image chunk ${index + 1}/${chunks.size} was not acknowledged: $reason")
            }
        }
        imageChunkAcks[ackKey] = {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(ackKey)
                status("Image ${imageIndex + 1}/${images.size}: ${index + 1}/${chunks.size}")
                handler.postDelayed({ sendImagePart(nodeId, transferId, chunks, index + 1, imageIndex, images, status) }, 80)
            }
        }
        handler.postAtTime({ failChunk("watch acknowledgement timeout") }, ackKey, android.os.SystemClock.uptimeMillis() + 8000)
        sendPayload(nodeId, payload, {}, { error ->
            // A successful transport send is not proof that the watch processed the chunk.
            // Wait for the watch acknowledgement; report transport errors immediately.
            failChunk(error)
        })
    }

    private fun registerCommandListener(target: Node) {
        if (listeningNodeId == target.id) return
        try {
            Wearable.getMessageApi(app).addListener(
                target.id,
                OnMessageReceivedListener { _, data ->
                    val message = try { String(data, StandardCharsets.UTF_8) } catch (_: Exception) { "" }
                    if (message.isNotBlank()) {
                        try {
                            val json = JSONObject(message)
                            if (json.optString("type") == "browser_image_chunk_ack") {
                                acknowledgeImageChunk(json.optString("id"), json.optInt("index", -1))
                            }
                        } catch (_: Exception) {}
                        commandListener?.invoke(message)
                    }
                }
            )
            listeningNodeId = target.id
        } catch (_: Throwable) {
            // Keep the existing one-way page transfer usable if this firmware lacks inbound messages.
        }
    }

    private fun findNode(status: (String) -> Unit, onFound: (Node) -> Unit) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                status("Watch did not respond within 12 seconds. Check Notify for Xiaomi connection.")
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
                        status("Found watch: ${found.name}. Checking permission...")
                        onFound(found)
                    }
                }
                .addOnFailureListener { error ->
                    if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                    handler.removeCallbacks(timeout)
                    node = null
                    status("Could not find watch: ${error.message ?: "неизвестная error"}")
                }
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                status("Ошибка поиска часов: ${error.message ?: "неизвестная error"}")
            }
        }
    }

    private fun authorizeAndOpen(target: Node, status: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getAuthApi(app)
                .checkPermissions(target.id, arrayOf(Permission.DEVICE_MANAGER))
                .addOnSuccessListener { grants ->
                    if (grants.any { !it }) {
                        status("Requesting permission to connect to watch...")
                        try {
                            Wearable.getAuthApi(app)
                                .requestPermission(target.id, Permission.DEVICE_MANAGER)
                                .addOnSuccessListener {
                                    status("Permission request sent. Approve it in Notify if prompted.")
                                    openWatchApp(target, status, onReady)
                                }
                                .addOnFailureListener { error ->
                                    status("Could not request permission: ${error.message ?: "error"}")
                                }
                        } catch (error: Throwable) {
                            status("Permission request error: ${error.message ?: "error"}")
                        }
                    } else {
                        openWatchApp(target, status, onReady)
                    }
                }
                .addOnFailureListener { error ->
                    status("Could not check watch permission: ${error.message ?: "error"}")
                }
        } catch (error: Throwable) {
            status("Permission check error: ${error.message ?: "error"}")
        }
    }

    private fun openWatchApp(target: Node, status: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getNodeApi(app)
                .launchWearApp(target.id, "/pages/index")
                .addOnSuccessListener {
                    status("WristBrowser is open on the watch. Ready to send a page.")
                    onReady()
                }
                .addOnFailureListener { error ->
                    status("Could not open WristBrowser on watch: ${error.message ?: "error"}")
                }
        } catch (error: Throwable) {
            status("WristBrowser launch error: ${error.message ?: "error"}")
        }
    }

    private fun transferPage(target: Node, page: WebPage, images: List<PageImage>, status: (String) -> Unit) {
        val transferId = UUID.randomUUID().toString()
        val chunks = splitUtf8Safely(page.text, 1200).map { Base64.encodeToString(it.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) }
        val start = JSONObject().put("protocol", 1).put("type", "page_start").put("id", transferId)
            .put("url", page.url).put("title", page.title).put("chunks", chunks.size).toString()
        status("Chunks prepared: ${chunks.size}")
        sendPayload(target.id, start,
            { sendPart(target.id, transferId, chunks, 0, images, status) },
            { error -> status("Could not start transfer: $error") }
        )
    }

    private fun sendPart(
        id: String,
        transferId: String,
        chunks: List<String>,
        index: Int,
        images: List<PageImage>,
        status: (String) -> Unit
    ) {
        if (index >= chunks.size) {
            val end = JSONObject().put("protocol", 1).put("type", "page_end").put("id", transferId).toString()
            sendPayload(id, end, { transferImages(id, images, 0, status) },
                { status("Transfer completion error: $it") })
            return
        }
        val payload = JSONObject().put("protocol", 1).put("type", "page_chunk")
            .put("id", transferId).put("index", index).put("textBase64", chunks[index]).toString()
        sendPayload(id, payload, {
            status("Sending: ${index + 1}/${chunks.size}")
            sendPart(id, transferId, chunks, index + 1, images, status)
        }, { status("Chunk error ${index + 1}: $it") })
    }

    private fun sendPayload(id: String, value: String, success: () -> Unit, failure: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val maxRetries = 3

        fun attempt(retry: Int) {
            try {
                Wearable.getMessageApi(app).sendMessage(id, value.toByteArray(StandardCharsets.UTF_8))
                    .addOnSuccessListener { success() }
                    .addOnFailureListener { error ->
                        if (retry < maxRetries) {
                            handler.postDelayed({ attempt(retry + 1) }, 250L * (retry + 1))
                        } else {
                            failure(error.message ?: "error XMS (after $maxRetries retries)")
                        }
                    }
            } catch (error: Throwable) {
                if (retry < maxRetries) {
                    handler.postDelayed({ attempt(retry + 1) }, 250L * (retry + 1))
                } else {
                    failure(error.message ?: "error XMS (after $maxRetries retries)")
                }
            }
        }

        attempt(0)
    }

    private fun splitUtf8Safely(value: String, maxBytes: Int): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var bytes = 0
        value.codePoints().forEach { codePoint ->
            val part = String(Character.toChars(codePoint))
            val size = part.toByteArray(StandardCharsets.UTF_8).size
            if (bytes + size > maxBytes && current.isNotEmpty()) {
                result += current.toString()
                current = StringBuilder()
                bytes = 0
            }
            current.append(part)
            bytes += size
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.ifEmpty { listOf("") }
    }
}
