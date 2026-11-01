/*
  C3 Music Receiver (UDP Edition)
  ESP32-C3 + SSD1306 72x40 OLED + PCM5102A / UDA1334A I2S DAC
  High-Performance Low-Latency UDP Audio Receiver (Port 50005)
  OLED: SDA 5, SCL 6
  I2S: BCLK 3, LRCLK 1, DOUT 10
*/

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <Update.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <driver/i2s.h>
#include <math.h>

static const char WIFI_SSID[] = "GFiber_2.4_Coverage_AECD9";
static const char WIFI_PASSWORD[] = "006BF4FD";
static const char DEVICE_NAME[] = "C3 Music Receiver";
static const char MDNS_HOSTNAME[] = "c3music";
static const char FIRMWARE_VERSION[] = "2.5";

static constexpr uint8_t OLED_SDA = 5;
static constexpr uint8_t OLED_SCL = 6;
static constexpr uint8_t OLED_WIDTH = 72;
static constexpr uint8_t OLED_HEIGHT = 40;

static constexpr int I2S_BCLK_PIN = 3;
static constexpr int I2S_LRCLK_PIN = 1;
static constexpr int I2S_DOUT_PIN = 10;

static constexpr uint16_t UDP_DEFAULT_PORT = 50005;

static constexpr uint32_t DEFAULT_SAMPLE_RATE = 48000;
static constexpr uint8_t DEFAULT_CHANNELS = 2;
static constexpr uint8_t DEFAULT_BITS_PER_SAMPLE = 16;

static constexpr uint8_t C3_PROTOCOL_VERSION = 1;
static constexpr size_t C3_FORMAT_HEADER_BYTES = 16;

// 64 KB Ring Buffer for stable streaming
static constexpr size_t AUDIO_RING_BYTES = 65536;
static constexpr size_t UDP_MAX_PACKET_BYTES = 1472;
static constexpr size_t I2S_WRITE_BYTES = 2048;
static constexpr uint32_t PREBUFFER_BYTES = 12000;
static constexpr uint32_t WIFI_RETRY_MS = 10000;
static constexpr uint32_t OLED_REFRESH_MS = 650;
static constexpr uint32_t STATUS_REFRESH_MS = 1000;
static constexpr uint32_t STREAM_READ_TIMEOUT_MS = 5000;

enum ReceiverState : uint8_t {
  RX_BOOTING = 0, RX_WIFI_CONNECTING, RX_WIFI_OFFLINE, RX_IDLE,
  RX_CONNECTING, RX_BUFFERING, RX_STREAMING, RX_STOPPED, RX_ERROR, RX_UPDATING
};

struct StreamFormat {
  uint32_t sampleRate = DEFAULT_SAMPLE_RATE;
  uint16_t channels = DEFAULT_CHANNELS;
  uint16_t bitsPerSample = DEFAULT_BITS_PER_SAMPLE;
  uint16_t audioFormat = 1;
  bool valid = false;
};

struct RuntimeStats {
  uint32_t reconnects = 0, underruns = 0, streamErrors = 0;
  uint32_t bytesReceived = 0, bytesPlayed = 0, sessionStartedMs = 0, lastReceiveMs = 0;
  String lastError;
};

struct Settings {
  uint16_t udpPort = UDP_DEFAULT_PORT;
  bool autoReconnect = true, oledEnabled = true, streamEnabled = true;
  uint16_t targetBufferMs = 250;
  uint8_t volumePercent = 50;
};

static U8G2_SSD1306_72X40_ER_F_HW_I2C oled(U8G2_R0, U8X8_PIN_NONE);
static bool oledAvailable = false;
static bool oledStreamingDisabled = false;
static Preferences preferences;
static WebServer server(80);
static WiFiUDP udpClient;
static Settings settings;
static StreamFormat streamFormat;
static RuntimeStats stats;
static volatile ReceiverState receiverState = RX_BOOTING;
static volatile bool stopRequested = false;
static volatile bool i2sReady = false;
static volatile bool bufferStarted = false;
static volatile bool udpListening = false;
static uint8_t audioRing[AUDIO_RING_BYTES];
static volatile size_t ringReadIndex = 0, ringWriteIndex = 0, ringCount = 0;
static portMUX_TYPE ringMux = portMUX_INITIALIZER_UNLOCKED;
static SemaphoreHandle_t audioDataSemaphore = nullptr;
static SemaphoreHandle_t i2sMux = nullptr;
static SemaphoreHandle_t udpMux = nullptr;
static TaskHandle_t streamTaskHandle = nullptr, playbackTaskHandle = nullptr;
static uint32_t lastWifiAttemptMs = 0, lastOledRefreshMs = 0, lastStatusRefreshMs = 0;
static bool mdnsStarted = false;

static void loadSettings() {
  preferences.begin("c3music", false);
  settings.udpPort = preferences.getUShort("udpport", UDP_DEFAULT_PORT);
  settings.autoReconnect = preferences.getBool("autorecon", true);
  settings.oledEnabled = preferences.getBool("oled", true);
  settings.streamEnabled = preferences.getBool("enabled", true);
  settings.targetBufferMs = preferences.getUShort("buffer", 120);
  settings.volumePercent = preferences.getUChar("volume", 50);
  if (settings.volumePercent > 100) settings.volumePercent = 100;
  if (settings.udpPort == 0) settings.udpPort = UDP_DEFAULT_PORT;
  if (settings.targetBufferMs < 15) settings.targetBufferMs = 15;
  if (settings.targetBufferMs > 300) settings.targetBufferMs = 300;
}

static void saveSettings() {
  preferences.putUShort("udpport", settings.udpPort);
  preferences.putBool("autorecon", settings.autoReconnect);
  preferences.putBool("oled", settings.oledEnabled);
  preferences.putBool("enabled", settings.streamEnabled);
  preferences.putUShort("buffer", settings.targetBufferMs);
  preferences.putUChar("volume", settings.volumePercent);
}

static void resetSettings() {
  preferences.clear(); preferences.end();
  settings.udpPort = UDP_DEFAULT_PORT;
  settings.autoReconnect = true;
  settings.oledEnabled = true; settings.streamEnabled = true;
  settings.targetBufferMs = 120;
  settings.volumePercent = 50;
  preferences.begin("c3music", false); saveSettings();
}

static const char* receiverStateName(ReceiverState v) {
  switch(v) {
    case RX_BOOTING: return "Booting"; case RX_WIFI_CONNECTING: return "WiFi connecting";
    case RX_WIFI_OFFLINE: return "WiFi offline"; case RX_IDLE: return "Ready";
    case RX_CONNECTING: return "Connecting"; case RX_BUFFERING: return "Buffering";
    case RX_STREAMING: return "Streaming"; case RX_STOPPED: return "Stopped";
    case RX_ERROR: return "Error"; case RX_UPDATING: return "Updating";
    default: return "Unknown";
  }
}

static void setReceiverState(ReceiverState value, const String &error = "") {
  receiverState = value;
  if (error.length() > 0) { stats.lastError = error; Serial.printf("[STATE] %s: %s\n", receiverStateName(value), error.c_str()); }
  else { Serial.printf("[STATE] %s\n", receiverStateName(value)); }
}

// Compiler type-safe ringWrite
static bool ringWrite(const uint8_t *data, size_t length) {
  if (!data || length == 0) return true;
  bool ok = true;
  portENTER_CRITICAL(&ringMux);
  size_t currentCount = (size_t)ringCount;
  size_t freeBytes = AUDIO_RING_BYTES - currentCount;
  if (length > freeBytes) { length = freeBytes; ok = false; }
  size_t currentWrite = (size_t)ringWriteIndex;
  size_t spaceToEnd = AUDIO_RING_BYTES - currentWrite;
  size_t first = (length < spaceToEnd) ? length : spaceToEnd;
  memcpy(audioRing + currentWrite, data, first);
  size_t second = length - first;
  if (second > 0) memcpy(audioRing, data + first, second);
  ringWriteIndex = (currentWrite + length) % AUDIO_RING_BYTES;
  ringCount = currentCount + length;
  portEXIT_CRITICAL(&ringMux);
  if (length > 0 && audioDataSemaphore) xSemaphoreGive(audioDataSemaphore);
  return ok;
}

// Compiler type-safe ringRead
static size_t ringRead(uint8_t *out, size_t maxLen) {
  if (!out || maxLen == 0) return 0;
  portENTER_CRITICAL(&ringMux);
  size_t currentCount = (size_t)ringCount;
  size_t len = (maxLen < currentCount) ? maxLen : currentCount;
  if (len > 0) {
    size_t currentRead = (size_t)ringReadIndex;
    size_t spaceToEnd = AUDIO_RING_BYTES - currentRead;
    size_t first = (len < spaceToEnd) ? len : spaceToEnd;
    memcpy(out, audioRing + currentRead, first);
    size_t second = len - first;
    if (second > 0) memcpy(out + first, audioRing, second);
    ringReadIndex = (currentRead + len) % AUDIO_RING_BYTES;
    ringCount = currentCount - len;
  }
  portEXIT_CRITICAL(&ringMux);
  return len;
}

static void ringClear() {
  portENTER_CRITICAL(&ringMux);
  ringReadIndex = ringWriteIndex = ringCount = 0;
  portEXIT_CRITICAL(&ringMux);
  bufferStarted = false;
}

static size_t ringSize() { portENTER_CRITICAL(&ringMux); size_t v = (size_t)ringCount; portEXIT_CRITICAL(&ringMux); return v; }
static uint8_t ringPercent() { return (uint8_t)((ringSize() * 100UL) / AUDIO_RING_BYTES); }

