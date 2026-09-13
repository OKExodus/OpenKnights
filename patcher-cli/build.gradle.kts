plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":patcher-core"))
}

application {
    mainClass.set("io.github.okexodus.openknights.patcher.cli.MainKt")
    applicationName = "openknights-patcher"
}
