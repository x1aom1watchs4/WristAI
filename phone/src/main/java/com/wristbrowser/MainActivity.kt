package com.wristbrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val bridge = remember { WearableBridge(applicationContext) }
            var address by remember { mutableStateOf("https://example.com") }
            var page by remember { mutableStateOf<WebPage?>(null) }
            var status by remember { mutableStateOf("Проверяем связь с часами…") }
            var busy by remember { mutableStateOf(false) }
            var browserView by remember { mutableStateOf<WebView?>(null) }

            fun captureAndSend(view: WebView) {
                mainHandler.postDelayed({
                    try {
                        val width = 418
                        val height = 260
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(bitmap)
                        val sx = width.toFloat() / view.width.coerceAtLeast(1)
                        val sy = height.toFloat() / view.height.coerceAtLeast(1)
                        canvas.scale(sx, sy)
                        view.draw(canvas)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 45, output)
                        bitmap.recycle()
                        bridge.sendBrowserImage(view.title?.ifBlank { view.url ?: "Веб-страница" } ?: "Веб-страница", output.toByteArray()) {
                            status = it
                        }
                    } catch (e: Exception) {
                        status = "Не удалось сделать снимок: ${e.message ?: "ошибка"}"
                    }
                }, 650)
            }

            fun openBrowserUrl(raw: String) {
                val target = raw.trim()
                if (target.isBlank()) return
                val url = if (target.startsWith("https://", true)) target else if (target.contains(" ") || !target.contains(".")) {
                    "https://www.google.com/search?q=" + Uri.encode(target)
                } else "https://$target"
                if (!url.startsWith("https://")) {
                    status = "Разрешены только HTTPS-страницы"
                    return
                }
                browserView?.loadUrl(url)
                status = "Открываю: $url"
            }

            LaunchedEffect(Unit) {
                bridge.setCommandListener { raw ->
                    runOnUiThread {
                        try {
                            val command = JSONObject(raw)
                            when (command.optString("type")) {
                                "browser_image_ack" -> {
                                    status = if (command.optBoolean("ok", false)) {
                                        "Часы подтвердили: изображение сохранено и показано."
                                    } else {
                                        "Часы не смогли сохранить изображение: " + command.optString("error", "неизвестная ошибка")
                                    }
                                }
                                "search" -> {
                                    val query = command.optString("query").take(300)
                                    if (query.isNotBlank()) openBrowserUrl(query)
                                }
                                "tap" -> {
                                    val x = command.optDouble("x", -1.0).toInt().coerceIn(0, 465)
                                    val y = command.optDouble("y", -1.0).toInt().coerceIn(0, 465)
                                    browserView?.evaluateJavascript(
                                        "(function(){var e=document.elementFromPoint($x, $y);if(e){e.scrollIntoView({block:'center'});var a=e.closest('a,button,input,[role=button]');if(a){a.click();}else{e.click();}}})()",
                                        null
                                    )
                                    browserView?.let { captureAndSend(it) }
                                }
                                "scroll" -> {
                                    val delta = command.optInt("delta", 300).coerceIn(-1200, 1200)
                                    browserView?.evaluateJavascript("window.scrollBy(0,$delta)", null)
                                    browserView?.let { captureAndSend(it) }
                                }
                            }
                        } catch (_: Exception) {
                            status = "Не удалось обработать команду с часов"
                        }
                    }
                }
                bridge.connect { status = it }
            }

            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("WristBrowser", style = MaterialTheme.typography.headlineMedium)
                        Text("Поиск и веб-страницы на Xiaomi Watch S4")
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            label = { Text("Сайт или поисковый запрос") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = address.isNotBlank(),
                            onClick = { openBrowserUrl(address) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Открыть в браузере часов") }
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.javaScriptCanOpenWindowsAutomatically = false
                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            return request.url.scheme != "https"
                                        }
                                        override fun onPageFinished(view: WebView, url: String) {
                                            status = "Страница готова: ${view.title}"
                                            captureAndSend(view)
                                        }
                                    }
                                    browserView = this
                                }
                            },
                            update = { browserView = it }
                        )
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text("Снимок страницы автоматически отправляется на часы. Нажатия и прокрутка с часов управляют этой страницей.")
                        page?.let { loaded ->
                            HorizontalDivider()
                            Text(loaded.title, style = MaterialTheme.typography.titleLarge)
                            Text(loaded.url, style = MaterialTheme.typography.labelSmall)
                            Text(loaded.text.take(4500), style = MaterialTheme.typography.bodyMedium)
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    bridge.send(loaded) { message ->
                                        status = message
                                        if (message.startsWith("Данные переданы") || message.startsWith("Ошибка")) busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Передать текстовую версию") }
                        }
                    }
                }
            }
        }
    }
}