static void displayBegin() {
  Wire.begin(OLED_SDA, OLED_SCL);
  oled.begin();
  oled.setPowerSave(settings.oledEnabled ? 0 : 1);
  oled.clearBuffer();
  oled.setFont(u8g2_font_5x7_tf);
  oled.drawStr(0, 7, "C3 MUSIC");
  oled.drawStr(0, 17, "Starting...");
  oled.sendBuffer();
  oledAvailable = true;
}

static void displayLineCenter(const char *text, uint8_t y, const uint8_t *font) {
  oled.setFont(font);
  int w = oled.getStrWidth(text);
  int x = (OLED_WIDTH - w) / 2; if (x < 0) x = 0;
  oled.drawStr(x, y, text);
}

static void displayUpdate() {
  if (!oledAvailable || !settings.oledEnabled) return;

  if (receiverState == RX_STREAMING) {
    if (!oledStreamingDisabled) {
      oled.setPowerSave(1);
      oledStreamingDisabled = true;
      Serial.println("[OLED] Deactivated during streaming");
    }
    return;
  }

  if (oledStreamingDisabled) return;

  if (millis() - lastOledRefreshMs < OLED_REFRESH_MS) return;
  lastOledRefreshMs = millis();
  char buf[32];
  oled.clearBuffer();
  oled.setFont(u8g2_font_5x7_tf);
  oled.drawStr(0, 7, "C3 MUSIC");
  const char *st = "BOOT";
  switch(receiverState) {
    case RX_WIFI_CONNECTING: st = "WIFI"; break;
    case RX_WIFI_OFFLINE: st = "NO WIFI"; break;
    case RX_IDLE: st = "READY"; break;
    case RX_CONNECTING: st = "LINK"; break;
    case RX_BUFFERING: st = "BUFFER"; break;
    case RX_STREAMING: st = "PLAY"; break;
    case RX_STOPPED: st = "STOP"; break;
    case RX_ERROR: st = "ERROR"; break;
    case RX_UPDATING: st = "OTA"; break;
  }
  int sw = oled.getStrWidth(st);
  oled.drawStr(OLED_WIDTH - sw, 7, st);
  if (receiverState == RX_STREAMING || receiverState == RX_BUFFERING) {
    snprintf(buf, sizeof(buf), "%luk %s", (unsigned long)(streamFormat.sampleRate/1000), streamFormat.channels==2?"ST":"MO");
    displayLineCenter(buf, 19, u8g2_font_6x10_tf);
    snprintf(buf, sizeof(buf), "BUF %u%%", ringPercent());
    displayLineCenter(buf, 29, u8g2_font_4x6_tf);
    oled.drawFrame(0,33,OLED_WIDTH,7);    uint8_t w = (uint8_t)(((OLED_WIDTH-2)*ringPercent())/100);    if (w>0) oled.drawBox(1,34,w,5);
  } else {
    String msg;
    if (WiFi.status()==WL_CONNECTED) msg = WiFi.localIP().toString();
    else if (receiverState==RX_WIFI_CONNECTING) msg = "Connecting...";
    else msg = "Check WiFi";
    displayLineCenter(msg.c_str(), 22, u8g2_font_6x10_tf);
    if (receiverState==RX_ERROR && stats.lastError.length()>0) {
      String e = stats.lastError; if (e.length()>17) e=e.substring(0,17);
      displayLineCenter(e.c_str(), 38, u8g2_font_4x6_tf);
    } else {
      displayLineCenter("c3music.local", 38, u8g2_font_4x6_tf);
    }
  }
  oled.sendBuffer();
}

static void beginMdnsIfNeeded();

static void connectWifiIfNeeded() {
  if (WiFi.status()==WL_CONNECTED) { beginMdnsIfNeeded(); return; }
  if (millis()-lastWifiAttemptMs < WIFI_RETRY_MS) return;
  lastWifiAttemptMs = millis();
  setReceiverState(RX_WIFI_CONNECTING);
  WiFi.mode(WIFI_STA); WiFi.setHostname(MDNS_HOSTNAME);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf("[WIFI] Connecting to %s\n", WIFI_SSID);
}

static void beginMdnsIfNeeded() {
  if (mdnsStarted || WiFi.status()!=WL_CONNECTED) return;
  if (MDNS.begin(MDNS_HOSTNAME)) { MDNS.addService("http","tcp",80); mdnsStarted=true; Serial.printf("[MDNS] http://%s.local/\n", MDNS_HOSTNAME); }
  else Serial.println("[MDNS] Failed");
}

static uint32_t activeI2sSr = 0;
static uint16_t activeI2sCh = 0;
static uint16_t activeI2sBits = 0;

static void endI2S() {
  if (i2sMux) xSemaphoreTake(i2sMux, portMAX_DELAY);
  if (i2sReady) { i2s_driver_uninstall(I2S_NUM_0); i2sReady=false; }
  activeI2sSr = 0;
  activeI2sCh = 0;
  activeI2sBits = 0;
  if (i2sMux) xSemaphoreGive(i2sMux);
}

static uint32_t targetPrebufferBytes() {
  uint64_t bytesPerSecond = (uint64_t)streamFormat.sampleRate *
                            (uint64_t)streamFormat.channels *
                            (uint64_t)(streamFormat.bitsPerSample / 8);
  if (bytesPerSecond == 0) bytesPerSecond =
      (uint64_t)DEFAULT_SAMPLE_RATE * DEFAULT_CHANNELS * (DEFAULT_BITS_PER_SAMPLE / 8);
  uint64_t bytes = (bytesPerSecond * settings.targetBufferMs) / 1000ULL;
  if (bytes < 4096) bytes = 4096;
  const uint64_t maxBufferBytes = (uint64_t)AUDIO_RING_BYTES - 4096ULL;
  if (bytes > maxBufferBytes) bytes = maxBufferBytes;
  return (uint32_t)bytes;
}

static void stopUdpListener() {
  if (udpMux) xSemaphoreTake(udpMux, portMAX_DELAY);
  if (udpListening) {
    udpClient.stop();
    udpListening = false;
  }
  if (udpMux) xSemaphoreGive(udpMux);
}

static bool beginI2S(uint32_t sr, uint16_t ch, uint16_t bits) {
  if (sr != DEFAULT_SAMPLE_RATE || (bits != 16 && bits != 24 && bits != 32) || (ch != 1 && ch != 2)) return false;
  
  if (i2sMux) xSemaphoreTake(i2sMux, portMAX_DELAY);
  if (i2sReady) { i2s_driver_uninstall(I2S_NUM_0); i2sReady=false; }

  // PCM5102A: 16-bit uses 16-bit slots; 24-bit and 32-bit use standard 32-bit slots
  i2s_bits_per_sample_t bps = (bits == 16) ? I2S_BITS_PER_SAMPLE_16BIT : I2S_BITS_PER_SAMPLE_32BIT;

  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER|I2S_MODE_TX),
    .sample_rate = sr,
    .bits_per_sample = bps,
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = 1024,
    .use_apll = false,          // ESP32-C3 does NOT support APLL!
    .tx_desc_auto_clear = true,  // Automatically clears DMA buffer on underrun
    .fixed_mclk = 0             // Must be 0!
  };
  
  if (i2s_driver_install(I2S_NUM_0, &cfg, 0, nullptr) != ESP_OK) {
    if (i2sMux) xSemaphoreGive(i2sMux);
    return false;
  }

  i2s_pin_config_t pin = {
    .bck_io_num = I2S_BCLK_PIN,
    .ws_io_num = I2S_LRCLK_PIN,
    .data_out_num = I2S_DOUT_PIN,
    .data_in_num = I2S_PIN_NO_CHANGE
  };

  if (i2s_set_pin(I2S_NUM_0, &pin) != ESP_OK) {
    i2s_driver_uninstall(I2S_NUM_0);
    if (i2sMux) xSemaphoreGive(i2sMux);
    return false;
  }

  i2s_zero_dma_buffer(I2S_NUM_0);
  i2sReady = true;
  activeI2sSr = sr;
  activeI2sCh = ch;
  activeI2sBits = bits;
  if (i2sMux) xSemaphoreGive(i2sMux);

  Serial.printf("[I2S] %lu Hz, %u-bit (slot: %s), %s, BCLK=%d WS=%d DOUT=%d\n",
                (unsigned long)sr, bits, (bps == I2S_BITS_PER_SAMPLE_16BIT) ? "16" : "32",
                ch==2?"Stereo":"Mono", I2S_BCLK_PIN, I2S_LRCLK_PIN, I2S_DOUT_PIN);
  return true;
}

static uint32_t readLe32(const uint8_t *p){ return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }

// Parse optional 16-byte C3 header from UDP packet
static bool parseC3HeaderIfPresent(const uint8_t *pkt, size_t len, size_t &payloadOffset) {
  payloadOffset = 0;
  if (len < C3_FORMAT_HEADER_BYTES) return false;
  if (memcmp(pkt, "C3MS", 4) != 0) return false;

  const uint8_t version = pkt[4];
  const uint16_t bits = pkt[5];
  const uint16_t channels = pkt[6];
  const uint32_t sampleRate = readLe32(pkt + 8);
  const uint32_t frameSize = readLe32(pkt + 12);
  const uint32_t expectedFrameSize = (uint32_t)channels * (uint32_t)(bits / 8);

  if (version == C3_PROTOCOL_VERSION &&
      (bits == 16 || bits == 24 || bits == 32) &&
      (channels == 1 || channels == 2) &&
      (sampleRate == DEFAULT_SAMPLE_RATE) &&
      frameSize == expectedFrameSize &&
      expectedFrameSize > 0) {
    streamFormat.sampleRate = sampleRate;
    streamFormat.channels = channels;
    streamFormat.bitsPerSample = bits;
    streamFormat.audioFormat = 1;
    streamFormat.valid = true;
    payloadOffset = C3_FORMAT_HEADER_BYTES;
    Serial.printf("[UDP] Format Header C3MS: %lu Hz, %u-bit, %s\n",
                  (unsigned long)sampleRate, bits, channels == 2 ? "Stereo" : "Mono");
    return true;
  }
  return false;
}

