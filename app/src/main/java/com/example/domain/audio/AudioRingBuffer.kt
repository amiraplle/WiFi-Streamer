package com.example.domain.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-performance, zero-allocation circular ring buffer for raw audio PCM frames.
 * Decouples the real-time AudioRecord thread from the TCP/HTTP network transmission thread,
 * ensuring network latency spikes never block audio capture or cause sample drops.
 */
class AudioRingBuffer(val capacity: Int = 128 * 1024) {

    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var availableBytes = 0
    private val lock = ReentrantLock()

    /**
     * Writes [length] bytes from [src] into the ring buffer.
     * If capacity is exceeded, drops the oldest unread bytes to keep streaming strictly real-time.
     * Guaranteed to execute in microseconds without blocking for network I/O.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0

        lock.withLock {
            val toWrite = length.coerceAtMost(capacity)

            // If incoming data exceeds remaining capacity, discard oldest data
            val freeSpace = capacity - availableBytes
            if (toWrite > freeSpace) {
                val overflow = toWrite - freeSpace
                readPos = (readPos + overflow) % capacity
                availableBytes -= overflow
            }

            // Write chunk in 1 or 2 segments (handling wrap-around)
            val firstChunk = (capacity - writePos).coerceAtMost(toWrite)
            System.arraycopy(src, offset, buffer, writePos, firstChunk)

            val secondChunk = toWrite - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(src, offset + firstChunk, buffer, 0, secondChunk)
                writePos = secondChunk
            } else {
                writePos = (writePos + firstChunk) % capacity
            }

            availableBytes += toWrite
            return toWrite
        }
    }

    /**
     * Reads up to [maxLength] bytes into [dest] starting at [destOffset].
     * Returns the actual number of bytes read (0 if empty).
     */
    fun read(dest: ByteArray, destOffset: Int, maxLength: Int): Int {
        if (maxLength <= 0) return 0

        lock.withLock {
            if (availableBytes == 0) return 0

            val toRead = maxLength.coerceAtMost(availableBytes)

            // Read in 1 or 2 segments (handling wrap-around)
            val firstChunk = (capacity - readPos).coerceAtMost(toRead)
            System.arraycopy(buffer, readPos, dest, destOffset, firstChunk)

            val secondChunk = toRead - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, dest, destOffset + firstChunk, secondChunk)
                readPos = secondChunk
            } else {
                readPos = (readPos + firstChunk) % capacity
            }

            availableBytes -= toRead
            return toRead
        }
    }

    /**
     * Current number of bytes buffered and awaiting transmission.
     */
    val available: Int
        get() = lock.withLock { availableBytes }

    /**
     * Clears all buffered audio data.
     */
    fun clear() {
        lock.withLock {
            writePos = 0
            readPos = 0
            availableBytes = 0
        }
    }
}
