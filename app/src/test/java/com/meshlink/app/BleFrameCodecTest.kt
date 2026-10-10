package com.meshlink.app

import com.meshlink.app.core.bluetooth.BleFrameCodec
import com.meshlink.app.core.bluetooth.FrameReassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun framesStayWithinLegacyGattValueSize() {
        val frames = BleFrameCodec.split(ByteArray(500) { (it and 0xFF).toByte() })
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.size <= 20 })
    }

    @Test
    fun reassemblerRestoresPayload() {
        val original = ByteArray(1500) { (it * 31).toByte() }
        val reassembler = FrameReassembler()
        var result: ByteArray? = null
        for (frame in BleFrameCodec.split(original)) {
            result = reassembler.accept(frame) ?: result
        }
        assertArrayEquals(original, result)
    }
}
