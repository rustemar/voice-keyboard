package com.tyraen.voicekeyboard.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class EndpointConnectivityPolicyTest {
    private class Fixture(var internet: Boolean = false, var link: Boolean = true) {
        val answers = HashMap<String, List<InetAddress>>()
        val resolved = ArrayList<String>()
        val policy = EndpointConnectivityPolicy({ internet }, { link }) { host ->
            resolved += host
            answers[host].orEmpty()
        }
    }

    @Test fun `validated internet permits public APIs without DNS preflight`() = runTest {
        val f = Fixture(internet = true, link = false)
        assertTrue(f.policy.canAccessEndpoint("https://api.groq.com/openai/v1"))
        assertTrue(f.policy.canAccessEndpoint("https://speech.example.org/v1"))
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun `loopback works without any Android network link`() = runTest {
        val f = Fixture(link = false)
        for (endpoint in listOf(
            "http://127.0.0.1:8000/v1", "http://127.23.45.67:8000/v1",
            "http://[::1]:8000/v1", "http://[::ffff:127.0.0.1]:8000/v1",
            "http://localhost:8000/v1", "http://LOCALHOST.:8000/v1"
        )) assertTrue(endpoint, f.policy.canAccessEndpoint(endpoint))
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun `local private and overlay literals work on a link without validated internet`() = runTest {
        val f = Fixture()
        for (host in listOf("10.0.2.2", "172.16.0.1", "172.31.255.254", "192.168.1.5", "169.254.2.3", "100.64.0.1", "100.127.255.254")) {
            assertTrue(host, f.policy.canAccessEndpoint("http://$host:8000/v1"))
        }
        for (host in listOf("fc00::1", "fdab:1234::2", "fe80::1", "::ffff:192.168.1.5")) {
            assertTrue(host, f.policy.canAccessEndpoint("http://[$host]:8000/v1"))
        }
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun `local literals other than loopback still need a network link`() = runTest {
        val f = Fixture(link = false)
        assertFalse(f.policy.canAccessEndpoint("http://192.168.1.5:8000"))
        assertFalse(f.policy.canAccessEndpoint("http://[fdab:1234::2]:8000"))
        assertFalse(f.policy.canAccessEndpoint("http://nas.local:8000"))
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun `public and non-destination literals stay parked without internet`() = runTest {
        val f = Fixture()
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.0.2.1", "0.0.0.0", "224.0.0.1", "255.255.255.255")) {
            assertFalse(host, f.policy.canAccessEndpoint("http://$host:8000"))
        }
        for (host in listOf("2001:4860:4860::8888", "::", "ff02::1")) {
            assertFalse(host, f.policy.canAccessEndpoint("http://[$host]:8000"))
        }
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun `local and ordinary domain names use bounded resolver results instead of suffix guesses`() = runTest {
        val f = Fixture()
        val local = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
        for (host in listOf("nas", "nas.local", "speech.home.arpa", "speech.example.org")) {
            f.answers[host] = local
            assertTrue(host, f.policy.canAccessEndpoint("http://$host:8000/v1"))
        }
        assertEquals(listOf("nas", "nas.local", "speech.home.arpa", "speech.example.org"), f.resolved)
        assertFalse(f.policy.canAccessEndpoint("http://missing.local:8000/v1"))
    }

    @Test fun `unknown cloud domains resolving publicly and built-in providers remain parked`() = runTest {
        val f = Fixture()
        f.answers["speech.example.org"] = listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        assertFalse(f.policy.canAccessEndpoint("https://speech.example.org/v1"))
        for (host in listOf("api.groq.com", "api.openai.com", "api.anthropic.com", "openrouter.ai", "eu.openrouter.ai")) {
            assertFalse(host, f.policy.canAccessEndpoint("https://$host/v1"))
        }
        assertEquals(listOf("speech.example.org"), f.resolved)
    }

    @Test fun `scheme-less local addresses use the same URL normalization as provider settings`() = runTest {
        val f = Fixture()
        assertTrue(f.policy.canAccessEndpoint(" 192.168.1.5:8000/v1 "))
        f.answers["nas"] = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)))
        assertTrue(f.policy.canAccessEndpoint("http://user:password@nas:8000/v1?source=keyboard"))
        assertEquals(listOf("nas"), f.resolved)
    }

    @Test fun `cancelling name resolution cancels provider gating`() = runTest {
        val policy = EndpointConnectivityPolicy({ false }, { true }) { throw CancellationException("cancel") }
        try {
            policy.canAccessEndpoint("https://speech.example.org/v1")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
