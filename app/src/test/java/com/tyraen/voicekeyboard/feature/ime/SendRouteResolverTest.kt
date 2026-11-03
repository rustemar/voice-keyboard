package com.tyraen.voicekeyboard.feature.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class SendRouteResolverTest {
    private val multiline = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val imageTypes = arrayOf("image/gif", "image/*", "image/jpg", "image/png", "image/webp")

    private fun route(
        pkg: String? = "org.telegram.messenger", type: Int = multiline,
        action: Int = EditorInfo.IME_ACTION_UNSPECIFIED, actionId: Int = 0,
        label: String? = null, mime: Array<String> = imageTypes
    ) = SendRouteResolver.resolve(pkg, type, action, actionId, label, mime)

    @Test fun `declared Send keeps its editor action even when Enter must make a new line`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val declared = route(pkg = "example.chat", action = options, mime = emptyArray())
        assertEquals(SendRouteResolver.Kind.DECLARED_SEND, declared.kind)
        assertEquals(EditorInfo.IME_ACTION_SEND, declared.action!!.id)
        assertTrue(declared.automatic)
        assertEquals(EditorField.Enter.NewLine, EditorField.enter(options, 0, null))
        assertEquals(42, route(pkg = "example.chat", action = options, actionId = 42, label = "Custom send").action!!.id)
    }

    @Test fun `official Telegram composer supports Send after TextView normalizes to Done or Next`() {
        for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web")) {
            for (action in listOf(EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE,
                EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT)) {
                val resolved = route(pkg = pkg, action = action or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                assertEquals(SendRouteResolver.Kind.TELEGRAM_COMPOSER_SEND, resolved.kind)
                assertEquals(EditorInfo.IME_ACTION_SEND, resolved.action!!.id)
                assertTrue(resolved.automatic)
            }
        }
    }

    @Test fun `Telegram unrelated fields and custom actions remain ineligible for automatic sending`() {
        val unrelated = listOf(
            route(mime = emptyArray()),
            route(mime = arrayOf("text/plain")),
            route(type = InputType.TYPE_CLASS_TEXT),
            route(action = EditorInfo.IME_ACTION_SEARCH),
            route(action = EditorInfo.IME_ACTION_GO),
            route(action = EditorInfo.IME_ACTION_PREVIOUS),
            route(label = "Custom", actionId = 19),
            route(actionId = 19),
            route(pkg = "org.telegram.messenger.unverified"),
            route(type = multiline or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        for (resolved in unrelated) {
            assertEquals(SendRouteResolver.Kind.LEGACY_CTRL_ENTER, resolved.kind)
            assertFalse(resolved.automatic)
            assertNull(resolved.action)
        }
    }

    @Test fun `owner reported ChatGPT multiline field uses exactly one standard Send trial`() {
        val actual = route(pkg = "com.openai.chatgpt", type = 180225, action = 1107296256, mime = emptyArray())
        assertEquals(SendRouteResolver.Kind.CHATGPT_COMPAT_TRIAL, actual.kind)
        assertEquals(EditorInfo.IME_ACTION_SEND, actual.action!!.id)
        assertTrue(actual.automatic)
    }

    @Test fun `ChatGPT compatibility trial excludes other fields packages and custom actions`() {
        val unrelated = listOf(
            route(pkg = "com.openai.chatgpt", type = InputType.TYPE_CLASS_TEXT),
            route(pkg = "com.openai.chatgpt", action = EditorInfo.IME_ACTION_SEARCH),
            route(pkg = "com.openai.chatgpt", action = EditorInfo.IME_ACTION_DONE),
            route(pkg = "com.openai.chatgpt", actionId = 42, label = "Custom"),
            route(pkg = "com.openai.chatgpt", actionId = 42),
            route(pkg = "com.openai.chatgpt", type = multiline or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            route(pkg = "com.openai.other"),
            route(pkg = "example.chat")
        )
        for (resolved in unrelated) {
            assertFalse(resolved.automatic)
            assertNull(resolved.action)
        }
    }

    @Test fun `declared Send in a password field never authorizes automatic sending`() {
        val password = route(pkg = "example.chat", type = multiline or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            action = EditorInfo.IME_ACTION_SEND)
        assertEquals(EditorInfo.IME_ACTION_SEND, password.action!!.id)
        assertFalse(password.automatic)
    }

    @Test fun `diagnostic MIME names contain only whitelisted literal image types`() {
        assertEquals("image/*,image/png", SendRouteResolver.diagnosticMimeTypes(
            arrayOf("text/private-editor-data", "image/png", "image/*", "image/png", "image/custom-private")
        ))
    }
}
