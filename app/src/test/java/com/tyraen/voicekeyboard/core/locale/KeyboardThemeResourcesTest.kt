package com.tyraen.voicekeyboard.core.locale

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Guards theme failures that are easy to miss when functional tests can still click a key. */
class KeyboardThemeResourcesTest {
    private val resources = File("src/main/res")

    private fun document(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    @Test fun `platform image buttons do not depend on an unsupported app tint attribute`() {
        val doc = document(File(resources, "layout/input_panel.xml"))
        val buttons = doc.getElementsByTagName("ImageButton")
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) as Element
            val supportTint = button.getAttributeNS("http://schemas.android.com/apk/res-auto", "tint")
            assertTrue(
                "Platform ImageButton ignores app:tint: ${button.getAttributeNS(ANDROID, "id")}",
                supportTint.isEmpty()
            )
        }
    }

    @Test fun `enabled keyboard labels remain readable in light and dark resources`() {
        val light = colors("values")
        val dark = light + colors("values-night")
        for ((name, palette) in listOf("light" to light, "dark" to dark)) {
            for (surface in listOf("keyboard_bg", "key_bg", "correction_letter_surface", "correction_control_surface")) {
                val ratio = contrast(palette.getValue("key_text"), palette.getValue(surface))
                assertTrue("$name key labels on $surface have contrast $ratio", ratio >= 4.5)
            }
            val primary = contrast(palette.getValue("white"), palette.getValue("mic_idle"))
            assertTrue("$name primary action labels have contrast $primary", primary >= 4.5)
        }
    }

    private fun colors(directory: String): Map<String, Int> {
        return File(resources, directory).listFiles { file -> file.extension == "xml" }!!
            .sortedBy { it.name }.flatMap { file ->
                val nodes = document(file).getElementsByTagName("color")
                (0 until nodes.length).map { index ->
                    val element = nodes.item(index) as Element
                    element.getAttribute("name") to element.textContent.trim().removePrefix("#").toLong(16).toInt()
                }
            }.toMap()
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
