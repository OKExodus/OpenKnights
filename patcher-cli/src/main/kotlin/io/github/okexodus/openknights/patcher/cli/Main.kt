package io.github.okexodus.openknights.patcher.cli

import io.github.okexodus.openknights.patcher.BuildInfo
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    println("OpenKnights Patcher ${BuildInfo.version}")
    if (args.singleOrNull() == "--version") return
    println("This is a development build: patching is not available yet.")
    exitProcess(if (args.isEmpty()) 0 else 2)
}
