package com.wristbrowser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PasswordVisualTransformation
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

    private fun requestGemini(apiKey: String, requestBody: JSONObject, callback: (String?, String?) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = try { JSONObject(responseText).optJSONObject("error")?.optString("message") }
                    catch (_: Exception) { null }
                    throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Gemini API HTTP $code")
                }
                val json = JSONObject(responseText)
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val answer = buildString {
                    if (parts != null) for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
                    }
                }.trim()
                if (answer.isBlank()) throw IllegalStateException("Gemini вернул пустой ответ")
                mainHandler.post { callback(answer, null) }
            } catch (error: Exception) {
                mainHandler.post { callback(null, error.message ?: "Не удалось связаться с Gemini") }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val bridge = remember { WearableBridge(applicationContext) }
            var apiKey by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("Подключаемся к часам…") }
            var busy by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(listOf<Pair<String, String>>()) }

            LaunchedEffect(Unit) {
                bridge.setCommandListener { raw ->
                    runOnUiThread {
                        try {
                            val command = JSONObject(raw)
                            if (command.optString("type") != "ai_prompt") return@runOnUiThread
                            val prompt = command.optString("prompt").trim().take(4000)
                            when {
                                prompt.isBlank() -> status = "Получен пустой вопрос."
                                apiKey.isBlank() -> {
                                    status = "Введи Gemini API key в этом приложении."
                                    bridge.send(WebPage("wristai://reply", "WristAI", "Сначала введи Gemini API key в приложении на телефоне."), emptyList()) { result -> status = result }
                                }
                                busy -> status = "ИИ уже готовит ответ. Подожди завершения."
                                else -> {
                                    busy = true
                                    status = "Gemini обрабатывает вопрос…"
                                    val contents = JSONArray()
                                    history.forEach { turn ->
                                        contents.put(JSONObject().put("role", turn.first)
                                            .put("parts", JSONArray().put(JSONObject().put("text", turn.second))))
                                    }
                                    contents.put(JSONObject().put("role", "user")
                                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                                    val requestBody = JSONObject().put("contents", contents)
                                        .put("generationConfig", JSONObject().put("maxOutputTokens", 1400))
                                    requestGemini(apiKey.trim(), requestBody) { answer, error ->
                                        busy = false
                                        if (answer == null) {
                                            val message = "Ошибка Gemini: " + (error ?: "неизвестная ошибка")
                                            status = message
                                            bridge.send(WebPage("wristai://reply", "WristAI", message), emptyList()) { result -> status = result }
                                        } else {
                                            history = (history + listOf("user" to prompt, "model" to answer)).takeLast(12)
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
                        Text("Телефон — сервер ИИ. Часы отправляют текстовые вопросы, телефон обращается к Gemini и возвращает ответы.")
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it },
                            label = { Text("Gemini API key") },
                            placeholder = { Text("Вставь ключ Google AI Studio") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Text("Ключ хранится только в памяти приложения и не записывается в репозиторий или на диск.",
                            style = MaterialTheme.typography.bodySmall)
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
