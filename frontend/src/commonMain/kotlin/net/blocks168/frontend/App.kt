package net.blocks168.frontend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Example page: a counter stored by the backend (in PostgreSQL). */
@Composable
fun App(backend: BackendClient) {
    var value by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { backend.counter() }.onSuccess { value = it.value }.onFailure { error = it.message }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("168blocks", style = MaterialTheme.typography.headlineMedium)
                Text(counterLabel(value))
                Button(onClick = {
                    scope.launch {
                        runCatching { backend.increment() }
                            .onSuccess { value = it.value; error = null }
                            .onFailure { error = it.message }
                    }
                }) { Text("Increment") }
                error?.let { Text("Backend error: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

fun counterLabel(value: Long?): String = if (value == null) "Counter: loading…" else "Counter: $value"
