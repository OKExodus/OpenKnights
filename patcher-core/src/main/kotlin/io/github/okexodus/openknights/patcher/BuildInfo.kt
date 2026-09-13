package io.github.okexodus.openknights.patcher

import java.util.Properties

/** Facts about this build of OpenKnights, written into the resources by the build. */
object BuildInfo {
    val version: AppVersion by lazy {
        val properties = Properties()
        val stream = BuildInfo::class.java.getResourceAsStream("build-info.properties")
            ?: error("build-info.properties is missing from the patcher resources")
        stream.use(properties::load)
        AppVersion.parse(properties.getProperty("version") ?: error("build-info.properties has no version"))
    }
}
