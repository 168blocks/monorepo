plugins {
    id("blocks.velocity-plugin")
}

description = "Dev-only Velocity plugin exposing proxy state over HTTP for the end-to-end tests (docs/testing.md)"

// No dependencies of its own: the HTTP server is the JDK's (com.sun.net.httpserver), JSON is Velocity's Gson.
