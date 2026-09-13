plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(project(":game-data"))

    testRuntimeOnly(libs.sqlite.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Local-only proofs read a maintainer's private data (never in CI): the game file and the private dev folder.
    inputs.property("openknightsOriginals", providers.environmentVariable("OPENKNIGHTS_ORIGINALS").orElse(""))
    inputs.property("openknightsDevDir", providers.environmentVariable("OPENKNIGHTS_DEV_DIR").orElse(""))
}
