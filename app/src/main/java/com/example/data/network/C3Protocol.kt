package com.example.data.network

import com.example.model.AudioStreamFormat
import com.example.model.HeaderMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

object C3Protocol {

    const val DEFAULT_RECEIVER_HOSTNAME = "c3music.local"
    const val DEFAULT_UDP_PORT = 50005

    // Magic: "C3MS" (0x43, 0x33, 0x4D, 0x53)
    val C3_MAGIC = byteArrayOf(0x43, 0x33, 0x4D, 0x53)
    const val C3_PROTOCOL_VERSION: Byte = 0x01

    /**
     * Builds a 16-byte C3 Format Sync Header so ESP32 receiver knows active
     * sample rate, bit depth, and channels before processing raw PCM.
     */
    fun createC3Header(format: AudioStreamFormat): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(C3_MAGIC) // 4 bytes
        buffer.put(C3_PROTOCOL_VERSION) // 1 byte
        buffer.put(format.bitDepth.toByte()) // 1 byte
        buffer.put(format.channelCount.toByte()) // 1 byte
        buffer.put(0.toByte()) // flags / reserved: 1 byte
        buffer.putInt(format.sampleRate) // 4 bytes Little Endian
        buffer.putInt(format.frameSizeBytes) // 4 bytes frame size
        return buffer.array()
    }

    // Magic: "C3MD" (0x43, 0x33, 0x4D, 0x44) for C3 Live Metadata / In-Band Control
    val C3_META_MAGIC = byteArrayOf(0x43, 0x33, 0x4D, 0x44)

    /**
     * Builds a UDP Metadata packet for C3 Receiver.
     * Header: "C3MD" (4 bytes)
     * Payload: UTF-8 JSON string {"title":"...","artist":"...","album":"..."}
     */
    fun createMetadataPacket(title: String, artist: String, album: String = ""): ByteArray {
        val safeTitle = title.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
        val safeArtist = artist.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
        val safeAlbum = album.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
        val json = """{"title":"$safeTitle","artist":"$safeArtist","album":"$safeAlbum"}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(C3_META_MAGIC.size + jsonBytes.size)
        buffer.put(C3_META_MAGIC)
        buffer.put(jsonBytes)
        return buffer.array()
    }

    /**
     * Builds a UDP Volume Control packet for C3 Receiver.
     * Header: "C3MD" (4 bytes)
     * Payload: UTF-8 JSON string {"volume":"50"}
     */
    fun createVolumePacket(volumePercent: Int): ByteArray {
        val safeVol = volumePercent.coerceIn(0, 100)
        val json = """{"volume":"$safeVol"}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(C3_META_MAGIC.size + jsonBytes.size)
        buffer.put(C3_META_MAGIC)
        buffer.put(jsonBytes)
        return buffer.array()
    }

    /**
     * Determines whether a header should be transmitted at connection start
     * based on chosen format and header mode.
     */
    fun getInitialHeader(format: AudioStreamFormat, mode: HeaderMode): ByteArray? {
        return when (mode) {
            HeaderMode.RAW_PCM -> null
            HeaderMode.ALWAYS_HEADER -> createC3Header(format)
            HeaderMode.AUTO -> {
                // Standard 48kHz / 16-bit / stereo sends pure raw PCM directly to ESP32 I2S
                null
            }
            HeaderMode.WAV_HEADER -> createWavHeader(format, dataLength = 0x7FFFFFFF)
        }
    }

    /**
     * Creates standard 44-byte RIFF/WAVE header for HTTP streaming fallback.
     */
    fun createWavHeader(format: AudioStreamFormat, dataLength: Int = 0x7FFFFFFF): ByteArray {
        val totalDataLen = dataLength + 36
        val sampleRate = format.sampleRate
        val channels = format.channelCount
        val bitsPerSample = format.bitDepth
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val header = ByteArray(44)
        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat: 1 for PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        // data sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (dataLength and 0xff).toByte()
        header[41] = ((dataLength shr 8) and 0xff).toByte()
        header[42] = ((dataLength shr 16) and 0xff).toByte()
        header[43] = ((dataLength shr 24) and 0xff).toByte()
        return header
    }
}
