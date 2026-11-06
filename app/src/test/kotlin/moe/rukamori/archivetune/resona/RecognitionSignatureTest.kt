/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import moe.rukamori.archivetune.shazamkit.ShazamSignatureGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Android and iPad must send the same signature. Both are tested against one
 * reference from a third implementation (same synthetic signal, no real audio).
 */
class RecognitionSignatureTest {
    private fun testPcm(): ShortArray {
        var state = 12345L
        return ShortArray(134_400) { index ->
            state = (state * 1_103_515_245L + 12345L) and 0x7FFFFFFFL
            val noise = (((state shr 16) and 0x7FFFL).toInt() - 16384) shr 2
            val first = if ((index / 18) % 2 == 0) 6000 else -6000
            val second = if ((index / 5) % 2 == 0) 3000 else -3000
            (noise + first + second).coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun signatureMatchesTheReference() {
        val signature = ShazamSignatureGenerator().apply { feedPcm16Mono(testPcm()) }.nextSignatureOrNull()
        assertNotNull(signature)
        assertEquals(3104L, signature!!.sampleDurationMs)
        val bytes = Base64.getDecoder().decode(signature.uri.substringAfter("base64,"))
        assertEquals(1912, bytes.size)
        val crc = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        assertEquals(0x6A5320A3, crc)
        val body = bytes.copyOfRange(56, 72).joinToString("") { "%02x".format(it) }
        assertEquals("4000036037000000234383390e559f5a", body)
    }
}
