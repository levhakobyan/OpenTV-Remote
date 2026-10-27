package com.luma.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class BoundaryTest {
    @Test fun acceptsOnlyLiteralPrivateV4() {
        listOf("192.168.1.2","10.0.2.2","172.16.0.1","172.31.255.254","169.254.2.3").forEach { assertTrue(it,LocalAddress.valid(it)) }
        listOf("127.0.0.1","0.0.0.0","8.8.8.8","172.32.0.1","192.168.1.999","localhost","http://192.168.1.2","192.168.1.2:80","192.168.1.2@evil.com","::1","224.0.0.1").forEach { assertFalse(it,LocalAddress.valid(it)) }
    }
}
