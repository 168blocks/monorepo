package dev.vxcc.b8.mod.creativehost

import java.nio.file.Path
import java.time.Duration

/**
 * One server to start in the host's JVM.
 *
 * @property id name for logs and [CreativeHost.server]; unique among running hosted servers
 * @property directory the server's own directory: `server.properties`, the `world` directory, user cache
 * @property port TCP port (`server-port`); the host chooses it, nothing here picks a free one
 * @property template a world directory copied to `<directory>/world` when that does not exist yet (an
 *   existing world is reused as it is); null creates a new world from `server.properties`. A template
 *   with its spawn area already generated starts in a fraction of a second; generating a world takes seconds.
 * @property properties `server.properties` entries over the host's own (`level-name` is always `world`)
 * @property shareResources reuse the host's datapack resources and registries when the world selects the
 *   same datapacks and features as the host (otherwise the server loads its own, which takes 0.4–1.3 s)
 * @property startTimeout how long [CreativeHost.start] waits for the first tick
 */
data class HostedServerSpec(
    val id: String,
    val directory: Path,
    val port: Int,
    val template: Path? = null,
    val properties: Map<String, String> = emptyMap(),
    val shareResources: Boolean = true,
    val startTimeout: Duration = Duration.ofMinutes(1),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(port in 1..65535) { "port $port out of range" }
    }
}