// DRAM Static buffers: completely eliminates task stack overflow risk
static uint8_t playbackIn[I2S_WRITE_BYTES / 2];
static uint8_t playbackOut[I2S_WRITE_BYTES * 2];

static void playbackTask(void*) {
  for(;;){
    if (!i2sReady || !bufferStarted){ vTaskDelay(pdMS_TO_TICKS(10)); continue; }

    size_t want = sizeof(playbackIn);
    size_t n = ringRead(playbackIn, want);
    if (n==0){
      stats.underruns++;
      if (audioDataSemaphore) xSemaphoreTake(audioDataSemaphore, pdMS_TO_TICKS(20));
      else vTaskDelay(pdMS_TO_TICKS(4));
      continue;
    }

    const uint8_t *writeBuf = playbackIn;
    size_t writeLen = n;
    uint8_t volume = settings.volumePercent;

    if (streamFormat.bitsPerSample == 16) {
      if (volume < 100) {
        int16_t *samples = reinterpret_cast<int16_t*>(playbackIn);
        size_t sampleCount = n / sizeof(int16_t);
        for (size_t i = 0; i < sampleCount; ++i) {
          samples[i] = (int16_t)(((int32_t)samples[i] * volume) / 100);
        }
      }

      if (streamFormat.channels==1) {
        size_t samples = n / 2;
        int16_t *src = reinterpret_cast<int16_t*>(playbackIn);
        int16_t *dst = reinterpret_cast<int16_t*>(playbackOut);
        for (size_t i=0;i<samples;i++) { dst[i*2]=src[i]; dst[i*2+1]=src[i]; }
        writeBuf = playbackOut;
        writeLen = samples * 4;
      }
    } else if (streamFormat.bitsPerSample == 24) {
      // Unpack 24-bit Little Endian (3 bytes) into 32-bit slot for PCM5102A DAC
      size_t totalSamples = n / 3;
      int32_t *dst = reinterpret_cast<int32_t*>(playbackOut);
      size_t outIndex = 0;

      for (size_t i = 0; i < totalSamples; ++i) {
        size_t bytePos = i * 3;
        int32_t sample = (int32_t)((uint32_t)playbackIn[bytePos] |
                                  ((uint32_t)playbackIn[bytePos + 1] << 8) |
                                  ((uint32_t)playbackIn[bytePos + 2] << 16));
        if (sample & 0x800000) sample |= 0xFF000000;

        if (volume < 100) {
          sample = (int32_t)(((int64_t)sample * volume) / 100);
        }

        int32_t outSample = sample << 8; // MSB aligned in 32-bit slot

        if (streamFormat.channels == 1) {
          dst[outIndex++] = outSample;
          dst[outIndex++] = outSample;
        } else {
          dst[outIndex++] = outSample;
        }
      }

      writeBuf = playbackOut;
      writeLen = outIndex * sizeof(int32_t);
    } else if (streamFormat.bitsPerSample == 32) {
      int32_t *src = reinterpret_cast<int32_t*>(playbackIn);
      size_t totalSamples = n / sizeof(int32_t);

      if (volume < 100) {
        for (size_t i = 0; i < totalSamples; ++i) {
          src[i] = (int32_t)(((int64_t)src[i] * volume) / 100);
        }
      }

      if (streamFormat.channels == 1) {
        int32_t *dst = reinterpret_cast<int32_t*>(playbackOut);
        for (size_t i = 0; i < totalSamples; ++i) {
          dst[i * 2] = src[i];
          dst[i * 2 + 1] = src[i];
        }
        writeBuf = playbackOut;
        writeLen = totalSamples * 2 * sizeof(int32_t);
      }
    }

    if (i2sMux) xSemaphoreTake(i2sMux, portMAX_DELAY);
    bool ready = i2sReady;
    size_t w=0;
    esp_err_t r = ready ? i2s_write(I2S_NUM_0,writeBuf,writeLen,&w,pdMS_TO_TICKS(40)) : ESP_FAIL;
    if (i2sMux) xSemaphoreGive(i2sMux);

    if (r==ESP_OK && w>0) stats.bytesPlayed += (streamFormat.channels==1) ? (w/2) : w;
    else vTaskDelay(pdMS_TO_TICKS(2));
  }
}

static uint8_t udpPacketBuf[UDP_MAX_PACKET_BYTES];

static void streamTask(void*) {
  for(;;){
    if (WiFi.status() != WL_CONNECTED) {
      stopUdpListener();
      endI2S();
      ringClear();
      if (settings.streamEnabled && !stopRequested && receiverState!=RX_WIFI_OFFLINE && receiverState!=RX_WIFI_CONNECTING && receiverState!=RX_UPDATING) {
        Serial.printf("[WIFI] Disconnected (status=%d)\n", (int)WiFi.status());
        setReceiverState(RX_WIFI_OFFLINE, "WiFi disconnected");
      }
      vTaskDelay(pdMS_TO_TICKS(500));
      continue;
    }

    if (!settings.streamEnabled || stopRequested) {
      stopUdpListener();
      endI2S();
      ringClear();
      if (receiverState != RX_STOPPED) setReceiverState(RX_STOPPED);
      vTaskDelay(pdMS_TO_TICKS(150));
      continue;
    }

    // Ensure UDP listener is bound to port
    if (!udpListening) {
      if (udpMux) xSemaphoreTake(udpMux, portMAX_DELAY);
      if (udpClient.begin(settings.udpPort)) {
        udpListening = true;
        Serial.printf("[UDP] Listening on port %u\n", settings.udpPort);
        setReceiverState(RX_IDLE);
      } else {
        stats.streamErrors++;
        setReceiverState(RX_ERROR, "UDP bind failed");
      }
      if (udpMux) xSemaphoreGive(udpMux);
      vTaskDelay(pdMS_TO_TICKS(50));
      continue;
    }

    // Process incoming UDP audio datagrams
    int packetSize = udpClient.parsePacket();
    if (packetSize > 0) {
      int len = udpClient.read(udpPacketBuf, sizeof(udpPacketBuf));
      if (len > 0) {
        size_t payloadOffset = 0;
        if (!streamFormat.valid || (len >= (int)C3_FORMAT_HEADER_BYTES && memcmp(udpPacketBuf, "C3MS", 4) == 0)) {
          if (parseC3HeaderIfPresent(udpPacketBuf, len, payloadOffset)) {
            if (!i2sReady || activeI2sSr != streamFormat.sampleRate || activeI2sBits != streamFormat.bitsPerSample || activeI2sCh != streamFormat.channels) {
              beginI2S(streamFormat.sampleRate, streamFormat.channels, streamFormat.bitsPerSample);
            }
          } else if (!streamFormat.valid) {
            Serial.println("[UDP] Waiting for valid C3MS format header");
          }
        }

        if (!i2sReady) {
          beginI2S(streamFormat.sampleRate, streamFormat.channels, streamFormat.bitsPerSample);
        }

        const uint8_t *audioData = udpPacketBuf + payloadOffset;
        size_t audioLen = len - payloadOffset;

        if (audioLen > 0) {
          stats.bytesReceived += audioLen;
          stats.lastReceiveMs = millis();
          if (stats.sessionStartedMs == 0) stats.sessionStartedMs = millis();

          if (!ringWrite(audioData, audioLen)) {
            vTaskDelay(pdMS_TO_TICKS(1));
          }

          if (!bufferStarted && ringSize() >= targetPrebufferBytes()) {
            bufferStarted = true;
            setReceiverState(RX_STREAMING);
          }
        }
      }
    } else {
      // Packet timeout detection
      if (bufferStarted && (millis() - stats.lastReceiveMs > STREAM_READ_TIMEOUT_MS)) {
        bufferStarted = false;
        stats.sessionStartedMs = 0;
        setReceiverState(RX_IDLE);
        Serial.println("[UDP] Audio stream idle (silence/paused)");
      }
      vTaskDelay(pdMS_TO_TICKS(1));
    }
  }
}

static void startStreaming(){
  stopRequested = false;
  settings.streamEnabled = true;
  saveSettings();
  setReceiverState(RX_IDLE);
}

static void stopStreaming(){
  stopRequested = true;
  settings.streamEnabled = false;
  saveSettings();
  stopUdpListener();
  ringClear();
  endI2S();
  setReceiverState(RX_STOPPED);
}

static void reconnectStreaming(){
  stopRequested = true;
  stopUdpListener();
  ringClear();
  endI2S();
  delay(50);
  stopRequested = false;
  settings.streamEnabled = true;
  saveSettings();
  setReceiverState(RX_IDLE);
}

static String jsonEscape(const String &in){ String o; o.reserve(in.length()+8); for(size_t i=0;i<in.length();++i){ char c=in[i]; switch(c){ case'\\':o+="\\\\";break; case'"':o+="\\\"";break; case'\n':o+="\\n";break; case'\r':o+="\\r";break; case'\t':o+="\\t";break; default: if((uint8_t)c>=0x20)o+=c; } } return o; }

