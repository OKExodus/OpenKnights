import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Same toolchain as the other modules; d8 desugars the Java-21 bytecode for the on-device DEX (min-api 26).
    jvmToolchain(21)
}

/** The platform android.jar to compile the on-device driver against (compileOnly: the real classes ship with the OS). */
fun androidJar(): File {
    val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: (project.findProperty("android.sdk.dir") as String?)
        ?: error("Set ANDROID_HOME / ANDROID_SDK_ROOT to the Android SDK to build :server-android")
    val jar = File(home, "platforms/android-35/android.jar")
    require(jar.isFile) { "android.jar not found at $jar (install platforms;android-35)" }
    return jar
}

dependencies {
    api(project(":server-core"))
    compileOnly(files(androidJar()))
}
