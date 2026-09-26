package com.tyraen.voicekeyboard.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the translations against `values/strings.xml`. Lint only warns about a missing
 * translation, and a wrong format specifier is not caught at build time at all: it crashes
 * `getString(id, args)` on the device, inside the keyboard for panel strings.
 */
class StringResourcesTest {

    private val resDir = File("src/main/res")

    /** Left in English on purpose: the app name and the default endpoint, model and prompt. */
    private val untranslated = setOf(
        "app_name", "default_endpoint", "default_model", "default_language", "default_prompt"
    )

    // Tokenised like java.util.Formatter, so the escaped "%%" is one token and not the start of "% f".
    private val specifier = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?([tT]?[a-zA-Z]|%)""")

    private fun load(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun specifiers(text: String) = specifier.findAll(text).map { it.value }
        .filter { it != "%%" && it != "%n" }.sorted().toList()

    private val base by lazy { load(File(resDir, "values/strings.xml")) }

    private val locales by lazy {
        resDir.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .sortedBy { it.name }
            .associate { it.name to load(File(it, "strings.xml")) }
    }

    @Test fun `all 16 translations are found`() {
        assertEquals(16, locales.size)
    }

    @Test fun `every translation defines every translatable string`() {
        for ((dir, strings) in locales) {
            val missing = base.keys - untranslated - strings.keys
            assertTrue("$dir is missing $missing", missing.isEmpty())
        }
    }

    @Test fun `translations use the same format specifiers as English`() {
        for ((dir, strings) in locales) {
            for ((name, text) in strings) {
                val english = base[name] ?: continue
                assertEquals("$dir/$name", specifiers(english), specifiers(text))
            }
        }
    }
}
