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
    RAW_UDP("UDP Stream — Low-Latency Real-Time")
}

enum class BufferLatencyPreset(val durationMs: Int, val displayName: String, val description: String) {
    LOW_LATENCY(50, "Low Latency (50ms)", "Minimal delay, best for fast 5GHz Wi-Fi"),
    BALANCED(150, "Balanced (150ms)", "Recommended: Absorbs notification shade & UI gestures"),
    SAFE(300, "Rock-Solid (300ms)", "Maximum dropout immunity against app switching & OS pauses")
}
