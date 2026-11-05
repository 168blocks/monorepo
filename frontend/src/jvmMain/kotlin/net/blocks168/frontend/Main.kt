package net.blocks168.frontend

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Desktop build; `BLOCKS_BACKEND_URL` defaults to a backend on localhost. */
fun main() = application {
    val backend = BackendClient(System.getenv("BLOCKS_BACKEND_URL") ?: "http://localhost:8080")
    Window(onCloseRequest = ::exitApplication, title = "168blocks") { App(backend) }
}
