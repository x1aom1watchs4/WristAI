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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val bridge = remember { WearableBridge(applicationContext) }
            var address by remember { mutableStateOf("https://example.com") }
            var status by remember { mutableStateOf("Connecting to watch...") }
            var browserView by remember { mutableStateOf<WebView?>(null) }
            var pageLoading by remember { mutableStateOf(false) }
            var pageReady by remember { mutableStateOf(false) }
            var pageProgress by remember { mutableIntStateOf(0) }
            var sendAfterLoad by remember { mutableStateOf(false) }
            var sendingImage by remember { mutableStateOf(false) }
            var capturePending by remember { mutableStateOf(false) }

            fun captureAndSend(view: WebView) {
                if (sendingImage) { capturePending = true; return }
                sendingImage = true
                status = "Preparing image for watch..."
                mainHandler.postDelayed({
                    try {
                        if (view.width <= 0 || view.height <= 0) {
                            sendingImage = false
                            status = "Preview is not ready yet. Open the page again."
                            return@postDelayed
                        }
                        val width = 418
                        val height = 260
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                        val canvas = Canvas(bitmap)
                        canvas.scale(
                            width.toFloat() / view.width.coerceAtLeast(1),
                            height.toFloat() / view.height.coerceAtLeast(1)
                        )
                        view.draw(canvas)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 55, output)
                        bitmap.recycle()
                        bridge.sendBrowserImage(
                            view.title?.ifBlank { view.url ?: "Web page" } ?: "Web page",
                            output.toByteArray()
                        ) { message ->
                            status = message
                            if (message.contains("error", ignoreCase = true) ||
                                message.contains("Could not", ignoreCase = true) ||
                                message.startsWith("Watch not connected")) {
                                sendingImage = false
                                if (capturePending) {
                                    capturePending = false
                                    mainHandler.postDelayed({ browserView?.let { captureAndSend(it) } }, 180)
                                }
                            }
                        }
                        mainHandler.postDelayed({
                            if (sendingImage) {
                                sendingImage = false
                                if (capturePending) {
                                    capturePending = false
                                    browserView?.let { captureAndSend(it) }
                                }
                            }
                        }, 7000)
                    } catch (e: Exception) {
                        sendingImage = false
                        status = "Could not capture preview: ${e.message ?: "error"}"
                    }
                }, 35)
            }

            fun openBrowserUrl(raw: String, sendWhenReady: Boolean = false) {
                sendAfterLoad = sendWhenReady
                val target = raw.trim()
                if (target.isBlank()) return
                if (target.startsWith("http://", true)) {
                    status = "Please use a secure HTTPS address."
                    return
                }
                val url = when {
                    target.startsWith("https://", true) -> target
                    target.contains(" ") || !target.contains(".") ->
                        "https://www.google.com/search?q=" + Uri.encode(target)
                    else -> "https://$target"
                }
                browserView?.loadUrl(url)
                if (browserView == null) {
                    status = "Browser preview is starting. Tap Open again in a moment."
                } else {
                    pageReady = false
                    status = "Opening page..."
                }
            }

            LaunchedEffect(Unit) {
                bridge.setCommandListener { raw ->
                    runOnUiThread {
                        try {
                            val command = JSONObject(raw)
                            when (command.optString("type")) {
                                "browser_image_ack" -> {
                                    sendingImage = false
                                    status = if (command.optBoolean("ok", false)) {
                                        "Watch confirmed: preview received and displayed."
                                    } else {
                                        "Watch could not display image: " + command.optString("error", "unknown error")
                                    }
                                    if (capturePending) {
                                        capturePending = false
                                        mainHandler.postDelayed({ browserView?.let { captureAndSend(it) } }, 180)
                                    }
                                }
                                "search" -> {
                                    val query = command.optString("query").take(300)
                                    if (query.isNotBlank()) {
                                        address = query
                                        openBrowserUrl(query, true)
                                    }
                                }
                                "tap" -> {
                                    val x = command.optDouble("x", -1.0).toInt().coerceIn(0, 417)
                                    val y = command.optDouble("y", -1.0).toInt().coerceIn(0, 259)
                                    val view = browserView
                                    if (view != null && pageReady && !pageLoading) {
                                        sendAfterLoad = true
                                        val script = "(function(){var x=($x/418)*window.innerWidth;var y=($y/260)*window.innerHeight;var e=document.elementFromPoint(x,y);if(!e)return '';var a=e.closest('a[href]');if(a&&a.href&&a.href.indexOf('https:')===0)return a.href;var b=e.closest('button,input,select,textarea,[role=button],[onclick]');(b||e).click();return '';})()"
                                        view.evaluateJavascript(script) { result ->
                                            val href = try { org.json.JSONTokener(result ?: "\"\"").nextValue() as? String } catch (_: Exception) { null }
                                            if (!href.isNullOrBlank() && href.startsWith("https://")) {
                                                view.loadUrl(href)
                                            } else {
                                                mainHandler.postDelayed({
                                                    if (sendAfterLoad && pageReady && !pageLoading) {
                                                        sendAfterLoad = false
                                                        captureAndSend(view)
                                                    }
                                                }, 900)
                                            }
                                        }
                                        mainHandler.postDelayed({
                                            if (sendAfterLoad && pageLoading) {
                                                view.stopLoading()
                                                pageLoading = false
                                                pageReady = true
                                                sendAfterLoad = false
                                                status = "Page load timed out; showing current page."
                                                captureAndSend(view)
                                            }
                                        }, 9000)
                                    }
                                }
                                "scroll" -> {
                                    val delta = command.optInt("delta", 300).coerceIn(-1200, 1200)
                                    val view = browserView
                                    if (view != null && pageReady && !pageLoading) {
                                        status = if (delta > 0) "Scrolling page down..." else "Scrolling page up..."
                                        // Scroll the actual WebView first. This works even on pages
                                        // whose content does not respond to window.scrollBy().
                                        view.scrollBy(0, delta)
                                        val script = "(function(){var x=Math.floor(window.innerWidth/2),y=Math.floor(window.innerHeight/2),e=document.elementFromPoint(x,y);while(e&&e!==document.body){var s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+2){e.scrollTop+=($delta);return 'nested';}e=e.parentElement;}window.scrollBy(0,($delta));if(document.scrollingElement)document.scrollingElement.scrollTop+=0;return 'document';})()"
                                        view.evaluateJavascript(script, null)
                                        mainHandler.postDelayed({
                                            if (pageReady && !pageLoading) browserView?.let { captureAndSend(it) }
                                        }, 90)
                                    } else {
                                        status = "Scroll ignored: page is not ready yet."
                                    }
                                }
                                "back" -> {
                                    val view = browserView
                                    if (view != null && view.canGoBack()) {
                                        sendAfterLoad = true
                                        view.goBack()
                                    } else {
                                        status = "No previous page."
                                    }
                                }
                                "forward" -> {
                                    val view = browserView
                                    if (view != null && view.canGoForward()) {
                                        sendAfterLoad = true
                                        view.goForward()
                                    } else {
                                        status = "No next page."
                                    }
                                }
                                "reload" -> {
                                    val view = browserView
                                    if (view != null) {
                                        sendAfterLoad = true
                                        view.reload()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            status = "Could not process command from watch."
                        }
                    }
                }
                bridge.connect { status = it }
            }

            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("WristBrowser", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Open a website here and preview exactly what is sent to your Xiaomi Watch S4.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Website or search") },
                            placeholder = { Text("google.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { openBrowserUrl(address) },
                                enabled = address.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Open") }

                            OutlinedButton(
                                onClick = { browserView?.let { captureAndSend(it) } },
                                enabled = pageReady && !pageLoading && browserView != null && !sendingImage,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (sendingImage) "Sending..." else "Send to Watch") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("WATCH PREVIEW", style = MaterialTheme.typography.labelLarge)
                            Text("418 × 260", style = MaterialTheme.typography.labelSmall)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .aspectRatio(418f / 260f)
                                    .clip(RoundedCornerShape(18.dp))
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        WebView(context).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.allowFileAccess = false
                                            settings.allowContentAccess = false
                                            settings.javaScriptCanOpenWindowsAutomatically = false
                                            webChromeClient = object : WebChromeClient() {
                                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                                    pageProgress = newProgress
                                                }
                                            }
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView,
                                                    request: WebResourceRequest
                                                ): Boolean = request.url.scheme != "https"

                                                override fun onPageStarted(
                                                    view: WebView,
                                                    url: String,
                                                    favicon: Bitmap?
                                                ) {
                                                    pageLoading = true
                                                    pageReady = false
                                                    status = "Loading page..."
                                                }

                                                override fun onPageFinished(view: WebView, url: String) {
                                                    pageLoading = false
                                                    pageReady = true
                                                    if (sendAfterLoad) {
                                                        sendAfterLoad = false
                                                        status = "Search loaded. Sending to watch..."
                                                        captureAndSend(view)
                                                    } else {
                                                        status = "Preview ready: ${view.title ?: url}"
                                                    }
                                                }
                                            }
                                            setBackgroundColor(android.graphics.Color.WHITE)
                                            // Let the WebView receive swipe gestures instead of the
                                            // surrounding Compose screen stealing them for its scroll.
                                            setOnTouchListener { web, event ->
                                                when (event.actionMasked) {
                                                    android.view.MotionEvent.ACTION_DOWN,
                                                    android.view.MotionEvent.ACTION_MOVE ->
                                                        web.parent?.requestDisallowInterceptTouchEvent(true)
                                                    android.view.MotionEvent.ACTION_UP,
                                                    android.view.MotionEvent.ACTION_CANCEL -> {
                                                        web.parent?.requestDisallowInterceptTouchEvent(false)
                                                        if (pageReady && !pageLoading) {
                                                            mainHandler.postDelayed({
                                                                if (pageReady && !pageLoading) {
                                                                    browserView?.let { captureAndSend(it) }
                                                                }
                                                            }, 60)
                                                        }
                                                    }
                                                }
                                                false
                                            }
                                            browserView = this
                                        }
                                    },
                                    update = { browserView = it }
                                )
                            }
                        }

                        if (pageLoading) {
                            LinearProgressIndicator(
                                progress = { pageProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Loading: $pageProgress%", style = MaterialTheme.typography.labelSmall)
                        }

                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "The preview uses the same 418 × 260 image size as the watch. Touches and scrolling on the watch control this page.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
