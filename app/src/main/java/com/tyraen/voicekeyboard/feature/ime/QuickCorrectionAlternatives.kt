package com.tyraen.voicekeyboard.feature.ime

/** Related characters for a held key. The complete character palette remains available separately. */
object QuickCorrectionAlternatives {
    private fun chars(text: String): List<String> = text.map(Char::toString)
    private fun groups(vararg entries: Pair<String, String>): Map<String, List<String>> =
        entries.associate { (base, values) -> base to chars(values) }

    private val alternatives = mapOf(
        "en" to groups("a" to "áàâäæ", "c" to "ç", "e" to "éèêë", "i" to "íìîï",
            "n" to "ñ", "o" to "óòôöœ", "u" to "úùûü", "y" to "ÿ"),
        "fr" to groups("a" to "àâæ", "c" to "ç", "e" to "éèêë", "i" to "îï",
            "o" to "ôœ", "u" to "ùûü", "y" to "ÿ"),
        "de" to groups("a" to "äáà", "e" to "éè", "o" to "ö", "s" to "ß", "u" to "ü"),
        "es" to groups("a" to "á", "e" to "é", "i" to "í", "n" to "ñ", "o" to "ó", "u" to "úü"),
        "it" to groups("a" to "à", "e" to "èé", "i" to "ìí", "o" to "òó", "u" to "ùú"),
        "pt" to groups("a" to "áàâã", "c" to "ç", "e" to "éê", "i" to "í", "o" to "óôõ", "u" to "úü"),
        "pl" to groups("a" to "ą", "c" to "ć", "e" to "ę", "l" to "ł", "n" to "ń",
            "o" to "ó", "s" to "ś", "z" to "źż"),
        "fi" to groups("a" to "äå", "e" to "é", "o" to "ö", "s" to "š", "z" to "ž"),
        "tr" to groups("a" to "â", "g" to "ğ", "ı" to "î", "i" to "î", "o" to "ö",
            "s" to "ş", "u" to "üû", "c" to "ç"),
        "el" to groups("α" to "ά", "ε" to "έ", "η" to "ή", "ι" to "ίϊΐ", "ο" to "ό",
            "υ" to "ύϋΰ", "ω" to "ώ", "σ" to "ς"),
        "be" to groups("е" to "ё", "у" to "ў"),
        "ru" to groups("е" to "ё", "ь" to "ъ"),
        "hi" to mapOf(
            "आ" to listOf("ा"), "इ" to listOf("ि"), "ई" to listOf("ी"),
            "उ" to listOf("ु"), "ऊ" to listOf("ू"), "ऋ" to listOf("ृ"),
            "ए" to listOf("े"), "ऐ" to listOf("ै"), "ओ" to listOf("ो"), "औ" to listOf("ौ"),
            "ड" to listOf("ड़"), "ढ" to listOf("ढ़")
        ),
        "ar" to groups("ا" to "أإآ", "د" to "ذ", "و" to "ؤ", "ي" to "ئى", "ه" to "ة")
    )

    fun forKey(layout: QuickCorrectionLayout, key: String, uppercase: Boolean): List<String> =
        alternatives[layout.code]?.get(key).orEmpty().map { value ->
            if (uppercase && value == "ß") "ẞ" else layout.letter(value, uppercase)
        }.distinct()
}
