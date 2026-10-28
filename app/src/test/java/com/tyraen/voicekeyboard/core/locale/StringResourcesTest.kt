package com.tyraen.voicekeyboard.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks every string-resource file against the default English resources. Lint only warns
 * about a missing translation, and a wrong format specifier is not caught at build time: it crashes
 * `getString(id, args)` on the device, inside the keyboard for panel strings.
 */
class StringResourcesTest {

    private val resDir = File("src/main/res")

    private data class StringResource(val text: String, val translatable: Boolean, val file: File)

    // Tokenised like java.util.Formatter, so the escaped "%%" is one token and not the start of "% f".
    private val specifier = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?([tT]?[a-zA-Z]|%)""")

    private fun load(directory: File): Map<String, StringResource> {
        val strings = linkedMapOf<String, StringResource>()
        directory.listFiles { file -> file.isFile && file.extension == "xml" }!!
            .sortedBy { it.name }
            .forEach { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = doc.getElementsByTagName("string")
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as Element
                    val name = element.getAttribute("name")
                    val previous = strings[name]
                    assertTrue(
                        "$directory/$name is defined in both ${previous?.file?.name} and ${file.name}",
                        previous == null
                    )
                    strings[name] = StringResource(
                        element.textContent,
                        element.getAttribute("translatable") != "false",
                        file
                    )
                }
            }
        return strings
    }

    private fun specifiers(text: String) = specifier.findAll(text).map { it.value }
        .filter { it != "%%" && it != "%n" }.sorted().toList()

    private val base by lazy { load(File(resDir, "values")) }

    private val locales by lazy {
        resDir.listFiles { file -> file.isDirectory && file.name.matches(Regex("values-[a-z]{2,3}")) }!!
            .sortedBy { it.name }
            .associate { it.name to load(it) }
    }

    @Test fun `every interface language has translated resources`() {
        val expected = TranscriptionLocale.entries.filter { it.code != "en" }
            .map { "values-${it.code}" }.toSet()
        assertEquals(expected, locales.keys)
        assertTrue("English resources are missing", base.isNotEmpty())
    }

    @Test fun `every translation defines every translatable string`() {
        for ((dir, strings) in locales) {
            val translatable = base.filterValues { it.translatable }.keys
            val missing = translatable - strings.keys
            assertTrue("$dir is missing $missing", missing.isEmpty())
        }
    }

    @Test fun `translations use the same format specifiers as English`() {
        for ((dir, strings) in locales) {
            for ((name, text) in strings) {
                val english = base[name] ?: continue
                assertEquals("$dir/$name", specifiers(english.text), specifiers(text.text))
            }
        }
    }
}
