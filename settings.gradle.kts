pluginManagement {
    repositories {
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Google's Maven repository, used only for the Android tool libraries (apksig, smali).
        google {
            content {
                includeGroup("com.android.tools.build")
                includeGroup("com.android.tools.smali")
            }
        }
    }
}

rootProject.name = "OpenKnights"

include("exact")
include("protocol")
include("game-data")
include("server-core")
include("server-pc")
include("patcher-core")
include("patcher-cli")
