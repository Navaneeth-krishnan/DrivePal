package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalAddressTest {

    @Test
    fun `private, loopback and link-local addresses are on the local network`() {
        val local = listOf(
            "localhost", "10.0.2.2", "192.168.1.20", "172.16.5.4", "172.31.255.255", "127.0.0.1",
            "169.254.10.1", "laptop.local", "100.64.0.7", "[fd12:3456::1]", "fd12:3456::1", "[::1]", "fe80::1",
        )

        assertEquals(local.map { true }, local.map(LocalAddress::isLocal), local.toString())
    }

    @Test
    fun `public hosts and addresses are not`() {
        val public = listOf("generativelanguage.googleapis.com", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2a00:1450::1", "")

        assertEquals(public.map { false }, public.map(LocalAddress::isLocal), public.toString())
    }

    @Test
    fun `something that is not an address at all is simply not local`() {
        assertEquals(false, LocalAddress.isLocal("http://"))
        assertEquals(false, LocalAddress.isLocal("[not an address]"))
    }
}
