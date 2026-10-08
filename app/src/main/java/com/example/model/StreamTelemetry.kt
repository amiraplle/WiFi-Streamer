package com.example.model

data class StreamTelemetry(
    val streamingState: StreamingState = StreamingState.IDLE,
    val captureStatus: CaptureStatus = CaptureStatus.IDLE,
    val format: AudioStreamFormat = AudioStreamFormat.FORMAT_44K_16BIT_STEREO,
    val audioSource: AudioSourceType = AudioSourceType.DIRECT_AUDIO,
    val targetHost: String = "c3music.local",
    val targetPort: Int = 50005,
    val protocolMode: ProtocolMode = ProtocolMode.RAW_UDP,
    val bytesTransmitted: Long = 0L,
    val durationSeconds: Long = 0L,
    val currentBitrateKbps: Int = 0,
    val reconnectCount: Int = 0,
    val volumePercent: Int = 100,
    val isMuted: Boolean = false,
    val lastError: String? = null,
    val bufferHealthPercent: Int = 100,
    val activeClientsCount: Int = 0,
    val connectedClientAddress: String? = null
) {
    val formattedDuration: String
        get() {
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedDataTransmitted: String
        get() {
            val mb = bytesTransmitted / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}
