package com.wristbrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val bridge = remember { WearableBridge(applicationContext) }
            var address by remember { mutableStateOf("https://example.com") }
            var page by remember { mutableStateOf<WebPage?>(null) }
            var status by remember { mutableStateOf("Проверяем связь с часами…") }
            var busy by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) { bridge.connect { status = it } }
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("WristBrowser", style = MaterialTheme.typography.headlineMedium)
                        Text("Читаемые веб-страницы на Xiaomi Watch S4")
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            label = { Text("Адрес сайта") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !busy && address.isNotBlank(),
                            onClick = {
                                busy = true
                                status = "Загружаем страницу…"
                                scope.launch {
                                    try {
                                        val loaded = withContext(Dispatchers.IO) { PageLoader.load(address.trim()) }
                                        page = loaded
                                        status = "Страница загружена: ${loaded.title}"
                                    } catch (e: Exception) {
                                        status = "Не удалось загрузить: ${e.message ?: "ошибка"}"
                                    } finally { busy = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Загрузка…" else "Открыть сайт") }

                        Text(status, style = MaterialTheme.typography.bodyMedium)
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
                                        if (message.startsWith("Данные переданы")) busy = false
                                        if (message.startsWith("Ошибка")) busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Передать на часы") }
                        }
                    }
                }
            }
        }
    }
}
