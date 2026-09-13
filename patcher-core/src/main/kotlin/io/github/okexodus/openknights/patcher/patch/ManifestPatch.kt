package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.AppVersion
import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.XmlElement

/**
 * The app manifest as OpenKnights needs it: its own package, name, icon and version; one APK instead of a split set;
 * native code extracted on install; no backup of game data by the system; plain HTTP to the local server allowed; and
 * none of the publisher's sign-in, payment, analytics or advertising components. Java class names stay unchanged.
 */
class ManifestPatch(
    val packageName: String,
    val label: String,
    val version: AppVersion,
    /** The launcher icon resource, or null to keep the game's own icon. */
    val iconResource: Int?,
) {
    data class Result(val manifest: ByteArray, val removedComponents: List<String>, val removedAttributes: List<String>)

    fun apply(original: ByteArray): Result {
        val xml = BinaryXml.read(original)
        val root = xml.root
        if (root.name != "manifest") mismatch("the root element is <${root.name}>")
        val removedAttributes = ArrayList<String>()
        root.set(null, "package", 0, ResValue.string(packageName))
        root.set(ANDROID, "versionCode", VERSION_CODE, ResValue.int(version.versionCode), raw = null)
        root.set(ANDROID, "versionName", VERSION_NAME, ResValue.string(version.toString()))
        for (name in listOf("requiredSplitTypes", "splitTypes", "isSplitRequired")) {
            if (root.remove(ANDROID, name)) removedAttributes += "manifest android:$name"
        }
        val removed = ArrayList<String>()
        root.children.removeIf { it is XmlElement && it.name == "queries" && removed.add("<queries>") }

        val application = root.elements.singleOrNull { it.name == "application" } ?: mismatch("there is no single <application>")
        application.set(ANDROID, "label", LABEL, ResValue.string(label))
        iconResource?.let { application.set(ANDROID, "icon", ICON, ResValue.reference(it), raw = null) }
        application.set(ANDROID, "allowBackup", ALLOW_BACKUP, ResValue.boolean(false), raw = null)
        application.set(ANDROID, "extractNativeLibs", EXTRACT_NATIVE_LIBS, ResValue.boolean(true), raw = null)
        application.set(ANDROID, "usesCleartextTraffic", USES_CLEARTEXT_TRAFFIC, ResValue.boolean(true), raw = null)
        if (application.remove(ANDROID, "fullBackupContent")) removedAttributes += "application android:fullBackupContent"

        var splash = 0
        application.children.removeIf { node ->
            val e = node as? XmlElement ?: return@removeIf false
            val name = e.androidAttribute("name")?.value?.string ?: ""
            val drop = e.name == "provider" || e.name == "service" ||
                ((e.name == "activity" || e.name == "receiver") && !name.startsWith("com.gamed9.")) ||
                (e.name == "meta-data" && name !in KEPT_META_DATA)
            if (drop) removed += "<${e.name}> $name"
            drop
        }
        for (activity in application.elements.filter { it.name == "activity" }) {
            val name = activity.androidAttribute("name")?.value?.string ?: continue
            if (!name.endsWith("SplashActivity")) continue
            splash++
            activity.set(ANDROID, "label", LABEL, ResValue.string(label))
            activity.children.removeIf { node ->
                val filter = node as? XmlElement ?: return@removeIf false
                val drop = filter.name == "intent-filter" &&
                    filter.elements.none { it.name == "action" && it.androidAttribute("name")?.value?.string == "android.intent.action.MAIN" }
                if (drop) removed += "<intent-filter> of $name without MAIN"
                drop
            }
            val launcher = activity.elements.any { f -> f.name == "intent-filter" && f.elements.any { it.androidAttribute("name")?.value?.string == "android.intent.action.MAIN" } }
            if (!launcher) mismatch("$name has no MAIN intent filter")
        }
        if (splash != 1) mismatch("expected one launch activity (SplashActivity), found $splash")
        for (kept in KEPT_META_DATA) {
            if (application.elements.none { it.name == "meta-data" && it.androidAttribute("name")?.value?.string == kept }) mismatch("the meta-data $kept is missing")
        }
        return Result(xml.encode(), removed, removedAttributes)
    }

    private fun mismatch(what: String): Nothing =
        throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "The app manifest is not as expected: $what. Nothing was patched.")

    companion object {
        private const val ANDROID = BinaryXml.ANDROID_NS
        const val LABEL = 0x01010001
        const val ICON = 0x01010002
        const val VERSION_CODE = 0x0101021b
        const val VERSION_NAME = 0x0101021c
        const val ALLOW_BACKUP = 0x01010280
        const val EXTRACT_NATIVE_LIBS = 0x010104ea
        const val USES_CLEARTEXT_TRAFFIC = 0x010104ec
        val KEPT_META_DATA = listOf("CLIENT_CHANNEL", "CLIENT_RECHARGE_ID")
    }
}
