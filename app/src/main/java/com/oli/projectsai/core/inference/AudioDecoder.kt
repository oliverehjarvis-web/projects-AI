package com.oli.projectsai.core.inference

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes an arbitrary audio file (M4A/AAC, MP3, WAV) into the canonical pipeline format
 * (16 kHz mono PCM-16 little-endian) that Gemma 4's audio encoder expects, and slices the
 * result into ≤30 s chunks for long-form transcription.
 *
 * One full hour of mono 16 kHz PCM-16 is ~115 MB, so the entire decoded buffer lives in
 * memory; that's well within the headroom we have on the OnePlus 13 but is the reason this
 * isn't a streaming pipeline.
 */
class AudioDecodeError(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class AudioDecoder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    /**
     * Decodes [uri] into 16 kHz mono PCM-16 LE bytes. Throws [AudioDecodeError] for
     * unsupported codecs / corrupt streams; callers should surface the message verbatim.
     */
    suspend fun decodeToPcm16Mono(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (t: Throwable) {
                throw AudioDecodeError("Could not open audio file: ${t.message}", t)
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeError("File contains no audio track")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sourceMime = format.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeError("Audio track has no MIME type")
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val rawPcm: ByteArray = if (sourceMime == "audio/raw") {
                // WAVs surface as raw PCM with no decoder — read frames directly.
                readRawPcm(extractor)
            } else {
                decodeViaCodec(extractor, format, sourceMime)
            }

            val mono = if (sourceChannels > 1) downmixToMono(rawPcm, sourceChannels) else rawPcm
            val resampled = if (sourceSampleRate != TARGET_SAMPLE_RATE)
                resampleLinear(mono, sourceSampleRate, TARGET_SAMPLE_RATE)
            else mono

            if (resampled.isEmpty()) throw AudioDecodeError("Decoded audio was empty")
            resampled
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Splits mono PCM-16 LE bytes into chunks of [chunkSeconds] with [overlapSeconds] of
     * preceding audio prepended to each non-first chunk. The overlap helps the model see a
     * word that began just before the chunk boundary; the transcript stitcher trims the
     * resulting duplicate text.
     */
    fun chunkPcm16Mono(
        pcm: ByteArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        chunkSeconds: Int = 28,
        overlapSeconds: Int = 1
    ): List<ByteArray> {
        require(chunkSeconds in 1..TRANSCRIPTION_MAX_SECONDS)
        require(overlapSeconds in 0 until chunkSeconds)
        if (pcm.isEmpty()) return emptyList()

        val bytesPerSecond = sampleRate * 2
        val chunkBytes = chunkSeconds * bytesPerSecond
        val advance = (chunkSeconds - overlapSeconds) * bytesPerSecond

        val out = mutableListOf<ByteArray>()
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + chunkBytes, pcm.size)
            out += pcm.copyOfRange(start, end)
            if (end == pcm.size) break
            start += advance
        }
        return out
    }

    private fun readRawPcm(extractor: MediaExtractor): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(64 * 1024)
        while (true) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val arr = ByteArray(size)
            buf.position(0)
            buf.get(arr, 0, size)
            out.write(arr)
            extractor.advance()
        }
        return out.toByteArray()
    }

    private fun decodeViaCodec(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String
    ): ByteArray {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            throw AudioDecodeError("No decoder available for $mime", t)
        }
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                            ?: throw AudioDecodeError("Codec returned a null input buffer")
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                            ?: throw AudioDecodeError("Codec returned a null output buffer")
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val arr = ByteArray(info.size)
                        outBuf.get(arr)
                        out.write(arr)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        } catch (t: Throwable) {
            throw AudioDecodeError("Decoder error: ${t.message}", t)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return out.toByteArray()
    }

    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = src.remaining() / channels
        val dst = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sum = 0
            repeat(channels) { sum += src.get().toInt() }
            dst.putShort((sum / channels).toShort())
        }
        return dst.array()
    }

    private fun resampleLinear(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return pcm
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val srcSamples = src.remaining()
        val dstSamples = ((srcSamples.toLong() * dstRate) / srcRate).toInt()
        val dst = ByteBuffer.allocate(dstSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceAtMost(srcSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(srcSamples - 1)
            val frac = srcPos - i0
            val s0 = src.get(i0).toInt()
            val s1 = src.get(i1).toInt()
            val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            dst.putShort(sample.toShort())
        }
        return dst.array()
    }
}
