# Unified ESP32 Audio Receiver: WROOM Bluetooth & Dual-Mode Roadmap

Architectural blueprint for porting the proven C3 firmware base to a **headless ESP32 WROOM Classic Bluetooth A2DP receiver**, followed by a roadmap for a **unified dual-transmission system (Bluetooth / Wi-Fi UDP)** with zero OLED display overhead.

### User Review & Critical Decisions

> [!IMPORTANT]
> The plan below formalizes your exact architectural requirements:
> - **100% Headless (Zero OLED)**: Completely remove U8g2 library, I2C polling loops, and font tables. This frees up ~30 KB of flash, ~12 KB of SRAM, and eliminates display interrupt jitter on the I2S audio clock.
> - **Phase 1: Pure Bluetooth A2DP Receiver on ESP32 WROOM**: Port the C3 firmware core to the dual-core ESP32 WROOM, stripping UDP transmission while retaining the Web Configuration Portal, NVS Preferences, OTA Updates, and direct I2S DAC output (PCM5102A / UDA1334A).
> - **Phase 2: Unified Transmission Firmware (BT / Wi-Fi UDP)**: Implement a clean radio arbitration state machine allowing the user to toggle between high-fidelity Wi-Fi UDP streaming (from our Android host app) and standard Classic Bluetooth A2DP (from any phone/laptop).

---

### 1. Overview & Core Concept

* **What It Does**: Provides high-fidelity, direct I2S audio output to an external DAC (PCM5102A / UDA1334A) from an ESP32 WROOM receiver.
  * In **Bluetooth Mode**: Functions as a standard high-quality Bluetooth A2DP Audio Sink (SBC/AAC) compatible with any mobile device or PC.
  * In **Wi-Fi UDP Mode**: Receives frame-paced 48 kHz 16/24/32-bit PCM direct from our Android host app with ultra-low latency (15–300 ms).
  * In **Both Modes**: Web-based configuration portal and OTA firmware update server remain accessible over local Wi-Fi.
* **Why ESP32 WROOM**: The ESP32-C3 only features BLE (no classic audio profiles). The ESP32 WROOM features dual Tensilica Xtensa LX6 cores with dedicated hardware for Classic Bluetooth (BR/EDR), making high-bitrate A2DP Sink possible without audio stutter.
* **Why Headless (No OLED)**: Eliminates display rendering bottlenecks, simplifies physical wiring (only 5 wires needed: 5V, GND, BCLK, LRCK, DOUT), and avoids I2C bus stalls during audio streaming.

---

### 2. Phase 1: Pure WROOM Bluetooth A2DP Receiver

In Phase 1, we create a clean, dedicated WROOM firmware:

#### Code Stripping & Optimization:
1. **Remove OLED Stack**:
   - Delete `#include <U8g2lib.h>` and `#include <Wire.h>`.
   - Remove OLED pin definitions (`OLED_SDA`, `OLED_SCL`), display refresh timers, and rendering routines.
2. **Remove UDP Streaming Engine**:
   - Remove `WiFiUDP udpClient`, packet parsing loops, jitter buffer pacing, and UDP socket bindings.
3. **Integrate Bluetooth A2DP Sink**:
   - Use the official ESP-IDF / Arduino `BluetoothA2DPSink` pipeline.
   - Core 1 handles A2DP decoding (SBC/AAC) and pushes decoded PCM directly into the I2S DMA buffer.
   - Core 0 runs the lightweight Wi-Fi AP/STA web server for Web OTA and setting adjustments.
4. **I2S Pin Mapping for ESP32 WROOM**:
   - Default classic WROOM I2S pins:
     - `I2S_BCLK_PIN = 26` (Bit Clock)
     - `I2S_LRCLK_PIN = 25` (Word Select / Left-Right Clock)
     - `I2S_DOUT_PIN = 22` (Data Out to PCM5102A / UDA1334A)
     - (Pins are user-configurable via Web UI / NVS).

---

### 3. Phase 2: Unified Dual-Transmission Architecture

Phase 2 combines both modes into a single, switchable firmware:

```
┌─────────────────────────────────────────────────────────────┐
│                 ESP32 WROOM Dual-Core SoC                   │
├──────────────────────────────┬──────────────────────────────┤
│      Core 0 (System & Web)   │   Core 1 (Real-Time Audio)   │
│  ┌────────────────────────┐  │  ┌────────────────────────┐  │
│  │ WebServer (Port 80)    │  │  │ Mode A: Bluetooth A2DP │  │
│  │ (Settings & OTA)       │  │  │ (SBC / AAC Audio Sink) │  │
│  └───────────┬────────────┘  │  └───────────┬────────────┘  │
│              │               │              │               │
│              ▼               │              ▼               │
│  ┌────────────────────────┐  │  ┌────────────────────────┐  │
│  │ Mode Controller (NVS)  │◄─┼──┤ Mode B: Wi-Fi UDP Rx   │  │
│  │ [BT Sink vs. UDP Stream│  │  │ (Direct 48k PCM Stream)│  │
│  └───────────┬────────────┘  │  └───────────┬────────────┘  │
│              │               │              │               │
│              │               │              ▼               │
│              │               │  ┌────────────────────────┐  │
│              │               │  │ I2S DMA Driver         │  │
│              │               │  │ (BCLK: 26, LRC: 25,    │  │
│              │               │  │  DOUT: 22 to DAC)      │  │
│              │               │  └───────────┬────────────┘  │
└──────────────┼───────────────┴──────────────┼───────────────┘
               │                              │
               ▼                              ▼
     Wi-Fi Network (AP/STA)         External PCM5102A DAC
     (OTA Updates & Controls)       (Analog Line-Out / Amp)
```

#### RF Coexistence & Clean Mode Switching:
Because the ESP32 shares a single 2.4 GHz radio between Wi-Fi and Classic Bluetooth:
1. **Bluetooth Mode**:
   - Classic Bluetooth A2DP Sink is active with highest radio priority.
   - Wi-Fi operates in low-bandwidth station mode purely for servicing occasional HTTP requests (Web UI & OTA). UDP streaming socket is closed.
2. **Wi-Fi UDP Mode**:
   - Bluetooth stack is cleanly shut down (`esp_a2d_sink_deinit()` and `esp_bluedroid_disable()`) to free ~45 KB of dynamic heap.
   - High-throughput UDP listening socket (Port 50005) receives uncompressed 48 kHz 16/24/32-bit PCM directly from the Android host app.
3. **Switching Trigger**:
   - Can be toggled on-the-fly via the Web Configuration Portal or via a short press of a physical boot/GPIO button.

---

### 4. Android Host App Compatibility

* **Wi-Fi Direct Streaming**: The host Android app continues direct streaming to the WROOM over UDP port 50005 using the existing low-latency engine (15–300 ms).
* **Bluetooth Mode Awareness**: When the receiver is in Bluetooth mode, the user can simply connect via standard Android Bluetooth settings and route system audio through the device.
* **Receivers Tab**: The Android app's "Receivers" screen can display the receiver's current mode (Wi-Fi UDP vs. Bluetooth) via the local HTTP telemetry endpoint.

---

### 5. Next Steps

When you receive your ESP32 WROOM board:
1. Review the proposed pinout and peripheral layout (I2S pins 26, 25, 22).
2. Generate the standalone **Phase 1 Bluetooth A2DP firmware** (`wroom_bt_receiver.cpp`).
3. Flash and verify audio fidelity with your PCM5102A DAC.
4. Merge into the **Phase 2 Unified Dual-Mode Firmware** when ready!
