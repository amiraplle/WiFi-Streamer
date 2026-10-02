package com.example.model

enum class StreamingState {
    IDLE,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    DISCONNECTED,
    ERROR
}

enum class CaptureStatus {
    IDLE,
    INITIALIZING,
    CAPTURING,
    SILENCE,
    PAUSED,
    ERROR
}

enum class AudioSourceType(val displayName: String) {
    INTERNAL_AUDIO("Internal Audio"),
    MICROPHONE("Microphone")
}

enum class HeaderMode(val displayName: String, val description: String) {
    AUTO("Auto Negotiate", "Raw PCM for 44.1/16; C3 sync header for 48kHz / 24-bit"),
    ALWAYS_HEADER("Always C3 Header", "Includes 16-byte C3 header for format auto-detection"),
    RAW_PCM("Legacy Raw PCM", "Pure PCM byte stream without header (Current C3 default)"),
    WAV_HEADER("WAV Header", "Prepends standard 44-byte RIFF/WAV header")
}

enum class ProtocolMode(val displayName: String) {
    RAW_TCP_CLIENT("Raw TCP Streamer (ESP32-C3)"),
    HTTP_SERVER("Local HTTP Server (Browser / C3 Fallback)")
}

enum class BufferLatencyPreset(val durationMs: Int, val displayName: String, val description: String) {
    LOW_LATENCY(10, "Low Latency (10ms)", "Minimal delay, best for fast 5GHz Wi-Fi"),
    BALANCED(25, "Balanced (25ms)", "Optimal trade-off between latency and jitter immunity"),
    SAFE(50, "Safe Buffer (50ms)", "Maximum dropout resistance on congested networks")
}