static String makeStatusJson(){
  String ip=(WiFi.status()==WL_CONNECTED)?WiFi.localIP().toString():"";
  String ssid=(WiFi.status()==WL_CONNECTED)?WiFi.SSID():"";
  int rssi=(WiFi.status()==WL_CONNECTED)?WiFi.RSSI():0;
  uint32_t sess=0; if (stats.sessionStartedMs>0 && (receiverState==RX_STREAMING||receiverState==RX_BUFFERING)) sess=(millis()-stats.sessionStartedMs)/1000UL;
  String r; r.reserve(1050);
  r+="{";
  r+="\"device\":\""+jsonEscape(DEVICE_NAME)+"\",";
  r+="\"version\":\""+String(FIRMWARE_VERSION)+"\",";
  r+="\"state\":\""+String(receiverStateName(receiverState))+"\",";
  r+="\"stateCode\":"+String((int)receiverState)+",";
  r+="\"mode\":\"UDP Stream\",";
  r+="\"streamEnabled\":"+String(settings.streamEnabled?"true":"false")+",";
  r+="\"wifiConnected\":"+String(WiFi.status()==WL_CONNECTED?"true":"false")+",";
  r+="\"ssid\":\""+jsonEscape(ssid)+"\",";
  r+="\"ip\":\""+jsonEscape(ip)+"\",";
  r+="\"hostname\":\""+String(MDNS_HOSTNAME)+".local\",";
  r+="\"rssi\":"+String(rssi)+",";
  r+="\"udpPort\":"+String(settings.udpPort)+",";
  r+="\"formatValid\":"+String(streamFormat.valid?"true":"false")+",";
  r+="\"sampleRate\":"+String(streamFormat.valid?streamFormat.sampleRate:0)+",";
  r+="\"channels\":"+String(streamFormat.valid?streamFormat.channels:0)+",";
  r+="\"bits\":"+String(streamFormat.valid?streamFormat.bitsPerSample:0)+",";
  r+="\"volume\":"+String(settings.volumePercent)+",";
  r+="\"bufferBytes\":"+String((uint32_t)ringSize())+",";
  r+="\"bufferPercent\":"+String(ringPercent())+",";
  r+="\"bytesReceived\":"+String(stats.bytesReceived)+",";
  r+="\"bytesPlayed\":"+String(stats.bytesPlayed)+",";
  r+="\"underruns\":"+String(stats.underruns)+",";
  r+="\"reconnects\":"+String(stats.reconnects)+",";
  r+="\"streamErrors\":"+String(stats.streamErrors)+",";
  r+="\"sessionSeconds\":"+String(sess)+",";
  r+="\"heap\":"+String(ESP.getFreeHeap())+",";
  r+="\"cpuTemp\":"+String(temperatureRead(), 1)+",";
  r+="\"flashSize\":"+String(ESP.getFlashChipSize())+",";
  r+="\"oled\":"+String(settings.oledEnabled?"true":"false")+",";
  r+="\"lastError\":\""+jsonEscape(stats.lastError)+"\"";
  r+="}";
  return r;
}

static String makeConfigJson(){
  String r; r.reserve(450);
  r+="{";
  r+="\"udpPort\":"+String(settings.udpPort)+",";
  r+="\"autoReconnect\":"+String(settings.autoReconnect?"true":"false")+",";
  r+="\"oled\":"+String(settings.oledEnabled?"true":"false")+",";
  r+="\"streamEnabled\":"+String(settings.streamEnabled?"true":"false")+",";
  r+="\"bufferMs\":"+String(settings.targetBufferMs)+",";
  r+="\"volume\":"+String(settings.volumePercent)+",";
  r+="\"i2s\":{\"bclk\":"+String(I2S_BCLK_PIN)+",\"lrclk\":"+String(I2S_LRCLK_PIN)+",\"dout\":"+String(I2S_DOUT_PIN)+"}";
  r+="}";  return r;
}

static void sendJson(int code, const String &body){ server.sendHeader("Cache-Control","no-store,no-cache,must-revalidate,max-age=0"); server.sendHeader("Pragma","no-cache"); server.send(code,"application/json",body); }
static bool requirePost(){ if (server.method()!=HTTP_POST){ sendJson(405,"{\"ok\":false,\"error\":\"POST required\"}"); return false; } return true; }

