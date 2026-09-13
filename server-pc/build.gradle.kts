plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":server-core"))
    runtimeOnly(libs.sqlite.jdbc)

    testRuntimeOnly(libs.sqlite.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.okexodus.openknights.server.pc.MainKt")
    applicationName = "openknights-server"
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    inputs.property("openknightsOriginals", providers.environmentVariable("OPENKNIGHTS_ORIGINALS").orElse(""))
    inputs.property("openknightsDevDir", providers.environmentVariable("OPENKNIGHTS_DEV_DIR").orElse(""))
    inputs.property("openknightsBundles", providers.environmentVariable("OPENKNIGHTS_BUNDLES").orElse(""))
    inputs.property("openknightsReleaseData", providers.environmentVariable("OPENKNIGHTS_RELEASE_DATA").orElse(""))
}

/**
 * The server on the PC for the development loop (plan §9.2): the same ports as on a device, reached from an emulator
 * through `adb reverse`. Pass the game file, the release data and a data root:
 *
 *     gradlew runServer --args="--apk <game APK> --release-data <folder> --data-root <folder>"
 */
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Runs the OpenKnights server on this PC (login 17777, game 19121, sign-in page 17778)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

/**
 * The differential harness on a maintainer's private fixture bundles (never in CI): replays every bundle under
 * OPENKNIGHTS_BUNDLES and writes build/bundle-reports/report.json.
 */
tasks.register<JavaExec>("runBundles") {
    group = "verification"
    description = "Replays the private fixture bundles in OPENKNIGHTS_BUNDLES against this server."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.okexodus.openknights.server.pc.harness.BundleRunnerKt")
    args(providers.environmentVariable("OPENKNIGHTS_BUNDLES").orElse("").get(), layout.buildDirectory.dir("bundle-reports").get().asFile.absolutePath)
}
