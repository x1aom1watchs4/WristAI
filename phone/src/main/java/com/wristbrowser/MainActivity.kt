package com.wristbrowser

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WebPage(val url: String, val title: String, val text: String)
data class PageImage(val title: String, val jpeg: ByteArray)

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundServiceEnabled = mutableStateOf(false)
    private val backgroundServiceMessage = mutableStateOf("Фоновый режим выключен.")

    private fun startBackgroundConnectionService() {
        val intent = Intent(this, BackgroundConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        backgroundServiceEnabled.value = true
        backgroundServiceMessage.value = "Фоновый режим активен. Android показывает постоянное уведомление."
    }

    private fun stopBackgroundConnectionService() {
        stopService(Intent(this, BackgroundConnectionService::class.java))
        backgroundServiceEnabled.value = false
        backgroundServiceMessage.value = "Фоновый режим выключен."
    }

    private fun requestOpenRouter(apiKey: String, requestBody: JSONObject, callback: (String?, String?) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = URL("https://openrouter.ai/api/v1/chat/completions")
                connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("HTTP-Referer", "https://github.com/x1aom1watchs4/WristBrowser")
                    setRequestProperty("X-Title", "WristAI")
                }
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = try {
                        val error = JSONObject(responseText).optJSONObject("error")
                        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                        val metadata = error?.optJSONObject("metadata")
                        val provider = metadata?.optString("provider_name")?.takeIf { it.isNotBlank() && it != "null" }
                        val raw = metadata?.optString("raw")?.takeIf { it.isNotBlank() && it != "null" }
                        listOfNotNull(message, provider?.let { "Provider: " + it }, raw?.let { "Details: " + it.take(500) })
                            .joinToString(" · ")
                    } catch (_: Exception) { null }
                    throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenRouter HTTP $code")
                }
                val json = JSONObject(responseText)
                val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = message?.opt("content")
                val answer = when (content) {
                    is String -> content.trim()
                    is JSONArray -> buildString {
                        for (i in 0 until content.length()) {
                            val part = content.optJSONObject(i)
                            if (part?.optString("type") == "text") append(part.optString("text", ""))
                        }
                    }.trim()
                    else -> ""
                }
                if (answer.isBlank()) throw IllegalStateException("OpenRouter вернул пустой ответ")
                // Keep the Bluetooth payload small so long model replies do not overload the watch UI.
                mainHandler.post { callback(answer.take(1000), null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, error.message ?: "Не удалось связаться с Gemini") }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startBackgroundConnectionService()
            else backgroundServiceMessage.value = "Разрешение Bluetooth не выдано. Фоновый режим не включён."
        }
        setContent {
            val context = LocalContext.current.applicationContext
            val encryptedPrefs = remember {
                runCatching {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        "wristai_secure_prefs",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.getOrNull()
            }
            val bridge = remember { WearableBridge(applicationContext) }
            var apiKey by remember {
                mutableStateOf(encryptedPrefs?.getString("openrouter_api_key", "") ?: "")
            }
            val chatModels = listOf(
                "google/gemma-4-26b-a4b-it:free" to "Gemma 4 26B A4B (Free)",
                "qwen/qwen4-max" to "Qwen4 Max",
                "mistralai/pixtral-large-2502" to "Pixtral Large 2502",
                "nvidia/nemotron-3-ultra-550b-a55b:free" to "NVIDIA Nemotron 3 Ultra (Free)",
                "openai/gpt-6.2-astra-pro" to "GPT-6.2 Astra Pro"
            )
            var selectedModelId by remember {
                mutableStateOf(encryptedPrefs?.getString("openrouter_model_id", chatModels.first().first) ?: chatModels.first().first)
            }
            var modelMenuExpanded by remember { mutableStateOf(false) }
            val selectedModelName = chatModels.firstOrNull { it.first == selectedModelId }?.second ?: chatModels.first().second
            var status by remember { mutableStateOf("Подключаемся к часам…") }
            var busy by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(listOf<Pair<String, String>>()) }

            LaunchedEffect(Unit) {
                bridge.setCommandListener { raw ->
                    runOnUiThread {
                        try {
                            val command = JSONObject(raw)
                            if (command.optString("type") != "ai_prompt") return@runOnUiThread
                            val prompt = command.optString("prompt").trim().take(2500)
                            when {
                                prompt.isBlank() -> status = "Получен пустой вопрос."
                                apiKey.isBlank() -> {
                                    status = "Введи OpenRouter API key в этом приложении."
                                    bridge.send(WebPage("wristai://reply", "WristAI", "Сначала введи OpenRouter API key в приложении на телефоне."), emptyList()) { result -> status = result }
                                }
                                busy -> status = "ИИ уже готовит ответ. Подожди завершения."
                                else -> {
                                    busy = true
                                    val modelName = chatModels.firstOrNull { it.first == selectedModelId }?.second ?: selectedModelId
                                    status = "$modelName обрабатывает вопрос…"
                                    val messages = JSONArray()
                                    history.takeLast(4).forEach { turn ->
                                        messages.put(JSONObject()
                                            .put("role", if (turn.first == "model") "assistant" else turn.first)
                                            .put("content", turn.second))
                                    }
                                    messages.put(JSONObject().put("role", "user").put("content", prompt))
                                    val requestBody = JSONObject()
                                        .put("model", selectedModelId)
                                        .put("messages", messages)
                                        .put("max_tokens", 450)
                                    requestOpenRouter(apiKey.trim(), requestBody) { answer, error ->
                                        busy = false
                                        if (answer == null) {
                                            val message = "Ошибка OpenRouter / $modelName: " + (error ?: "неизвестная ошибка")
                                            status = message
                                            bridge.send(WebPage("wristai://reply", "WristAI", message), emptyList()) { result -> status = result }
                                        } else {
                                            history = (history + listOf("user" to prompt.take(600), "model" to answer.take(1000))).takeLast(4)
                                            status = "Ответ получен. Передаём на часы…"
                                            bridge.send(WebPage("wristai://reply", "WristAI", answer), emptyList()) { result -> status = result }
                                        }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            status = "Ошибка обработки сообщения: " + (error.message ?: "unknown")
                        }
                    }
                }
                bridge.connect { message -> runOnUiThread { status = message } }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("WristAI", fontSize = 30.sp, style = MaterialTheme.typography.headlineMedium)
                        Text("Телефон отправляет вопросы выбранной модели OpenRouter. Часы показывают облегчённую текстовую переписку.")
                        Text("Модель для ответов", style = MaterialTheme.typography.titleMedium)
                        Box {
                            OutlinedButton(onClick = { modelMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedModelName)
                            }
                            DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                chatModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.second) },
                                        onClick = {
                                            selectedModelId = model.first
                                            encryptedPrefs?.edit()?.putString("openrouter_model_id", model.first)?.apply()
                                            modelMenuExpanded = false
                                            status = "Выбрана модель: ${model.second}"
                                        }
                                    )
                                }
                            }
                        }
                        Text("Seedance 2.5 Flash — отдельная модель генерации видео, она не используется для текстовых ответов.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = apiKey, onValueChange = {
                                apiKey = it
                                encryptedPrefs?.edit()?.putString("openrouter_api_key", it)?.apply()
                            },
                            label = { Text("OpenRouter API key") },
                            placeholder = { Text("Вставь ключ OpenRouter (sk-or-...)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (encryptedPrefs != null) "Ключ сохраняется на телефоне в зашифрованном хранилище Android."
                            else "Не удалось открыть зашифрованное хранилище. Ключ не будет сохранён.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                status = "Повторно подключаемся к часам…"
                                bridge.connect { message -> runOnUiThread { status = message } }
                            }, modifier = Modifier.weight(1f)) { Text("Подключить часы") }
                            OutlinedButton(onClick = {
                                history = emptyList()
                                status = "История диалога очищена."
                            }, modifier = Modifier.weight(1f)) { Text("Новая беседа") }
                        }
                        OutlinedButton(
                            onClick = {
                                if (backgroundServiceEnabled.value) {
                                    stopBackgroundConnectionService()
                                } else if (
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    bluetoothPermissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                } else {
                                    startBackgroundConnectionService()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (backgroundServiceEnabled.value) "Выключить фоновый режим" else "Работа при выключенном экране")
                        }
                        Text(backgroundServiceMessage.value, style = MaterialTheme.typography.bodySmall)
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Состояние", style = MaterialTheme.typography.titleMedium)
                                Text(status)
                                if (busy) Text("Ожидаем ответ Gemini…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Последние сообщения", style = MaterialTheme.typography.titleMedium)
                        if (history.isEmpty()) Text("Пока нет сообщений. Введи вопрос на часах.")
                        else history.forEach { turn -> Text(if (turn.first == "user") "Ты: " + turn.second else "ИИ: " + turn.second) }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Первая версия — текстовая. Изображения будут отдельным beta-этапом после проверки текстового обмена.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
