plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":patcher-core"))

    testImplementation(testFixtures(project(":patcher-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.okexodus.openknights.patcher.cli.MainKt")
    applicationName = "openknights-patcher"
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

val openknightsVersion = providers.gradleProperty("openknights.version")
val releaseName = openknightsVersion.map { "OpenKnights-Patcher-$it" }
val toolchainHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath }

/** A Java runtime with only the modules the patcher needs, from the build's own JDK (for this operating system). */
val runtimeImage = tasks.register<Exec>("runtimeImage") {
    val output = layout.buildDirectory.dir("runtime-image")
    val jdk = toolchainHome
    outputs.dir(output)
    inputs.property("jdk", jdk.map { it.asFile.absolutePath })
    doFirst { output.get().asFile.deleteRecursively() }
    val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    executable = jdk.get().file(if (windows) "bin/jlink.exe" else "bin/jlink").asFile.absolutePath
    args(
        "--add-modules", "java.base,java.compiler,java.desktop,java.logging,jdk.unsupported",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6",
        "--output", output.get().asFile.absolutePath,
    )
}

/**
 * The release folder: the launch scripts, README.txt, empty original/ and patched/ folders, the patcher in app/ and a
 * runtime in runtime/ (so nothing has to be installed).
 */
val releaseFolder = tasks.register<Sync>("releaseFolder") {
    val folder = layout.buildDirectory.dir(releaseName.map { "release/$it" })
    into(folder)
    from(layout.projectDirectory.dir("release")) {
        exclude("openknights-patcher")
        filePermissions { unix("rw-r--r--") }
    }
    from(layout.projectDirectory.file("release/openknights-patcher")) {
        filePermissions { unix("rwxr-xr-x") }
    }
    into("app") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
    into("runtime") {
        from(runtimeImage)
    }
    doLast {
        folder.get().asFile.resolve("original").mkdirs()
        folder.get().asFile.resolve("patched").mkdirs()
    }
}
