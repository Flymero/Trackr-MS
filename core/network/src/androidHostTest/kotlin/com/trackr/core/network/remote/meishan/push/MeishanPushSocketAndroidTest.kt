package com.trackr.core.network.remote.meishan.push

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class MeishanPushSocketAndroidTest {
    @Test
    fun readFrame_handlesUtf8ByteLimitAndBackToBackFrames() {
        val input = ByteArrayInputStream(
            ("{" + "\"message\":\"眉山\"}" + MeishanPushProtocol.DELIMITER +
                "{}" + MeishanPushProtocol.DELIMITER).encodeToByteArray()
        )

        assertEquals("{\"message\":\"眉山\"}", readFrame(input))
        assertEquals("{}", readFrame(input))
        assertNull(readFrame(input))
    }

    @Test
    fun readFrame_discardsEofHalfFrame() {
        val input = ByteArrayInputStream("{\"msgType\":0}".encodeToByteArray())

        assertNull(readFrame(input))
    }

    @Test
    fun readFrame_rejectsWirePayloadAboveLimit() {
        val input = ByteArrayInputStream(
            ("x".repeat(MeishanPushProtocol.MAX_FRAME_BYTES + 1) + MeishanPushProtocol.DELIMITER)
                .encodeToByteArray()
        )

        assertFailsWith<IOException> { readFrame(input) }
    }
}
