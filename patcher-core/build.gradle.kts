plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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

    // Made-up games and APKs for tests (no game data), shared with the command line's tests.
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.smali.dexlib2)

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
    // Our own patch data (smali, byte patches, branding) from the repository's patches/ folder.
    from(rootProject.layout.projectDirectory.dir("patches")) {
        into("io/github/okexodus/openknights/patches")
    }
}

/** Maintainer tool: compares two patched APKs (see tools/ApkComparison.kt in the tests). Not part of the patcher. */
tasks.register<JavaExec>("compareApks") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.okexodus.openknights.patcher.tools.ApkComparisonKt")
    maxHeapSize = "3g"
    args(listOf("ours", "earlier", "original", "out").map { providers.gradleProperty(it).orElse("").get() })
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Tests against a real copy of the game run only when this points at the folder holding it (never in CI).
    inputs.property("openknightsOriginals", providers.environmentVariable("OPENKNIGHTS_ORIGINALS").orElse(""))
}
