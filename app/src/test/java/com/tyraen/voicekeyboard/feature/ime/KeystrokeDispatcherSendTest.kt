package com.tyraen.voicekeyboard.feature.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystrokeDispatcherSendTest {

    @Test fun `an accepted Send action is dispatched once to the captured connection`() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val connection = connection(calls, actionAccepted = true)
        var connectionReads = 0
        val dispatcher = KeystrokeDispatcher {
            connectionReads++
            connection
        }

        assertTrue(dispatcher.pressSend(EditorField.Enter.Action(EditorInfo.IME_ACTION_SEND, null)))

        assertEquals(1, connectionReads)
        assertEquals(listOf("performEditorAction" to listOf(EditorInfo.IME_ACTION_SEND)), calls)
    }

    @Test fun `a rejected custom Send action is not retried or followed by key events`() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val dispatcher = KeystrokeDispatcher { connection(calls, actionAccepted = false) }

        assertFalse(dispatcher.pressSend(EditorField.Enter.Action(42, "Post")))

        assertEquals(listOf("performEditorAction" to listOf(42)), calls)
    }

    @Test fun `Send cannot dispatch without an input connection`() {
        val dispatcher = KeystrokeDispatcher { null }

        assertFalse(dispatcher.pressSend(EditorField.Enter.Action(EditorInfo.IME_ACTION_SEND, null)))
        assertFalse(dispatcher.pressSend(null))
    }

    private fun connection(
        calls: MutableList<Pair<String, List<Any?>>>,
        actionAccepted: Boolean
    ): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java)
    ) { _, method, arguments ->
        calls += method.name to arguments.orEmpty().toList()
        check(method.name == "performEditorAction") { "Unexpected editor call: ${method.name}" }
        actionAccepted
    } as InputConnection
}
