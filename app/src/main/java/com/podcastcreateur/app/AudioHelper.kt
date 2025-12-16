package com.podcastcreateur.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

data class AudioMetadata(
    val sampleRate: Int,
    val totalSamples: Long,
    val channelCount: Int,
    val durationSeconds: Long
)

object AudioHelper {
    // Nombre de points max pour la waveform pour éviter de saturer la RAM
    private const val WAVEFORM_SAMPLES = 8000 

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                format = f
                break
            }
        }

        if (format == null) {
            extractor.release()
            return null
        }

        val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
        val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (e: Exception) { 0L }
        val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
        val totalSamples = if (durationUs > 0) ((durationUs / 1_000_000.0) * sampleRate).toLong() else 0L
        val durationSeconds = durationUs / 1_000_000

        extractor.release()
        return AudioMetadata(sampleRate, totalSamples, channelCount, durationSeconds)
    }

    // Chargement allégé pour la visualisation
    fun loadWaveformPreview(input: File): AudioContent {
        val metadata = getAudioMetadata(input) ?: return AudioContent(ShortArray(0), 44100)
        if (metadata.totalSamples == 0L) return AudioContent(ShortArray(0), metadata.sampleRate)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            return AudioContent(ShortArray(0), metadata.sampleRate)
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            extractor.release()
            return AudioContent(ShortArray(0), metadata.sampleRate)
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return AudioContent(ShortArray(0), metadata.sampleRate)
        
        // Optimisation : Si le fichier est très long, on saute des bouts pour aller plus vite
        // Pour une vraie waveform précise sur gros fichier, FFmpeg serait mieux, 
        // mais MediaCodec est OK pour la visualisation si on ne stocke pas tout.
        
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val previewData = ArrayList<Short>()
        
        // Facteur de saut pour réduire la RAM consommée
        // On vise environ WAVEFORM_SAMPLES points au total
        val totalEstimatedBytes = metadata.durationSeconds * metadata.sampleRate * 2 // 16bit
        val skipRatio = (totalEstimatedBytes / (WAVEFORM_SAMPLES * 2)).toInt().coerceAtLeast(1)
        var outputCount = 0

        try {
            var isEOS = false
            while (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(5000)
                if (inIndex >= 0) {
                    val inBuffer = decoder.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                while (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        // Lecture des données
                        // Pour optimiser, on ne lit pas tout si skipRatio est grand
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        
                        // Downsampling simple
                        val shorts = ShortArray(chunk.size / 2)
                        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        
                        // On prend le max (peak) sur la plage de saut pour garder l'amplitude visuelle
                        if (outputCount % skipRatio == 0) {
                             // Algorithme de pic simple : prendre une valeur arbitraire ou la moyenne
                             if (shorts.isNotEmpty()) previewData.add(shorts[0])
                        }
                        outputCount++
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                
                // Sécurité mémoire
                if (previewData.size > WAVEFORM_SAMPLES * 2) break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { decoder.stop(); decoder.release() } catch(e: Exception) {}
            try { extractor.release() } catch(e: Exception) {}
        }

        val result = ShortArray(previewData.size)
        for (i in previewData.indices) result[i] = previewData[i]
        
        return AudioContent(result, metadata.sampleRate)
    }
}
