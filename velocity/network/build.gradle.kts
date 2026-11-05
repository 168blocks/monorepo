plugins {
    id("blocks.velocity-plugin")
}

description = "Network plugin of every edge Velocity: online players, cross-edge actions (LISTEN/NOTIFY), drain, maintenance"

dependencies {
    implementation(project(":libs:pgecs"))
    testImplementation(testFixtures(project(":libs:pgecs")))
}