static String htmlPage(){
  return R"HTML(<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#000000">
<title>C3 Music Receiver (UDP)</title>
<style>
@import url('https://fonts.googleapis.com/css2?family=Lexend:wght@300;400;500;600;650;700;750&display=swap');
:root{color-scheme:dark;--bg:#000;--surface:#080808;--line:#242424;--text:rgba(255,255,255,.9);--sub:rgba(255,255,255,.6);--muted:#777;--red:#ff8585;--green:#b5f79a;--blue:#91baff;--amber:#ffd18a;--font-ui:'Lexend',system-ui,sans-serif;--fs-small:11px;--fs-label:11px;--fs-value:12px;--fs-subsection:12px}
*{box-sizing:border-box}html{background:#000;scroll-behavior:smooth}body{margin:0;background:#000;color:var(--text);font-family:var(--font-ui);font-weight:400;-webkit-font-smoothing:antialiased;padding-bottom:104px}button,input,select,textarea{font:inherit;font-family:var(--font-ui)}button{touch-action:manipulation}main{width:min(640px,100%);margin:0 auto;padding:14px 19px 30px}.tab{display:none}.tab.active{display:block;animation:screenIn .24s ease-out}@keyframes screenIn{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:none}}
.eyebrow,.data-heading h2,.data-heading>span,.slider-head h3,.network-action h3,.field label,.stat .k,.label,.small,.nav-label{ text-transform:uppercase}.eyebrow{font-size:10px;font-weight:600;letter-spacing:.15em;color:var(--sub)}.player-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:9px 1px 0}.chip{display:inline-flex;align-items:center;gap:8px;min-height:29px;padding:0 11px;border:1px solid #ffffff12;border-radius:999px;background:linear-gradient(180deg,#ffffff07,#ffffff02);font-size:10px;font-weight:600;letter-spacing:.1em;text-transform:uppercase;color:var(--sub);transition:color .2s,border-color .2s,background .2s}.chip.ok{border-color:#b5f79a30;background:linear-gradient(180deg,#b5f79a0c,#b5f79a03)}.chip.bad{border-color:#ff858530;background:linear-gradient(180deg,#ff85850c,#ff858503)}.chip.connecting{border-color:#91baff30;background:linear-gradient(180deg,#91baff0c,#91baff03)}.chip:before{content:"";width:7px;height:7px;border-radius:50%;background:#777;box-shadow:0 0 9px currentColor}.chip.ok{color:var(--green)}.chip.ok:before{background:var(--green);box-shadow:0 0 6px #b5f79a99,0 0 14px #b5f79a55}.chip.bad{color:var(--red)}.chip.bad:before{background:var(--red);box-shadow:0 0 8px #ff858599}.chip.connecting{color:var(--blue)}.chip.connecting:before{background:var(--blue);box-shadow:0 0 8px #91baff99}
.player-stage{position:relative;display:flex;flex-direction:column;align-items:center;text-align:center;padding:35px 4px 22px}.player-orbit{--state-color:var(--red);width:138px;height:138px;border-radius:50%;border:1px solid color-mix(in srgb,var(--state-color) 45%,transparent);background:radial-gradient(circle,color-mix(in srgb,var(--state-color) 9%,#000) 0%,#000 70%);display:grid;place-items:center;position:relative;margin:7px 0 23px;box-shadow:0 0 28px color-mix(in srgb,var(--state-color) 8%,transparent),inset 0 0 22px color-mix(in srgb,var(--state-color) 5%,transparent);transition:border-color .25s,box-shadow .25s,background .25s}.player-orbit:before{content:"";position:absolute;inset:9px;border:1px solid color-mix(in srgb,var(--state-color) 24%,transparent);border-radius:50%}.player-orbit:after{content:"";position:absolute;inset:-7px;border:1px solid color-mix(in srgb,var(--state-color) 13%,transparent);border-radius:50%}.player-orbit.state-green{--state-color:var(--green)}.player-orbit.state-red{--state-color:var(--red)}.player-orbit.state-blue{--state-color:var(--blue)}.player-orbit.state-amber{--state-color:var(--amber)}.orbit-core{width:69px;height:69px;border-radius:50%;background:transparent;color:var(--state-color);display:grid;place-items:center;filter:drop-shadow(0 0 8px color-mix(in srgb,var(--state-color) 35%,transparent));transition:color .25s}.orbit-core svg{width:33px;height:33px;fill:currentColor}.hero{font-size:clamp(29px,8vw,43px);font-weight:650;letter-spacing:-.055em;line-height:1.08;margin:0 0 11px;color:rgba(255,255,255,.9);text-transform:uppercase;overflow-wrap:anywhere}.format-line{font-size:11px;line-height:1.5;color:var(--sub);letter-spacing:.045em;text-transform:uppercase;overflow-wrap:anywhere}.info-strip{width:100%;display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:27px;padding:13px 0 0;border-top:1px solid #202020;font-size:10px;letter-spacing:.1em;color:var(--sub);text-align:left;text-transform:uppercase}.info-strip strong{font-size:12px;letter-spacing:0;color:var(--amber);font-weight:500;text-align:right;overflow-wrap:anywhere}
.volume-panel{padding:21px 1px 3px;border-top:1px solid #191919}.slider-head{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:8px}.slider-head h3,.data-heading h2,.network-action h3{margin:0;font-size:var(--fs-subsection);font-weight:600;letter-spacing:.1em;color:var(--text)}.slider-value{font-size:20px;font-weight:500;letter-spacing:-.04em;color:var(--red);text-shadow:0 0 13px #ff858522}.volume-row{display:flex;align-items:center;gap:12px}.volume-row input{flex:1;min-width:0}.small{font-size:var(--fs-small);line-height:1.6;color:var(--sub);letter-spacing:.035em}.volume-panel>.small{margin-top:4px}input[type=range]{--volume:50%;width:100%;height:36px;margin:2px 0;appearance:none;background:transparent;accent-color:var(--red);cursor:pointer}#bufferInput{--volume:36.8%;accent-color:var(--amber)}#bufferInput::-webkit-slider-runnable-track{height:3px;background:linear-gradient(90deg,var(--amber) 0%,var(--amber) var(--volume),#303030 var(--volume),#303030 100%);border-radius:99px}#bufferInput::-webkit-slider-thumb{appearance:none;width:15px;height:15px;border-radius:50%;background:var(--amber);margin-top:-6px;border:2px solid #000;box-shadow:0 0 0 1px var(--amber),0 0 9px #ffd18a55}#bufferInput::-moz-range-track{height:3px;background:#303030;border-radius:99px}#bufferInput::-moz-range-progress{height:3px;background:var(--amber)}#bufferInput::-moz-range-thumb{width:12px;height:12px;border-radius:50%;background:var(--amber);border:2px solid #000;box-shadow:0 0 8px #ffd18a55}input[type=range]::-webkit-slider-runnable-track{height:3px;background:linear-gradient(90deg,var(--red) 0%,var(--red) var(--volume),#303030 var(--volume),#303030 100%);border-radius:99px}input[type=range]::-webkit-slider-thumb{appearance:none;width:17px;height:17px;border-radius:50%;background:var(--red);margin-top:-7px;border:2px solid #000;box-shadow:0 0 0 1px var(--red),0 0 10px #ff858566}input[type=range]:active::-webkit-slider-thumb{transform:scale(1.08)}.mute-btn{min-height:35px;padding:0 11px;border:1px solid #333;border-radius:99px;background:transparent;color:var(--sub);font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.07em}.mute-btn.muted{color:var(--red);border-color:#754343}
.transport-controls{display:grid;grid-template-columns:1fr 1fr 1.2fr;gap:0;margin:18px 0 33px;border-top:1px solid #252525;border-bottom:1px solid #252525}.action{display:flex;align-items:center;justify-content:center;gap:8px;min-height:47px;padding:10px 9px;border:0;border-radius:0;background:transparent;color:var(--text);font-size:10px;font-weight:600;letter-spacing:.1em;cursor:pointer;text-transform:uppercase}.action:active{transform:scale(.98)}.transport-controls .action+.action{border-left:1px solid #252525}.transport-controls .action{min-height:47px;padding:10px 9px;font-size:10px;font-weight:600;letter-spacing:.1em;gap:8px}.transport-controls .action svg{width:17px;height:17px;flex:0 0 17px}.action.primary{color:var(--green)}.action.secondary{background:transparent;color:var(--sub)}.transport-controls .action:nth-child(2){color:var(--red)}.transport-controls .action:nth-child(3){color:var(--blue)}.action.danger{background:transparent;color:var(--red)}.action:disabled{opacity:.45}.icon-action svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.icon-action:first-child svg{fill:currentColor;stroke:none}
.data-heading{gap:11px}.section-icon{width:29px;height:29px;flex:0 0 29px;display:grid;place-items:center;border:1px solid currentColor;border-radius:9px;background:color-mix(in srgb,currentColor 7%,transparent);box-shadow:inset 0 0 12px color-mix(in srgb,currentColor 5%,transparent)}.section-icon svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:1.65;stroke-linecap:round;stroke-linejoin:round}.section-icon.icon-blue{color:var(--blue);border-color:#91baff38}.section-icon.icon-green{color:var(--green);border-color:#b5f79a38}.section-icon.icon-amber{color:var(--amber);border-color:#ffd18a38}.section-icon.icon-violet{color:#c4a7ff;border-color:#c4a7ff38}.section-icon.icon-red{color:var(--red);border-color:#ff858538}.data-heading h2{letter-spacing:.115em}.data-section{margin:0 0 30px}.data-heading{display:flex;align-items:center;gap:10px;margin-bottom:13px}.data-heading>span{font-size:10px;font-weight:500;color:var(--sub);letter-spacing:.1em}.data-heading h2{font-size:12px}.row{display:flex;justify-content:space-between;align-items:center;gap:14px;min-height:39px;padding:9px 0;border-bottom:1px solid #171717}.row:last-child{border-bottom:0}.label{font-size:var(--fs-label);color:var(--sub);letter-spacing:.04em}.value{max-width:64%;font-size:var(--fs-value);font-weight:450;color:var(--text);text-align:right;overflow-wrap:anywhere}.health-grid{display:grid;grid-template-columns:1fr 1fr;gap:22px;margin:5px 0 13px}.stat{min-height:68px;padding:7px 0 12px;background:transparent;border:0;border-bottom:1px solid #262626;border-radius:0}.stat .k{font-size:10px;color:var(--sub);font-weight:500;letter-spacing:.1em;margin-bottom:8px}.stat .v{font-size:29px;font-weight:500;letter-spacing:-.05em;overflow-wrap:anywhere;color:var(--amber)}
.screen-heading{padding:17px 1px 22px;border-bottom:1px solid #202020;margin-bottom:14px}.screen-heading h2{font-size:clamp(25px,7vw,33px);letter-spacing:-.055em;font-weight:650;text-transform:uppercase;margin:9px 0;color:rgba(255,255,255,.9)}.screen-heading.player-screen-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.player-screen-heading h2{min-width:0}.chip{display:inline-flex;flex:0 0 auto;align-items:center;gap:7px;min-height:29px;padding:0 11px;border:1px solid #ffffff20;border-radius:999px;background:linear-gradient(180deg,#ffffff08,#ffffff02);font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:var(--sub);white-space:nowrap}.chip:before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor;box-shadow:0 0 8px currentColor}.chip.ok{color:var(--green);border-color:#b5f79a40;background:#b5f79a0a}.chip.bad{color:var(--red);border-color:#ff858540;background:#ff85850a}.chip.connecting{color:var(--blue);border-color:#91baff40;background:#91baff0a}.screen-heading p{margin:0;color:var(--sub);font-size:11px;line-height:1.6;text-transform:uppercase;letter-spacing:.025em}.network-status{display:flex;align-items:center;gap:17px;padding:22px 0 23px;border-bottom:1px solid #202020;margin-bottom:22px}.network-mark{width:60px;height:60px;border:1px solid #83afff55;border-radius:50%;display:grid;place-items:center;background:radial-gradient(circle,#83afff10,#000 70%);color:var(--blue);box-shadow:0 0 18px #83afff0d}.network-mark svg{width:27px;height:27px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.network-name{font-size:21px;font-weight:550;letter-spacing:-.035em;margin:4px 0;color:var(--text);overflow-wrap:anywhere}.network-action{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:18px 0;border-top:1px solid #202020}.network-action p{margin:5px 0 0}.network-action{display:block;width:100%}.network-action .action{position:relative;display:flex;box-sizing:border-box;align-items:center;justify-content:center;flex:0 0 100%;width:100%;min-height:58px;border:1px solid #83afff66;border-radius:999px;color:var(--blue);padding:15px 52px;font-size:12px;font-weight:700;letter-spacing:.08em;text-align:center;background:linear-gradient(180deg,#83afff0c,#83afff03);box-shadow:inset 0 0 18px #83afff08}.network-action .action span{display:block;width:100%;text-align:center;white-space:nowrap}.network-action .action svg{position:absolute;left:20px;top:50%;transform:translateY(-50%);width:21px;height:21px;flex:0 0 21px;pointer-events:none}
.settings-group{margin-bottom:25px;padding-bottom:21px;border-bottom:1px solid #202020}.settings-group:last-child{border-bottom:0}.field{margin:18px 0}.field label{display:block;color:var(--sub);font-size:10px;font-weight:550;letter-spacing:.1em;margin:0 0 8px}input[type=text],input[type=number]{width:100%;height:45px;border:0;border-bottom:1px solid #333;outline:none;background:#050505;color:var(--text);border-radius:0;padding:0 10px}input[type=text]:focus,input[type=number]:focus{border-color:var(--blue);box-shadow:0 1px 0 var(--blue)}.switchrow{display:flex;justify-content:space-between;align-items:center;gap:14px;min-height:50px;padding:10px 0;border-bottom:1px solid #1c1c1c}.switchrow>span{font-size:11px;color:var(--text);text-transform:uppercase;letter-spacing:.035em}.switch{appearance:none;flex:0 0 auto;width:41px;height:23px;border-radius:99px;background:#171717;border:1px solid #444;position:relative;cursor:pointer}.switch:before{content:"";position:absolute;width:15px;height:15px;left:3px;top:3px;background:#777;border-radius:50%;transition:transform .18s ease}.switch:checked{background:#b5f79a22;border-color:var(--green)}.switch:checked:before{transform:translateX(17px);background:var(--green);box-shadow:0 0 8px #b5f79a66}.settings-action{margin-top:14px}.settings-action .action{width:100%;border:1px solid #333;border-radius:99px;min-height:43px}.settings-action .action:not(.secondary):not(.danger){color:var(--green);border-color:#b5f79a55}.file{width:100%;padding:11px 0;border:0;border-bottom:1px dashed #444;border-radius:0;background:transparent;color:var(--sub);font-size:11px}.file::file-selector-button{border:1px solid #444;background:#080808;color:var(--text);border-radius:99px;padding:8px 12px;margin-right:9px;font-family:var(--font-ui)}progress{width:100%;height:4px;border-radius:99px;accent-color:var(--green);margin-top:12px}.maintenance-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px}.maintenance-actions button{border:1px solid #333;border-radius:99px}.maintenance-actions button:last-child{grid-column:1/-1}
#toast{position:fixed;z-index:80;left:50%;bottom:100px;transform:translate(-50%,12px);opacity:0;pointer-events:none;max-width:calc(100vw - 32px);background:#111;color:var(--text);border:1px solid #444;border-radius:12px;padding:12px 16px;font-size:12px;font-weight:550;box-shadow:0 12px 30px #000;transition:opacity .18s ease,transform .18s ease}#toast.show{opacity:1;transform:translate(-50%,0)}.hidden{display:none!important}
nav{position:fixed;z-index:40;bottom:0;left:0;right:0;display:flex;justify-content:center;padding:9px 14px calc(9px + env(safe-area-inset-bottom));background:linear-gradient(180deg,rgba(0,0,0,0),#000 45%)}nav .nav-inner{width:min(520px,100%);display:flex;align-items:center;justify-content:space-between;gap:10px}nav button{flex:1 1 0;min-width:0;min-height:55px;border:1px solid #292929;border-radius:99px;background:#050505;color:var(--sub);font-weight:550;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;padding:9px 10px;transition:background .18s ease,color .18s ease}nav button.active{background:#111;color:var(--text);border-color:#555}nav .nav-inner button svg{width:20px;height:20px;flex:0 0 20px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}nav .nav-inner button:first-child svg{fill:currentColor;stroke:none}.nav-label{font-size:10px;white-space:nowrap;letter-spacing:.035em}nav .nav-inner button:nth-child(2){flex:0 0 58px;width:58px;height:58px;min-height:58px;padding:0;border-radius:50%;background:#080808;color:var(--blue);border:1px solid #83afff77;box-shadow:0 0 15px #83afff12}nav .nav-inner button:nth-child(2).active{background:#0b1018;box-shadow:0 0 16px #83afff20}nav .nav-inner button:nth-child(2) svg{width:23px;height:23px;flex-basis:23px}nav .nav-inner button:nth-child(2) .nav-label{display:none}
@media(min-width:700px){main{padding-top:25px}.player-stage{padding-top:39px}.player-orbit{width:148px;height:148px}}
@media(max-width:390px){main{padding:10px 14px 22px}.player-orbit{width:124px;height:124px}.orbit-core{width:62px;height:62px}.player-stage{padding-top:25px}.transport-controls{gap:0}.transport-controls .action{padding:9px 4px;font-size:9px;gap:5px}nav{padding-left:10px;padding-right:10px}nav .nav-inner{gap:7px}nav button{min-height:52px;padding:8px 6px;gap:5px}.nav-label{font-size:9px}nav .nav-inner button:nth-child(2){flex-basis:53px;width:53px;height:53px;min-height:53px}.screen-heading h2{font-size:27px}}
@media(max-width:330px){.nav-label{font-size:8px}nav .nav-inner button:nth-child(2){flex-basis:48px;width:48px;height:48px;min-height:48px}.transport-controls .action span{letter-spacing:0}.maintenance-actions{grid-template-columns:1fr}.maintenance-actions button:last-child{grid-column:auto}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{scroll-behavior:auto!important;animation-duration:.01ms!important;transition-duration:.01ms!important}}
</style>
</head>
<body>
<main>
<section class="tab active" id="now">
<div class="screen-heading player-screen-heading"><h2>NOW PLAYING</h2><span class="chip" id="deviceStatus">CONNECTING</span></div>
<div class="player-stage"><div class="player-orbit state-red" id="statusOrbit" aria-hidden="true"><div class="orbit-core"><svg viewBox="0 0 24 24"><path d="M9 5.5v13l10-6.5z"/></svg></div></div><div class="hero" id="state">CONNECTING</div><div class="format-line" id="format">WAITING FOR STATUS</div><div class="info-strip"><span>BUFFER OCCUPANCY</span><strong id="bufferText">—</strong></div></div>
<div class="volume-panel"><div class="slider-head"><h3>OUTPUT VOLUME</h3><span class="slider-value" id="volumeText">50%</span></div><div class="volume-row"><input id="volumeInput" type="range" min="0" max="100" value="50" oninput="volumePreview(this.value)" onchange="setVolume(this.value)"><button type="button" class="mute-btn" id="muteBtn" onclick="toggleMute()">Mute</button></div></div>
<div class="transport-controls"><button class="action icon-action primary" aria-label="Start" title="Start" onclick="act('/api/stream/start')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13l10-6.5z"/></svg><span>START</span></button><button class="action secondary icon-action" aria-label="Stop" title="Stop" onclick="act('/api/stream/stop')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7h10v10H7z"/></svg><span>STOP</span></button><button class="action secondary icon-action" aria-label="Reconnect" title="Reconnect" onclick="act('/api/stream/reconnect')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 8.5A7.5 7.5 0 0 0 5.6 6.1L4 8m0 0V4m0 4h4"/><path d="M5 15.5A7.5 7.5 0 0 0 18.4 17.9L20 16m0 0v4m0-4h-4"/></svg><span>RECONNECT</span></button></div>
<div class="data-section"><div class="data-heading"><span class="section-icon icon-blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 7h10M4 12h16M4 17h7"/><circle cx="17" cy="7" r="2"/><circle cx="14" cy="17" r="2"/></svg></span><h2>STREAM DETAILS</h2></div><div class="row"><span class="label">Transport</span><span class="value" id="mode">UDP Stream</span></div><div class="row"><span class="label">Listening Port</span><span class="value" id="portDisplay">50005</span></div><div class="row"><span class="label">Session</span><span class="value" id="session">—</span></div></div>
<div class="data-section"><div class="data-heading"><span class="section-icon icon-green" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M3 12h4l2.2-6 4.1 12 2.2-6H21"/></svg></span><h2>AUDIO HEALTH</h2></div><div class="health-grid"><div class="stat"><div class="k">Underruns</div><div class="v" id="underruns">0</div></div><div class="stat"><div class="k">Reconnects</div><div class="v" id="reconnects">0</div></div></div><div class="row"><span class="label">Last Error</span><span class="value" id="lastError">None</span></div></div>
</section>
<section class="tab" id="wifi"><div class="screen-heading"><h2>Wi-Fi CONNECTION</h2></div><div class="network-status"><span class="network-mark"><svg viewBox="0 0 24 24"><path d="M4 9.5a12.5 12.5 0 0 1 16 0M7.2 13a7.5 7.5 0 0 1 9.6 0M10.3 16.2a2.8 2.8 0 0 1 3.4 0M12 19h.01"/></svg></span><div><div class="network-name" id="ssid">—</div><div class="small" id="wifiStatus">—</div></div></div><div class="data-section"><div class="data-heading"><span class="section-icon icon-blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 9.5a11 11 0 0 1 14 0M8 12.5a6.5 6.5 0 0 1 8 0M10.5 15.5a2.5 2.5 0 0 1 3 0"/><circle cx="12" cy="19" r=".8" fill="currentColor" stroke="none"/></svg></span><h2>CONNECTION DETAILS</h2></div><div class="row"><span class="label">IP Address</span><span class="value" id="ip">—</span></div><div class="row"><span class="label">Hostname</span><span class="value">c3music.local</span></div><div class="row"><span class="label">Signal Strength</span><span class="value" id="rssi">—</span></div></div><div class="network-action"><button class="action secondary" aria-label="Reconnect Wi-Fi" title="Reconnect Wi-Fi" onclick="act('/api/wifi/reconnect')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 8.5A7.5 7.5 0 0 0 5.6 6.1L4 8m0 0V4m0 4h4"/><path d="M5 15.5A7.5 7.5 0 0 0 18.4 17.9L20 16m0 0v4m0-4h-4"/></svg><span>Reconnect Wi-Fi</span></button></div></section>
<section class="tab" id="settings"><div class="screen-heading"><h2>DEVICE SETTINGS</h2></div>
<div class="settings-group"><div class="data-heading"><span class="section-icon icon-amber" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 7h6m4 0h6M4 12h2m4 0h10M4 17h8m4 0h4"/><circle cx="12" cy="7" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="14" cy="17" r="2"/></svg></span><h2>STREAM CONFIGURATION</h2></div><div class="field"><label>UDP Listening Port</label><input id="udpInput" type="number" min="1" max="65535" value="50005"></div><div class="field"><label for="bufferInput">TARGET BUFFER / LATENCY</label><div class="slider-head"><span class="small">15–300 MS</span><span class="slider-value" id="bufferTargetValue" style="color:var(--amber)">120 MS</span></div><input id="bufferInput" type="range" min="15" max="300" step="5" value="120" oninput="text('bufferTargetValue',this.value+' MS');this.style.setProperty('--volume',((Number(this.value)-15)/285*100)+'%')"><div class="small">LOWER LATENCY · HIGHER BUFFER STABILITY</div></div><div class="switchrow"><span>Automatic Stream Reconnect</span><input class="switch" id="autoreconnectInput" type="checkbox"></div><div class="settings-action"><button class="action" onclick="saveCfg()">Save Settings</button></div></div>
<div class="settings-group"><div class="data-heading"><span class="section-icon icon-blue" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 7h6M9 11h6M9 15h3"/><circle cx="16" cy="17" r=".8" fill="currentColor" stroke="none"/></svg></span><h2>DEVICE INFORMATION</h2></div><div class="switchrow"><span>OLED Enabled</span><input class="switch" id="oledInput" type="checkbox" onchange="saveCfg()"></div><div class="row"><span class="label">Firmware</span><span class="value" id="version">—</span></div><div class="row"><span class="label">Free heap</span><span class="value" id="heap">—</span></div><div class="row"><span class="label">CPU temperature</span><span class="value" id="cpuTemp">—</span></div></div>
<div class="settings-group"><div class="data-heading"><span class="section-icon icon-violet" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 16V4m-4 4 4-4 4 4"/><path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5"/></svg></span><h2>FIRMWARE OTA</h2></div><p class="small">Select a .bin file. Streaming will stop during update.</p><input id="firmware" class="file" type="file" accept=".bin"><div class="settings-action"><button class="action" id="otaBtn" onclick="uploadFw()">Install Firmware</button></div><progress id="otaProg" class="hidden" value="0" max="100"></progress><div class="small" id="otaTxt"></div></div>
<div class="settings-group"><div class="data-heading"><span class="section-icon icon-red" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M14 6a5 5 0 0 0-6.5 6.5L3 17l4 4 4.5-4.5A5 5 0 0 0 18 10l-3 3-4-4 3-3Z"/></svg></span><h2>MAINTENANCE</h2></div><div class="maintenance-actions"><button class="action secondary" onclick="act('/api/system/clear-stats')">Clear Stats</button><button class="action secondary" onclick="act('/api/system/reboot')">Restart</button><button class="action danger" onclick="if(confirm('Factory Reset?'))act('/api/system/factory-reset')">Factory Reset</button></div></div></section></main>
<nav><div class="nav-inner"><button class="active" data-tab="now" aria-label="Now Playing" title="Now Playing"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6.5v11l8.5-5.5z"/></svg><span class="nav-label">Now Playing</span></button><button data-tab="wifi" aria-label="Wi-Fi" title="Wi-Fi"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9.5a12.5 12.5 0 0 1 16 0M7.2 13a7.5 7.5 0 0 1 9.6 0M10.3 16.2a2.8 2.8 0 0 1 3.4 0M12 19h.01"/></svg><span class="nav-label">Wi-Fi</span></button><button data-tab="settings" aria-label="Settings" title="Settings"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8.7a3.3 3.3 0 1 0 0 6.6 3.3 3.3 0 0 0 0-6.6Z"/><path d="M19 13.4a7.4 7.4 0 0 0 0-2.8l1.5-1.1-1.8-3.1-1.8.7a7.5 7.5 0 0 0-2.4-1.4L14.3 4h-3.6l-.3 1.7A7.5 7.5 0 0 0 8 7.1l-1.8-.7-1.8 3.1L6 10.6a7.4 7.4 0 0 0 0 2.8l-1.5 1.1 1.8 3.1 1.8-.7a7.5 7.5 0 0 0 2.4 1.4l.3 1.7h3.6l.3-1.7a7.5 7.5 0 0 0 2.4-1.4l1.8.7 1.8-3.1Z"/></svg><span class="nav-label">Settings</span></button></div></nav><div id="toast"></div>
<script>
const $=id=>document.getElementById(id);let cfgLoaded=false;let lastVolume=50;
function toast(t){const x=$('toast');x.textContent=t;x.classList.add('show');clearTimeout(window.__toastTimer);window.__toastTimer=setTimeout(()=>x.classList.remove('show'),2500)}
function text(id,v){const e=$(id);if(e)e.textContent=v}
function time(s){s=Number(s||0);const h=Math.floor(s/3600),m=Math.floor(s%3600/60),q=s%60;return[h,m,q].map(x=>String(x).padStart(2,'0')).join(':')}
function updateVolumeUI(v){const n=Math.max(0,Math.min(100,Number(v)||0));const input=$('volumeInput');if(input)input.style.setProperty('--volume',n+'%');text('volumeText',n+'%');const b=$('muteBtn');if(b){const muted=n===0;b.textContent=muted?'Unmute':'Mute';b.classList.toggle('muted',muted)}}
let volumeTimer=0;let volumeChanging=false;let volumeRequest=0;
function volumePreview(v){const n=Math.max(0,Math.min(100,Number(v)||0));if(n>0)lastVolume=n;updateVolumeUI(n);volumeChanging=true;clearTimeout(volumeTimer);volumeTimer=setTimeout(()=>sendVolume(n,false),40)}
function toggleMute(){const input=$('volumeInput');if(!input)return;const current=Number(input.value)||0;const target=current>0?0:(lastVolume>0?lastVolume:50);input.value=target;updateVolumeUI(target);setVolume(target)}
async function sendVolume(v,commit){const n=Math.max(0,Math.min(100,Number(v)||0));const requestId=++volumeRequest;try{const body=new URLSearchParams({value:String(n),commit:commit?'1':'0'});const r=await fetch('/api/volume',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});const d=await r.json();if(commit&&requestId===volumeRequest)toast(d.ok?'Volume '+n+'%':(d.error||'Volume change failed'))}catch(e){if(commit&&requestId===volumeRequest)toast('Volume change failed')}}
function setVolume(v){const n=Math.max(0,Math.min(100,Number(v)||0));if(n>0)lastVolume=n;updateVolumeUI(n);volumeChanging=false;clearTimeout(volumeTimer);sendVolume(n,true)}
function apply(d){
 text('state',d.state||'—');const statusChip=$('deviceStatus');if(statusChip){const label=String(d.state||'Unknown').toUpperCase();const stateKey=String(d.state||'').toLowerCase();const isConnecting=stateKey.includes('connect')||stateKey.includes('buffer')||stateKey.includes('boot')||stateKey.includes('updat');const isGood=stateKey==='streaming'||stateKey==='ready'||stateKey==='idle';statusChip.textContent=label;statusChip.className='chip '+(isGood?'ok':(isConnecting?'connecting':'bad'))}
 const formatActive=!!d.formatValid && (d.state==='Buffering'||d.state==='Streaming');
 text('format',formatActive?((d.sampleRate||0)+' Hz · '+(d.bits||0)+'-bit · '+(d.channels===2?'Stereo':'Mono')):'UDP Audio Waiting');
 text('mode',d.mode||'UDP Stream');text('portDisplay',d.udpPort||50005);text('session',time(d.sessionSeconds));text('underruns',d.underruns||0);text('reconnects',d.reconnects||0);text('lastError',d.lastError||'None');
 const vol=Math.max(0,Math.min(100,Number(d.volume??50)||0)),vi=$('volumeInput');if(vi&&document.activeElement!==vi){vi.value=vol;updateVolumeUI(vol)}
 text('wifiStatus',d.wifiConnected?'Connected':'Disconnected');text('ssid',d.ssid||'—');text('ip',d.ip||'—');text('rssi',d.wifiConnected?(d.rssi+' dBm'):'—');text('version',d.version||'—');text('heap',d.heap?(Math.round(d.heap/1024)+' KB'):'—');text('cpuTemp',d.cpuTemp!==undefined&&d.cpuTemp!==null?(Number(d.cpuTemp).toFixed(1)+' °C'):'—');
 const p=Number(d.bufferPercent||0);text('bufferText',p+'% · '+(d.bufferBytes||0)+' bytes');const orbit=$('statusOrbit');const state=String(d.state||'').toLowerCase();const reconnecting=state.includes('connect')||state.includes('buffer')||state.includes('boot')||state.includes('updat');const connected=!!d.wifiConnected;const good=state==='streaming'||state==='ready'||state==='idle';const bad=state==='stopped'||state==='error'||state.includes('offline')||state==='web lost';if(orbit){orbit.classList.remove('state-green','state-red','state-blue','state-amber');orbit.classList.add(reconnecting?'state-blue':(good?'state-green':(bad?'state-red':(connected?'state-green':'state-red'))))}
}
let pollBusy=false;let pollTimer=0;async function poll(){if(pollBusy)return;pollBusy=true;try{const r=await fetch('/api/status',{cache:'no-store'});if(!r.ok)throw 0;apply(await r.json());if(!cfgLoaded)await loadCfg()}catch(e){text('state','Web lost');const c=$('deviceStatus');if(c){c.textContent='OFFLINE';c.className='chip bad'}}finally{pollBusy=false}}function schedulePoll(){clearTimeout(pollTimer);pollTimer=setTimeout(()=>{poll();schedulePoll()},document.visibilityState==='visible'?1000:1500)}
async function loadCfg(){try{const d=await(await fetch('/api/config',{cache:'no-store'})).json();$('udpInput').value=d.udpPort||50005;$('bufferInput').value=Math.max(15,Math.min(300,Number(d.bufferMs??120)||120));text('bufferTargetValue',$('bufferInput').value+' MS');$('bufferInput').style.setProperty('--volume',((Number($('bufferInput').value)-15)/285*100)+'%');$('autoreconnectInput').checked=!!d.autoReconnect;$('oledInput').checked=!!d.oled;const v=Math.max(0,Math.min(100,Number(d.volume??50)||0));$('volumeInput').value=v;updateVolumeUI(v);cfgLoaded=true}catch(e){}}
async function act(url){try{const r=await fetch(url,{method:'POST'}),d=await r.json();toast(d.ok?'Done':(d.error||'Failed'));setTimeout(poll,300)}catch(e){toast('Request failed')}}
async function saveCfg(){const bufferMs=Math.max(15,Math.min(300,Number($('bufferInput').value)||120));const body=new URLSearchParams({udpPort:$('udpInput').value,bufferMs:String(bufferMs),autoReconnect:$('autoreconnectInput').checked?'1':'0',oled:$('oledInput').checked?'1':'0'});try{const r=await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});const d=await r.json();toast(d.ok?'Saved':'Save failed');cfgLoaded=false;setTimeout(poll,500)}catch(e){toast('Save failed')}}
function uploadFw(){const f=$('firmware').files[0];if(!f){toast('Choose .bin first');return}if(!confirm('Install '+f.name+'?'))return;const xhr=new XMLHttpRequest(),form=new FormData();form.append('firmware',f);$('otaProg').classList.remove('hidden');$('otaProg').value=0;$('otaTxt').textContent='Uploading…';$('otaBtn').disabled=true;xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded/e.total*100);$('otaProg').value=p;$('otaTxt').textContent='Uploading '+p+'%'}};xhr.onload=()=>{$('otaBtn').disabled=false;if(xhr.status===200){$('otaProg').value=100;$('otaTxt').textContent='Update accepted. Restarting…';toast('Firmware update successful')}else{$('otaTxt').textContent='Update failed: '+xhr.responseText;toast('OTA failed')}};xhr.onerror=()=>{$('otaBtn').disabled=false;$('otaTxt').textContent='Upload failed';toast('OTA failed')};xhr.open('POST','/api/ota');xhr.send(form)}
const navButtons=Array.from(document.querySelectorAll('nav button'));const tabs=Array.from(document.querySelectorAll('.tab'));function showTab(index,direction=0){if(index<0||index>=navButtons.length)return;navButtons.forEach(x=>x.classList.remove('active'));tabs.forEach(x=>x.classList.remove('active','from-left','from-right'));const b=navButtons[index],panel=$(b.dataset.tab);b.classList.add('active');if(panel){panel.classList.add('active');if(direction<0)panel.classList.add('from-right');else if(direction>0)panel.classList.add('from-left')}window.scrollTo({top:0,behavior:'smooth'})}navButtons.forEach((b,i)=>b.onclick=()=>showTab(i,0));
poll();schedulePoll();document.addEventListener('visibilitychange',schedulePoll);
</script></body></html>)HTML";
}

static bool otaUploadFailed=false; static String otaUploadError;
static void handleFirmwareUpload(){
  HTTPUpload &up=server.upload();
  if (up.status==UPLOAD_FILE_START){ otaUploadFailed=false; otaUploadError=""; stopRequested=true; stopUdpListener(); ringClear(); endI2S(); setReceiverState(RX_UPDATING); size_t sz=UPDATE_SIZE_UNKNOWN; if (!Update.begin(sz,U_FLASH)){ otaUploadFailed=true; otaUploadError=Update.errorString(); Serial.printf("[OTA] Begin failed: %s\n",otaUploadError.c_str()); } else Serial.printf("[OTA] Start: %s\n",up.filename.c_str()); }
  else if (up.status==UPLOAD_FILE_WRITE){ if (!otaUploadFailed){ size_t w=Update.write(up.buf,up.currentSize); if (w!=up.currentSize){ otaUploadFailed=true; otaUploadError=Update.errorString(); Serial.printf("[OTA] Write failed: %s\n",otaUploadError.c_str()); } } }
  else if (up.status==UPLOAD_FILE_END){ if (!otaUploadFailed){ if (!Update.end(true)){ otaUploadFailed=true; otaUploadError=Update.errorString(); Serial.printf("[OTA] End failed: %s\n",otaUploadError.c_str()); } else Serial.printf("[OTA] Success: %u bytes\n",up.totalSize); } }
  else if (up.status==UPLOAD_FILE_ABORTED){ otaUploadFailed=true; otaUploadError="Upload aborted"; Update.abort(); }
}

static void setupWebServer(){
  server.on("/",HTTP_GET,[]{ server.sendHeader("Cache-Control","public,max-age=300,must-revalidate"); server.sendHeader("Vary","Accept-Encoding"); server.send(200,"text/html; charset=utf-8",htmlPage()); });
  server.on("/api/status",HTTP_GET,[]{ sendJson(200,makeStatusJson()); });
  server.on("/api/config",HTTP_GET,[]{ sendJson(200,makeConfigJson()); });
  server.on("/api/config",HTTP_POST,[]{
    if (!requirePost()) return;
    uint32_t up=server.arg("udpPort").toInt();
    if (up<1||up>65535){ sendJson(400,"{\"ok\":false,\"error\":\"Invalid port\"}"); return; }
    settings.udpPort=(uint16_t)up;
    uint32_t bm=server.arg("bufferMs").toInt(); if (bm<80||bm>700){ sendJson(400,"{\"ok\":false,\"error\":\"Invalid buffer\"}"); return; }
    settings.autoReconnect=(server.arg("autoReconnect")=="1"); settings.oledEnabled=(server.arg("oled")=="1"); settings.targetBufferMs=(uint16_t)bm;
    if (oledAvailable) {
      if (settings.oledEnabled) {
        oledStreamingDisabled = false;
        oled.setPowerSave(0);
        oled.clearBuffer();
        oled.sendBuffer();
      } else {
        oledStreamingDisabled = false;
        oled.setPowerSave(1);
      }
    }
    saveSettings(); reconnectStreaming(); sendJson(200,"{\"ok\":true}");
  });
  server.on("/api/volume",HTTP_POST,[]{
    if(!requirePost()) return;
    int v=server.arg("value").toInt();
    if(v<0||v>100){ sendJson(400,"{\"ok\":false,\"error\":\"Invalid volume\"}"); return; }
    settings.volumePercent=(uint8_t)v;
    if(server.arg("commit")=="1") preferences.putUChar("volume", settings.volumePercent);
    sendJson(200,"{\"ok\":true,\"volume\":"+String(settings.volumePercent)+"}");
  });
  server.on("/api/stream/start",HTTP_POST,[]{ if(!requirePost())return; startStreaming(); sendJson(200,"{\"ok\":true}"); });
  server.on("/api/stream/stop",HTTP_POST,[]{ if(!requirePost())return; stopStreaming(); sendJson(200,"{\"ok\":true}"); });
  server.on("/api/stream/reconnect",HTTP_POST,[]{ if(!requirePost())return; reconnectStreaming(); sendJson(200,"{\"ok\":true}"); });
  server.on("/api/wifi/reconnect",HTTP_POST,[]{ if(!requirePost())return; sendJson(200,"{\"ok\":true,\"message\":\"Reconnecting Wi-Fi\"}"); delay(250); stopUdpListener(); if(mdnsStarted){ MDNS.end(); mdnsStarted=false; } WiFi.disconnect(false,false); lastWifiAttemptMs=0; connectWifiIfNeeded(); });
  server.on("/api/system/clear-stats",HTTP_POST,[]{ if(!requirePost())return; stats=RuntimeStats(); sendJson(200,"{\"ok\":true}"); });
  server.on("/api/system/reboot",HTTP_POST,[]{ if(!requirePost())return; sendJson(200,"{\"ok\":true,\"message\":\"Restarting\"}"); delay(250); ESP.restart(); });
  server.on("/api/system/factory-reset",HTTP_POST,[]{ if(!requirePost())return; resetSettings(); sendJson(200,"{\"ok\":true,\"message\":\"Reset; restarting\"}"); delay(250); ESP.restart(); });
  server.on("/api/ota",HTTP_POST,[]{ if (otaUploadFailed){ String b="{\"ok\":false,\"error\":\""+jsonEscape(otaUploadError)+"\"}"; sendJson(500,b); stopRequested=false; settings.streamEnabled=true; setReceiverState(RX_IDLE); } else { sendJson(200,"{\"ok\":true,\"message\":\"Firmware written; restarting\"}"); delay(700); ESP.restart(); } }, handleFirmwareUpload);
  server.onNotFound([](){ sendJson(404,"{\"ok\":false,\"error\":\"Not found\"}"); });
  server.begin(); Serial.println("[WEB] HTTP server started");
}

void setup(){
  Serial.begin(115200); delay(400);
  Serial.println(); Serial.println("============================================================");
  Serial.printf("%s v%s\n",DEVICE_NAME,FIRMWARE_VERSION);
  Serial.println("High-Performance UDP Audio Stream Receiver (Port 50005)");
  Serial.println("============================================================");
  loadSettings();
  audioDataSemaphore=xSemaphoreCreateBinary();
  i2sMux=xSemaphoreCreateMutex();
  udpMux=xSemaphoreCreateMutex();
  displayBegin(); setReceiverState(RX_BOOTING);
  WiFi.persistent(false); WiFi.mode(WIFI_STA); WiFi.setSleep(false); WiFi.setAutoReconnect(true);
  setupWebServer();
  BaseType_t ok1=xTaskCreate(playbackTask,"i2sPlayback",4096,nullptr,3,&playbackTaskHandle);
  BaseType_t ok2=xTaskCreate(streamTask,"udpStream",6144,nullptr,2,&streamTaskHandle);
  if (ok1!=pdPASS||ok2!=pdPASS) setReceiverState(RX_ERROR,"Task creation failed");
  connectWifiIfNeeded(); displayUpdate();
}

void loop(){
  server.handleClient();
  if (WiFi.status()!=WL_CONNECTED){
    mdnsStarted=false;
    if (receiverState!=RX_WIFI_OFFLINE&&receiverState!=RX_WIFI_CONNECTING&&receiverState!=RX_UPDATING&&settings.streamEnabled){
      Serial.printf("[WIFI] Disconnected (status=%d)\n",(int)WiFi.status());
      setReceiverState(RX_WIFI_OFFLINE);
    }
    connectWifiIfNeeded();
  }
  else beginMdnsIfNeeded();
  displayUpdate();
  if (millis()-lastStatusRefreshMs>=STATUS_REFRESH_MS){
    lastStatusRefreshMs=millis();
    uint32_t target=targetPrebufferBytes();
    if (receiverState==RX_BUFFERING&&bufferStarted&&ringSize()>=(target/2)) setReceiverState(RX_STREAMING);
  }
  delay(2);
}
