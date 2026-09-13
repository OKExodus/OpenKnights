plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
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
}
