package com.example

import com.example.data.network.C3Protocol
import com.example.domain.audio.PcmAudioProcessor
import com.example.model.AudioStreamFormat
import com.example.model.HeaderMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testAudioStreamFormatCalculations() {
        val format44 = AudioStreamFormat.FORMAT_44K_16BIT_STEREO
        assertEquals(2, format44.bytesPerSample)
        assertEquals(4, format44.frameSizeBytes)
        assertEquals(1411, format44.bitrateKbps)

        val format48_16 = AudioStreamFormat.FORMAT_48K_16BIT_STEREO
        assertEquals(2, format48_16.bytesPerSample)
        assertEquals(4, format48_16.frameSizeBytes)
        assertEquals(1536, format48_16.bitrateKbps)

        val format48_24 = AudioStreamFormat.FORMAT_48K_24BIT_STEREO
        assertEquals(3, format48_24.bytesPerSample)
        assertEquals(6, format48_24.frameSizeBytes)
        assertEquals(2304, format48_24.bitrateKbps)
    }

    @Test
    fun testC3ProtocolHeaderCreation() {
        val format = AudioStreamFormat.FORMAT_48K_24BIT_STEREO
        val header = C3Protocol.createC3Header(format)

        assertEquals(16, header.size)
        // Verify Magic "C3MS"
        assertEquals('C'.code.toByte(), header[0])
        assertEquals('3'.code.toByte(), header[1])
        assertEquals('M'.code.toByte(), header[2])
        assertEquals('S'.code.toByte(), header[3])
        // Verify Version 1
        assertEquals(1.toByte(), header[4])
        // Verify 24-bit
        assertEquals(24.toByte(), header[5])
        // Verify 2 channels
        assertEquals(2.toByte(), header[6])
    }

    @Test
    fun testC3ProtocolHeaderModes() {
        // In Auto mode: 44.1/16 must return null (legacy pure raw PCM)
        val headerAuto44 = C3Protocol.getInitialHeader(AudioStreamFormat.FORMAT_44K_16BIT_STEREO, HeaderMode.AUTO)
        assertNull(headerAuto44)

        // In Auto mode: 48/16 must return C3 header
        val headerAuto48 = C3Protocol.getInitialHeader(AudioStreamFormat.FORMAT_48K_16BIT_STEREO, HeaderMode.AUTO)
        assertNotNull(headerAuto48)
        assertEquals(16, headerAuto48!!.size)

        // In Raw PCM mode: always null
        val headerRaw = C3Protocol.getInitialHeader(AudioStreamFormat.FORMAT_48K_24BIT_STEREO, HeaderMode.RAW_PCM)
        assertNull(headerRaw)

        // In WAV Header mode: 44 bytes
        val headerWav = C3Protocol.getInitialHeader(AudioStreamFormat.FORMAT_44K_16BIT_STEREO, HeaderMode.WAV_HEADER)
        assertNotNull(headerWav)
        assertEquals(44, headerWav!!.size)
    }

    @Test
    fun testPcmProcessorVolumeAndMute() {
        // Test 16-bit PCM volume scaling
        // Sample with value 1000: low = 0xE8, high = 0x03
        val buffer = byteArrayOf(0xE8.toByte(), 0x03.toByte())
        PcmAudioProcessor.processInPlace(buffer, buffer.size, bitDepth = 16, volumePercent = 50, isMuted = false)

        val scaledLow = buffer[0].toInt() and 0xFF
        val scaledHigh = buffer[1].toInt()
        val scaledSample = ((scaledHigh shl 8) or scaledLow).toShort().toInt()
        assertEquals(500, scaledSample)

        // Test Mute
        PcmAudioProcessor.processInPlace(buffer, buffer.size, bitDepth = 16, volumePercent = 100, isMuted = true)
        assertEquals(0.toByte(), buffer[0])
        assertEquals(0.toByte(), buffer[1])
    }

    @Test
    fun testPcmSilenceDetection() {
        val silentBuffer = ByteArray(128) { 0 }
        assertTrue(PcmAudioProcessor.isBufferSilent(silentBuffer, silentBuffer.size, bitDepth = 16))

        val noisyBuffer = ByteArray(128) { 0 }
        noisyBuffer[10] = 0x50.toByte()
        noisyBuffer[11] = 0x20.toByte() // amplitude ~8272
        assertFalse(PcmAudioProcessor.isBufferSilent(noisyBuffer, noisyBuffer.size, bitDepth = 16))
    }
}
