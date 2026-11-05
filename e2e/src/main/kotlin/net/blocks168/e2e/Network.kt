package net.blocks168.e2e

/**
 * Addresses of the network under test. Defaults match deploy/compose.yaml; override with
 * `BLOCKS_E2E_EDGES` (comma-separated host:port) and `BLOCKS_E2E_BACKEND` (URL) to test another
 * deployment, e.g. staging.
 */
data class Network(val edges: List<Endpoint>, val backendUrl: String) {
    data class Endpoint(val host: String, val port: Int) {
        override fun toString() = "$host:$port"
    }

    /** Edge proxy [n], counted from 1 like the compose services (edge-1 ...) */
    fun edge(n: Int): Endpoint = edges[n - 1]

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Network(
            edges = (env["BLOCKS_E2E_EDGES"] ?: "127.0.0.1:25501,127.0.0.1:25502,127.0.0.1:25503")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                    val (host, port) = it.split(':', limit = 2)
                    Endpoint(host, port.toInt())
                },
            backendUrl = env["BLOCKS_E2E_BACKEND"] ?: "http://127.0.0.1:8080",
        )
    }
}
