plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":exact"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // The private frame corpus (captured sessions) is read only when this points at it (never in CI).
    inputs.property("openknightsCorpus", providers.environmentVariable("OPENKNIGHTS_CORPUS").orElse(""))
}
