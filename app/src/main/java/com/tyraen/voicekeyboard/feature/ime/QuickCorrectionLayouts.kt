package com.tyraen.voicekeyboard.feature.ime

import java.util.Locale

/** Small direct-input layouts for corrections; these deliberately do not implement a language IME. */
data class QuickCorrectionLayout(
    val code: String,
    val nativeName: String,
    val rows: List<List<String>>,
    val extraCharacters: List<String>,
    val hasCase: Boolean = true
) {
    fun letter(value: String, uppercase: Boolean): String =
        if (uppercase && hasCase) value.uppercase(Locale(code)) else value
}

object QuickCorrectionLayouts {
    private fun keys(text: String) = text.map { it.toString() }
    private fun latin(code: String, name: String, top: String = "qwertyuiop",
        middle: String = "asdfghjkl", bottom: String = "zxcvbnm", extras: String = "") =
        QuickCorrectionLayout(code, name, listOf(keys(top), keys(middle), keys(bottom)), keys(extras))

    val all: List<QuickCorrectionLayout> = listOf(
        latin("en", "English · QWERTY", extras = "áàâäæçéèêëíìîïñóòôöœúùûüÿ"),
        latin("fr", "Français · AZERTY", "azertyuiop", "qsdfghjklm", "wxcvbn", "éèêëàâæçîïôœùûüÿ"),
        latin("de", "Deutsch · QWERTZ", "qwertzuiopü", "asdfghjklöä", "yxcvbnm", "ßẞéèáà"),
        latin("es", "Español · QWERTY", middle = "asdfghjklñ", extras = "áéíóúü¿¡"),
        latin("it", "Italiano · QWERTY", extras = "àèéìíòóùú"),
        latin("pt", "Português · QWERTY", middle = "asdfghjklç", extras = "áàâãéêíóôõúü"),
        latin("pl", "Polski · QWERTY", extras = "ąćęłńóśźż"),
        latin("fi", "Suomi · QWERTY", "qwertyuiopå", "asdfghjklöä", extras = "šžé"),
        latin("tr", "Türkçe · Q", "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç", "âîû"),
        latin("el", "Ελληνικά", "ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ", "άέήίόύώϊϋΐΰ"),
        latin("be", "Беларуская · ЙЦУКЕН", "йцукенгшўзх", "фывапролджэ", "ячсмітьбю", "ё’"),
        latin("ru", "Русский · ЙЦУКЕН", "йцукенгшщзх", "фывапролджэ", "ячсмитьбю", "ёъ"),
        QuickCorrectionLayout("hi", "हिन्दी",
            keys("अआइईउऊऋएऐओऔकखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसह").chunked(11),
            keys("ािीुूृेैोौंःँ़्ड़ढ़।॥"), hasCase = false),
        QuickCorrectionLayout("ar", "العربية", listOf(
            keys("ضصثقفغعهخحجد"), keys("شسيبلاتنمكط"),
            listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ")
        ), keys("ذأإآؤئءىةًٌٍَُِّْـ؟،؛"), hasCase = false)
    )

    fun resolve(code: String?): QuickCorrectionLayout? = all.firstOrNull { it.code == code }

    /** UI locale is only an initial suggestion; a stored layout is never replaced by it. */
    fun initial(storedCode: String?, interfaceCode: String): QuickCorrectionLayout =
        resolve(storedCode) ?: resolve(interfaceCode) ?: all.first()

    val symbols = listOf(
        keys("1234567890"), keys("@#€$%&*+-="),
        keys("()[]{}<>_\\"), keys(".,?!:;\"'/|")
    )

    fun label(value: String): String {
        val first = value.codePointAt(0)
        return when (Character.getType(first)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> "◌$value"
            else -> value
        }
    }
}
