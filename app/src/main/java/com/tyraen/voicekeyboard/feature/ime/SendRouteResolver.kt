package com.tyraen.voicekeyboard.feature.ime

import android.view.inputmethod.EditorInfo

/** A single dispatch route; automatic sending never falls through to another method. */
internal object SendRouteResolver {
    enum class Kind { DECLARED_SEND, TELEGRAM_COMPOSER_SEND, CHATGPT_COMPAT_TRIAL, LEGACY_CTRL_ENTER }

    data class Route(val kind: Kind, val action: EditorField.Enter.Action?, val automatic: Boolean)

    private val telegramPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web")
    private val telegramActions = setOf(
        EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE,
        EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE
    )
    private val imageMimeTypes = setOf("image/gif", "image/*", "image/jpg", "image/png", "image/webp")

    /** Only known, literal MIME names may appear in diagnostic logs. */
    fun diagnosticMimeTypes(contentMimeTypes: Array<String>): String =
        contentMimeTypes.filter { it in imageMimeTypes }.distinct().sorted().joinToString(",")

    fun resolve(
        packageName: String?, inputType: Int, imeOptions: Int,
        actionId: Int, actionLabel: CharSequence?, contentMimeTypes: Array<String>
    ): Route {
        val password = EditorField.isPassword(inputType)
        EditorField.sendAction(imeOptions, actionId, actionLabel)?.let {
            return Route(Kind.DECLARED_SEND, it, automatic = !password)
        }
        val rawAction = imeOptions and EditorInfo.IME_MASK_ACTION
        val composer = !password && EditorField.isMultiLine(inputType) && actionId == 0 && actionLabel == null
        if (composer && packageName in telegramPackages && rawAction in telegramActions &&
            contentMimeTypes.any { it in imageMimeTypes }
        ) {
            // Telegram's composer advertises image MIME types but TextView can normalize its
            // unspecified action to Done/Next. Its editor listener explicitly handles SEND.
            // Verified against DrKLO/Telegram commit 3de37c8cfb039452be7398909461da6288babf15,
            // ChatActivityEnterView.onCreateInputConnection and onEditorAction.
            return Route(Kind.TELEGRAM_COMPOSER_SEND, standardSend(), automatic = true)
        }
        if (composer && packageName == "com.openai.chatgpt" && rawAction == EditorInfo.IME_ACTION_UNSPECIFIED) {
            // The maintainer's device reported this multiline signature. This is a compatibility
            // trial of the standard SEND request, not a claim that the host sends a message.
            return Route(Kind.CHATGPT_COMPAT_TRIAL, standardSend(), automatic = true)
        }
        return Route(Kind.LEGACY_CTRL_ENTER, null, automatic = false)
    }

    private fun standardSend() = EditorField.Enter.Action(EditorInfo.IME_ACTION_SEND, null)
}
