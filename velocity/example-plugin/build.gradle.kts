plugins {
    id("blocks.velocity-plugin")
}

description = "Example Velocity plugin (Kotlin)"

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:pgecs"))
}
