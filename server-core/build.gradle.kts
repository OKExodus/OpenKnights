plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}
// Main sources compile to JVM 11 bytecode so d8 can desugar them into the on-device DEX (Java-21
// invokedynamic such as SwitchBootstraps.typeSwitch is not DEX-compatible). Tests are never dexed, so they
// stay on the toolchain's JVM (21), which JUnit 6 requires; the consumable variant attribute still comes
// from the main compile task (11), so :server-pc keeps consuming this module.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
tasks.named<JavaCompile>("compileJava") { options.release.set(11) }


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
