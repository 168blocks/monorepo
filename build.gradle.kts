// Plugins live in build-logic (convention plugins: blocks.kotlin-jvm, blocks.fabric-mod, ...).
// Every module applies them by id; see AGENTS.md for the layout.
//
//   ./dev ./gradlew build            everything, with unit and integration tests
//   ./dev tools/network.sh test      the podman compose network and the end-to-end tests

plugins {
    // Puts build-logic (and with it every plugin it depends on: Kotlin, Compose, Loom) on the root
    // classpath, so subprojects can apply those plugins by id without a version.
    id("blocks.repositories") apply false
}
