package dev.vxcc.b8.frontend

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/** Web build, served by the backend; talks to the backend on the page's own origin. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App(BackendClient(baseUrl = "")) }
}
