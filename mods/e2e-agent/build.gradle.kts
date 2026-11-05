plugins {
    id("blocks.fabric-mod")
}

description = "Dev-only Fabric server mod exposing server state over HTTP for the end-to-end tests (docs/testing.md)"

// Server-only and without dependencies of its own: the HTTP server is the JDK's (com.sun.net.httpserver)
// and JSON is Gson, which Minecraft ships.
