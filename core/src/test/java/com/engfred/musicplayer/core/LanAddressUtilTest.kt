package com.engfred.musicplayer.core

import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.util.LanAddressUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressUtilTest {

    @Test
    fun getLocalIpAddress_doesNotThrowException() {
        // Should execute cleanly without throwing on any JVM environment
        val ip = LanAddressUtil.getLocalIpAddress()
        // If an IP is found, verify it's not a loopback address
        if (ip != null) {
            assertTrue(ip.isNotEmpty())
            assertTrue(ip != "127.0.0.1")
        }
    }

    @Test
    fun castState_enumValuesExist() {
        val states = CastState.values()
        assertTrue(states.contains(CastState.DISCONNECTED))
        assertTrue(states.contains(CastState.CONNECTING))
        assertTrue(states.contains(CastState.CONNECTED))
    }
}
