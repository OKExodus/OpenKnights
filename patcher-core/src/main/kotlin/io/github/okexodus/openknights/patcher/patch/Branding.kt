package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.XmlElement

/**
 * The OpenKnights launcher icon: a bitmap per screen density for older launchers, and an adaptive icon (Android 8 and
 * newer) whose foreground layer is the portrait on a blurred copy of itself over a black background.
 */
class Branding(private val readFile: (String) -> ByteArray = { PatchData.bytes("branding/res/$it") }) {
    data class IconFiles(val iconId: Int, val files: Map<String, ByteArray>)

    /** Adds the icon resources to [resources]; returns the icon's resource id and the files to put in the APK. */
    fun addIcon(resources: ResourcePatch): IconFiles {
        val files = LinkedHashMap<String, ByteArray>()
        fun bitmaps(name: String): List<Pair<ByteArray, String>> = DENSITIES.map { (qualifier, density) ->
            val path = "res/drawable-$qualifier-v4/$name.png"
            files[path] = readFile("drawable-$qualifier-v4/$name.png")
            ResourcePatch.config(density = density) to path
        }
        val foreground = resources.addFileResource("drawable", FOREGROUND, bitmaps(FOREGROUND))
        val adaptivePath = "res/drawable-anydpi-v26/$ICON.xml"
        val icon = resources.addFileResource("drawable", ICON,
            bitmaps(ICON) + (ResourcePatch.config(density = ResourcePatch.DENSITY_ANY, sdk = 26) to adaptivePath))
        files[adaptivePath] = adaptiveIcon(foreground)
        return IconFiles(icon, files)
    }

    /** `<adaptive-icon>` with a black background and [foreground] as its foreground layer, as binary XML. */
    fun adaptiveIcon(foreground: Int): ByteArray {
        val root = XmlElement(null, "adaptive-icon")
        root.children += XmlElement(null, "background").also {
            it.set(BinaryXml.ANDROID_NS, "drawable", ATTR_DRAWABLE, ResValue.reference(COLOR_BLACK), raw = null)
        }
        root.children += XmlElement(null, "foreground").also {
            it.set(BinaryXml.ANDROID_NS, "drawable", ATTR_DRAWABLE, ResValue.reference(foreground), raw = null)
        }
        return BinaryXml.create(root).encode()
    }

    companion object {
        const val ICON = "openknights_icon"
        const val FOREGROUND = "openknights_icon_foreground"
        /** `android:drawable` and `@android:color/black`. */
        const val ATTR_DRAWABLE = 0x01010199
        const val COLOR_BLACK = 0x0106000c
        val DENSITIES = listOf("mdpi" to 160, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    }
}
