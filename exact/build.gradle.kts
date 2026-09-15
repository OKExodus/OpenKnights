plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}
// Main sources compile to JVM 11 bytecode so d8 can desugar them into the on-device DEX (Java-21
// invokedynamic such as SwitchBootstraps.typeSwitch is not DEX-compatible). Tests are never dexed, so they
// stay on the toolchain's JVM (21), which JUnit 6 requires; the consumable variant attribute still comes
// from the main compile task (11), so downstream modules keep consuming this one.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
tasks.named<JavaCompile>("compileJava") { options.release.set(11) }


dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
