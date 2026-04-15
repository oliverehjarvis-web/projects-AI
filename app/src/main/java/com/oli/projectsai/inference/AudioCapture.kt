package com.oli.projectsai.inference

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * 16 kHz mono PCM 16-bit microphone capture. Produces the byte format Gemma's audio encoder expects.
 * Single-shot: one call to [start], one call to [stop] returning the full buffer.
 * Caps at [TRANSCRIPTION_MAX_SECONDS] of audio; auto-stops on cap hit.
 */
@Singleton
class AudioCapture @Inject constructor() {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val TAG = "AudioCapture"
        val MAX_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * TRANSCRIPTION_MAX_SECONDS
    }

    private val recording = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var buffer = ByteArrayOutputStream()
    private var thread: Thread? = null

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording.get()) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 4)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }

        buffer = ByteArrayOutputStream(MAX_BYTES / 8)
        record = ar
        recording.set(true)
        ar.startRecording()

        thread = thread(name = "AudioCapture", isDaemon = true) {
            val tmp = ByteArray(minBuf)
            try {
                while (recording.get()) {
                    val read = ar.read(tmp, 0, tmp.size)
                    if (read > 0) {
                        // Return true if the cap was hit so we can break outside the synchronized block.
                        val capped = synchronized(buffer) {
                            val remaining = MAX_BYTES - buffer.size()
                            if (remaining <= 0) {
                                true
                            } else {
                                buffer.write(tmp, 0, minOf(read, remaining))
                                false
                            }
                        }
                        if (capped) {
                            recording.set(false)
                            break
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Capture thread error", t)
            }
        }
    }

    /** Stops the capture and returns the PCM bytes collected so far. Safe to call even if not started. */
    fun stop(): ByteArray {
        if (!recording.getAndSet(false)) return ByteArray(0)
        thread?.join(500)
        thread = null
        record?.run {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        record = null
        return synchronized(buffer) { buffer.toByteArray() }
    }

    fun cancel() {
        stop()
    }
}
