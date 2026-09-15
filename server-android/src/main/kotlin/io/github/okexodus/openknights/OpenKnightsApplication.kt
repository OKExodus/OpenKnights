package io.github.okexodus.openknights

import android.app.Application
import io.github.okexodus.openknights.server.android.AndroidServerHost

/**
 * The patched app's [Application]. The patcher points `<application android:name>` at this class (the game ships with
 * the default `android.app.Application`, so there is nothing to chain to), and it boots the in-process OpenKnights
 * server as early as possible, before any activity connects to the loopback listeners.
 */
class OpenKnightsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidServerHost.boot(this)
    }
}
