package com.tyraen.voicekeyboard.core.locale

data class LocaleEntry(
    val code: String,
    val displayName: String,
    val defaultPrompt: String
)

object TranscriptionLocale {

    val entries: List<LocaleEntry> = listOf(
        LocaleEntry("de", "Deutsch",
            "Hallo, wie geht es Ihnen? Mir geht es gut. Dies ist ein korrekt formatierter Satz, mit Zeichensetzung und Großschreibung."),
        LocaleEntry("en", "English",
            "Hello, how are you? I'm doing well. This is a properly formatted sentence, with punctuation and capitalization."),
        LocaleEntry("es", "Español",
            "Hola, ¿cómo estás? Estoy bien. Esta es una oración formateada correctamente, con puntuación y mayúsculas."),
        LocaleEntry("fr", "Français",
            "Bonjour, comment allez-vous ? Je vais bien. Ceci est une phrase correctement formatée, avec ponctuation et majuscules."),
        LocaleEntry("it", "Italiano",
            "Ciao, come stai? Sto bene. Questa è una frase formattata correttamente, con punteggiatura e maiuscole."),
        LocaleEntry("pl", "Polski",
            "Cześć, jak się masz? Mam się dobrze. To jest poprawnie sformatowane zdanie, ze znakami interpunkcyjnymi i wielkimi literami."),
        LocaleEntry("pt", "Português",
            "Olá, como você está? Estou bem. Esta é uma frase formatada corretamente, com pontuação e capitalização."),
        LocaleEntry("fi", "Suomi",
            "Hei, mitä kuuluu? Minulle kuuluu hyvää. Tämä on oikein muotoiltu lause, jossa on välimerkit ja isot kirjaimet."),
        LocaleEntry("tr", "Türkçe",
            "Merhaba, nasılsınız? Ben iyiyim. Bu, noktalama işaretleri ve büyük harflerle doğru biçimlendirilmiş bir cümledir."),
        LocaleEntry("el", "Ελληνικά",
            "Γεια σας, πώς είστε; Είμαι καλά. Αυτή είναι μια σωστά μορφοποιημένη πρόταση, με σημεία στίξης και κεφαλαία."),
        LocaleEntry("be", "Беларуская",
            "Прывітанне, як справы? У мяне ўсё добра. Гэта правільна адфарматаваны сказ, з пунктуацыяй і вялікімі літарамі."),
        LocaleEntry("ru", "Русский",
            "Привет, как дела? У меня всё хорошо. Это правильно отформатированное предложение, с пунктуацией и заглавными буквами."),
        LocaleEntry("hi", "हिन्दी",
            "नमस्ते, आप कैसे हैं? मैं ठीक हूँ। यह एक सही ढंग से स्वरूपित वाक्य है, विराम चिह्नों के साथ।"),
        LocaleEntry("ja", "日本語",
            "こんにちは、お元気ですか？元気です。これは句読点を含む、正しくフォーマットされた文です。"),
        LocaleEntry("ko", "한국어",
            "안녕하세요, 어떻게 지내세요? 잘 지내고 있습니다. 이것은 구두점과 대문자가 포함된 올바른 형식의 문장입니다."),
        LocaleEntry("zh", "中文",
            "你好，你好吗？我很好。这是一个格式正确的句子，包含标点符号。"),
        LocaleEntry("ar", "العربية",
            "مرحباً، كيف حالك؟ أنا بخير. هذه جملة منسقة بشكل صحيح، مع علامات الترقيم والأحرف الكبيرة.")
    )

    fun resolve(code: String): LocaleEntry? = entries.find { it.code == code }

    fun positionOf(code: String): Int = entries.indexOfFirst { it.code == code }.coerceAtLeast(0)
}
