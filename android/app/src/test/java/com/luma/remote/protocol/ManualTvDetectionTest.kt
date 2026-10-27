package com.luma.remote.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualTvDetectionTest {
    private val host="192.168.1.20"
    @Test fun ordinaryConnectionKeepsAndroidWhenNativePortsAreOpen()=runBlocking {
        val detected=ManualTvDetection.detect(host,open={ true },samsung={ null })
        assertEquals(Platform.ANDROID_TV,detected?.platform)
        assertEquals("manual:ANDROID_TV:$host",detected?.id)
    }
    @Test fun confirmedSamsungMetadataRetainsItsExistingPriorityAndIdentity()=runBlocking {
        val metadata=SamsungInfo("original-id","Living room",Capabilities())
        val detected=ManualTvDetection.detect(host,open={ true },samsung={ metadata })
        assertEquals(metadata.device(host),detected)
    }
    @Test fun ordinaryFallbackKeepsNativePriority()=runBlocking {
        for((ports,expected) in listOf(
            setOf(3000,8002,6469) to Platform.LG,
            setOf(8002,6469) to Platform.SAMSUNG,
            setOf(6469) to null,
            emptySet<Int>() to null
        )) {
            val detected=ManualTvDetection.detect(host,open={ it in ports },samsung={ null })
            assertEquals(expected,detected?.platform)
        }
    }
    @Test fun retiredPortIsNeverProbed()=runBlocking {
        val ports=java.util.Collections.synchronizedList(mutableListOf<Int>())
        assertNull(ManualTvDetection.detect(host,open={ ports+=it;false },samsung={ null }))
        assertEquals(setOf(6467,3000,8002),ports.toSet())
    }
    @Test fun publicAddressesAreRejectedBeforeAnyProbe()=runBlocking {
        try {
            ManualTvDetection.detect("8.8.8.8",open={ error("Port probe must not run") },samsung={ error("Metadata probe must not run") })
            fail("Public IP accepted")
        } catch(_:IllegalArgumentException) { }
    }
}
