plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.apksig)
    implementation(libs.smali)
    implementation(libs.smali.baksmali)
    implementation(libs.smali.dexlib2)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val version = providers.gradleProperty("openknights.version").get()
    inputs.property("openknightsVersion", version)
    filesMatching("io/github/okexodus/openknights/patcher/build-info.properties") {
        expand("version" to version)
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Tests against a real copy of the game run only when this points at the folder holding it (never in CI).
    inputs.property("openknightsOriginals", providers.environmentVariable("OPENKNIGHTS_ORIGINALS").orElse(""))
}
