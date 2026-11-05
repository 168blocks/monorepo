plugins {
    id("blocks.fabric-mod")
}

description = "Fixes for several MinecraftServers in one JVM (creative host, #6): JVM exits, shared executors, crashes"

dependencies {
    // @TargetHandler, to cancel C2ME's System.exit handler; C2ME and Async ship MixinSquared themselves,
    // we nest it too so the fix does not depend on their bundling
    implementation(libs.mixinsquared)
    include(libs.mixinsquared)
}
