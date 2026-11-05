pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "168blocks"

// Shared libraries
include(":libs:pgecs") // ACID persistent ECS on PostgreSQL (backend, mods, Velocity plugins)
include(":libs:pgecs-ir") // first-level query IR of pgecs (DSL in, planner and SQL compiler out)
include(":libs:pgecs-eqsat") // equality saturation (egglog on Endive) for the pgecs query planner
include(":libs:pgecs-ksp") // KSP processor: pgecs query infix relations and write accessors
include(":libs:pgecs-lambda") // bytecode decompiler for pgecs query lambdas (where, select, orderBy, ...)
include(":libs:pgecs-query") // pgecs query engine: planner, eqsat optimisation, SQL compiler, plan cache
include(":libs:common") // JVM code shared by all server-side components (CBOR wire format, ...)
include(":libs:api") // Kotlin Multiplatform backend API model, shared with the frontend

// Services
include(":backend") // Ktor
include(":frontend") // Compose Multiplatform (web + desktop)

// Minecraft
include(":mods:example-common") // Fabric mod (Kotlin + Java mixins), server and client
include(":mods:e2e-client") // dev-only client mod: local RPC for end-to-end tests with real game clients
include(":velocity:example-plugin") // Velocity plugin
include(":mods:e2e-agent") // dev only: server state over HTTP for the e2e tests
include(":velocity:e2e-agent") // dev only: proxy state over HTTP for the e2e tests

// Network
include(":deploy") // stages the podman compose network (deploy/compose.yaml)
include(":e2e") // end-to-end tests against the running network
