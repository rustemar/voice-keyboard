package com.tyraen.voicekeyboard.feature.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFieldTest {

    private val text = InputType.TYPE_CLASS_TEXT
    private val number = InputType.TYPE_CLASS_NUMBER
    private val termiusPackage = "com.server.auditor.ssh.client"
    private val termiusTerminal = "$termiusPackage:id/terminalView"

    @Test fun `text, web and number password fields are passwords`() {
        assertTrue(EditorField.isPassword(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(EditorField.isPassword(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(EditorField.isPassword(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun `flags on a password field do not hide it`() {
        assertTrue(EditorField.isPassword(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ))
    }

    @Test fun `a shown password is still a password`() {
        // "Show password" toggles switch the field to this variation; AOSP hides its mic there too.
        assertTrue(EditorField.isPassword(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
    }

    @Test fun `termux's class-less visible-password input is not a password`() {
        // Termux sends TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | NO_SUGGESTIONS with no class bits.
        assertFalse(EditorField.isPassword(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ))
    }

    @Test fun `ordinary fields are not passwords`() {
        assertFalse(EditorField.isPassword(InputType.TYPE_NULL))
        assertFalse(EditorField.isPassword(text))
        assertFalse(EditorField.isPassword(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(EditorField.isPassword(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(EditorField.isPassword(number))
        assertFalse(EditorField.isPassword(InputType.TYPE_CLASS_PHONE))
        // The password variation value under another class means something else.
        assertFalse(EditorField.isPassword(number or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    @Test fun `Termius terminal permits voice but keeps its password classification for context`() {
        // Verified Termius 7.11.2 default: inputType 0x91, imeOptions 0x52080006.
        assertTrue(EditorField.isPassword(0x91))
        assertFalse(EditorField.blocksVoice(0x91, termiusPackage, termiusTerminal))
    }

    @Test fun `Termius terminal input flags do not remove its narrow voice exception`() {
        val visiblePassword = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        for (flags in listOf(
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )) {
            val inputType = visiblePassword or flags
            assertTrue(EditorField.isPassword(inputType))
            assertFalse(EditorField.blocksVoice(inputType, termiusPackage, termiusTerminal))
        }
    }

    @Test fun `a visible password needs both the exact Termius app and full terminal resource name`() {
        val identities = listOf(
            "example.password.app" to termiusTerminal,
            "$termiusPackage.beta" to termiusTerminal,
            null to termiusTerminal,
            termiusPackage to null,
            termiusPackage to "$termiusPackage:id/password",
            termiusPackage to "terminalView",
            termiusPackage to "example.password.app:id/terminalView"
        )
        for ((packageName, resourceName) in identities) {
            assertTrue(
                "Visible password unexpectedly allowed for $packageName / $resourceName",
                EditorField.blocksVoice(0x91, packageName, resourceName)
            )
        }
    }

    @Test fun `real text web and numeric passwords remain blocked even with the terminal identity`() {
        for (inputType in listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            number or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )) {
            assertTrue(EditorField.blocksVoice(inputType, termiusPackage, termiusTerminal))
            assertTrue(EditorField.blocksVoice(
                inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                termiusPackage,
                termiusTerminal
            ))
        }
    }

    @Test fun `ordinary fields and class-less Termux input remain voice eligible without an exception`() {
        for (inputType in listOf(
            InputType.TYPE_NULL,
            text,
            0x80001, // Termius's optional normal input type: TEXT | NO_SUGGESTIONS.
            text or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            number,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )) {
            assertFalse(EditorField.blocksVoice(inputType, null, null))
            assertFalse(EditorField.blocksVoice(inputType, termiusPackage, termiusTerminal))
        }
    }

    @Test fun `a field that asks for no enter action gets a new line`() {
        assertEquals(
            EditorField.Enter.NewLine,
            EditorField.enter(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, 0, null)
        )
    }

    @Test fun `no enter action wins over a custom label`() {
        assertEquals(
            EditorField.Enter.NewLine,
            EditorField.enter(EditorInfo.IME_FLAG_NO_ENTER_ACTION, 42, "Post")
        )
    }

    @Test fun `the action from imeOptions is performed`() {
        for (id in listOf(
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_DONE
        )) {
            assertEquals(EditorField.Enter.Action(id, null), EditorField.enter(id, 0, null))
        }
    }

    @Test fun `action none types a new line`() {
        assertEquals(EditorField.Enter.NewLine, EditorField.enter(EditorInfo.IME_ACTION_NONE, 0, null))
    }

    @Test fun `an unspecified action is still performed, like AOSP`() {
        assertEquals(
            EditorField.Enter.Action(EditorInfo.IME_ACTION_UNSPECIFIED, null),
            EditorField.enter(EditorInfo.IME_ACTION_UNSPECIFIED, 0, null)
        )
    }

    @Test fun `a custom label performs the field's own action id`() {
        assertEquals(
            EditorField.Enter.Action(42, "Post"),
            EditorField.enter(EditorInfo.IME_ACTION_DONE, 42, "Post")
        )
    }

    @Test fun `other imeOptions flags do not change the action`() {
        assertEquals(
            EditorField.Enter.Action(EditorInfo.IME_ACTION_SEARCH, null),
            EditorField.enter(
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN,
                0, null
            )
        )
    }

    @Test fun `only text fields with the multi-line flag are multi-line`() {
        assertTrue(EditorField.isMultiLine(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(EditorField.isMultiLine(
            text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ))
        assertFalse(EditorField.isMultiLine(text))
        assertFalse(EditorField.isMultiLine(InputType.TYPE_NULL))
        // The same bit means something else outside the text class.
        assertFalse(EditorField.isMultiLine(number or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun `the send key uses the field's own send action only when it has one`() {
        assertTrue(EditorField.sendsByAction(EditorField.enter(EditorInfo.IME_ACTION_SEND, 0, null)))
        assertFalse(EditorField.sendsByAction(EditorField.enter(EditorInfo.IME_ACTION_DONE, 0, null)))
        assertFalse(EditorField.sendsByAction(EditorField.Enter.NewLine))
        // A chat box that asks for no enter action keeps Ctrl+Enter.
        assertFalse(EditorField.sendsByAction(
            EditorField.enter(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, 0, null)
        ))
        // A custom label names some other action, whatever its id.
        assertFalse(EditorField.sendsByAction(EditorField.enter(EditorInfo.IME_ACTION_SEND, 4, "Post")))
    }
}
