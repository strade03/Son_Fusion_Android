package com.podcastcreateur.app

import android.media.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.media.MediaMetadataRetriever

data class AudioMetadata(val sampleRate: Int, val channelCount: Int, val duration: Long, val totalSamples: Long)
data class AudioContent(val data: ShortArray, val sampleRate: Int, val channelCount: Int)

object AudioHelper {
    private const val BIT_RATE = 128000
    const val POINTS_PER_SECOND = 50 

    private fun getWaveformCacheFile(audioFile: File): File {
        return File(audioFile.parent, audioFile.name + ".peak")
    }

    /**
     * CHARGEMENT ULTRA-RAPIDE (STYLE AUDACITY)
     */
    fun loadWaveform(input: File, onUpdate: (FloatArray) -> Unit) {
        val cacheFile = getWaveformCacheFile(input)
        
        // 1. Si le cache existe, on charge l'onde en 10ms (même pour 1h de son)
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                onUpdate(floats)
                return 
            } catch (e: Exception) { cacheFile.delete() }
        }

        // 2. Sinon, on décode à haute vitesse
        val extractor = MediaExtractor()
        val allPoints = mutableListOf<Float>()
        try {
            extractor.setDataSource(input.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var currentSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var currentChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var samplesPerPoint = (currentSampleRate * currentChannels) / POINTS_PER_SECOND
            
            val duration = getAudioMetadata(input)?.duration ?: 0
            val step = if (duration > 600_000) 10 else 1 // Accélération : on saute des données si > 10min

            var maxPeak = 0f
            var sampleCount = 0
            var isEOS = false
            val tempChunk = FloatArray(1000)
            var chunkIdx = 0

            while (true) {
                if (!isEOS) {
                    val inIdx = decoder.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 5000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        val bulkSize = shorts.remaining()
                        val bulkArray = ShortArray(bulkSize)
                        shorts.get(bulkArray)

                        for (i in 0 until bulkSize step step) {
                            val s = abs(bulkArray[i].toFloat() / 32768f)
                            if (s > maxPeak) maxPeak = s
                            sampleCount += step
                            
                            if (sampleCount >= samplesPerPoint) {
                                val p = maxPeak.coerceAtMost(1.0f)
                                tempChunk[chunkIdx++] = p
                                allPoints.add(p)
                                maxPeak = 0f
                                sampleCount = 0
                                
                                if (chunkIdx >= tempChunk.size) {
                                    onUpdate(tempChunk.copyOf())
                                    chunkIdx = 0
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (isEOS && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
            if (chunkIdx > 0) onUpdate(tempChunk.copyOfRange(0, chunkIdx))
            
            // SAUVEGARDE DU CACHE
            val cacheBuf = ByteBuffer.allocate(allPoints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            allPoints.forEach { cacheBuf.putFloat(it) }
            cacheFile.writeBytes(cacheBuf.array())

            decoder.stop(); decoder.release(); extractor.release()
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * DECODAGE MONO POUR ECONOMISER LA RAM (Indispensable pour > 30 min)
     */
    fun decodeToPCM(input: File): AudioContent {
        val meta = getAudioMetadata(input) ?: return AudioContent(ShortArray(0), 44100, 1)
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                format = f; extractor.selectTrack(i); break
            }
        }
        if (format == null) return AudioContent(ShortArray(0), 44100, 1)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        // On prévoit le tableau en MONO (données divisées par channelCount si stéréo)
        val totalExpectedSamples = ((meta.duration * meta.sampleRate) / 1000).toInt()
        val finalPcm = ShortArray(totalExpectedSamples + 1000)
        var writeOffset = 0

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        val channels = meta.channelCount

        while (true) {
            if (!isEOS) {
                val inIdx = codec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val sz = extractor.readSampleData(buf!!, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 5000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null) {
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = outBuf.asShortBuffer()
                    while (shorts.hasRemaining()) {
                        if (channels == 2) {
                            val left = shorts.get().toInt()
                            val right = if (shorts.hasRemaining()) shorts.get().toInt() else left
                            val mono = ((left + right) / 2).toShort()
                            if (writeOffset < finalPcm.size) finalPcm[writeOffset++] = mono
                        } else {
                            if (writeOffset < finalPcm.size) finalPcm[writeOffset++] = shorts.get()
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (isEOS && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
        }
        codec.stop(); codec.release(); extractor.release()
        return AudioContent(finalPcm.copyOfRange(0, writeOffset), meta.sampleRate, 1)
    }

    fun generateWaveformFromPCM(content: AudioContent): FloatArray {
        val samplesPerPoint = content.sampleRate / POINTS_PER_SECOND
        if (samplesPerPoint <= 0) return floatArrayOf()
        val pointCount = content.data.size / samplesPerPoint
        val waveform = FloatArray(pointCount)
        for (i in 0 until pointCount) {
            var max = 0f
            val start = i * samplesPerPoint
            for (j in 0 until samplesPerPoint) {
                val idx = start + j
                if (idx >= content.data.size) break
                val s = abs(content.data[idx].toFloat() / 32768f)
                if (s > max) max = s
            }
            waveform[i] = max
        }
        return waveform
    }

    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int, channels: Int): Boolean {
        // Supprimer le cache de l'onde car le fichier change
        val cache = File(outputFile.parent, outputFile.name + ".peak")
        if (cache.exists()) cache.delete()

        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64000)
            
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            
            mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var tr = -1; var st = false; val info = MediaCodec.BufferInfo()
            
            // Conversion par blocs pour la RAM
            val bb = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcmData)
            val all = bb.array(); var off = 0; var eos = false
            
            while (true) {
                if (!eos) {
                    val i = enc.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val rem = all.size - off
                        val len = minOf(rem, 16384)
                        if (len > 0) {
                            enc.getInputBuffer(i)?.put(all, off, len)
                            off += len
                            enc.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, 0)
                        } else {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    tr = mux.addTrack(enc.outputFormat); mux.start(); st = true
                } else if (o >= 0) {
                    if (info.size != 0 && st) {
                        val d = enc.getOutputBuffer(o)
                        mux.writeSampleData(tr, d!!, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) { e.printStackTrace(); return false } 
        finally { try { enc?.release(); mux?.release() } catch (e: Exception) {} }
    }

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        val duration = getDurationAccurate(input)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    return AudioMetadata(sr, ch, duration, (duration * sr) / 1000)
                }
            }
        } catch (e: Exception) {} finally { extractor.release() }
        return null
    }
}