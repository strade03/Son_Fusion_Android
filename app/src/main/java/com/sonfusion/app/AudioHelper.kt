package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

object AudioHelper {
    // On standardise tout sur 44100Hz pour éviter les conflits de vitesse
    const val TARGET_SAMPLE_RATE = 44100 
    private const val BIT_RATE = 128000

    /**
     * Décode n'importe quel fichier audio et le convertit systématiquement en 44100Hz PCM
     */
    fun decodeToPCM(input: File): AudioContent {
        if (!input.exists()) return AudioContent(ShortArray(0), TARGET_SAMPLE_RATE)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            return AudioContent(ShortArray(0), TARGET_SAMPLE_RATE)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex < 0 || format == null) return AudioContent(ShortArray(0), TARGET_SAMPLE_RATE)

        // Récupération du sample rate d'origine du fichier
        val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            TARGET_SAMPLE_RATE
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return AudioContent(ShortArray(0), TARGET_SAMPLE_RATE)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmData = java.io.ByteArrayOutputStream()
        var isEOS = false

        try {
            while (true) {
                if (!isEOS) {
                    val inIndex = decoder.dequeueInputBuffer(1000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inBuffer!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        pcmData.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEOS) break 
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { decoder.stop(); decoder.release() } catch(e:Exception){}
            try { extractor.release() } catch(e:Exception){}
        }

        val bytes = pcmData.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        
        // --- CORRECTION MAJEURE : RÉ-ÉCHANTILLONNAGE ---
        // Si le fichier n'est pas en 44100Hz, on le convertit.
        return if (sourceSampleRate != TARGET_SAMPLE_RATE) {
            val resampled = resample(shorts, sourceSampleRate, TARGET_SAMPLE_RATE)
            AudioContent(resampled, TARGET_SAMPLE_RATE)
        } else {
            AudioContent(shorts, TARGET_SAMPLE_RATE)
        }
    }

    /**
     * Algorithme simple d'interpolation linéaire pour changer la fréquence
     */
    private fun resample(input: ShortArray, currentRate: Int, targetRate: Int): ShortArray {
        val ratio = currentRate.toDouble() / targetRate.toDouble()
        val outputSize = (input.size / ratio).toInt()
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val position = i * ratio
            val index = position.toInt()
            if (index >= input.size - 1) {
                output[i] = input[input.size - 1]
            } else {
                val fraction = position - index
                val val1 = input[index]
                val val2 = input[index + 1]
                // Interpolation
                output[i] = (val1 + fraction * (val2 - val1)).toInt().toShort()
            }
        }
        return output
    }

    /**
     * Encode en AAC 44100Hz mono
     */
    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int = TARGET_SAMPLE_RATE): Boolean {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false

            val outputBufferInfo = MediaCodec.BufferInfo()
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) byteBuffer.putShort(s)
            byteBuffer.position(0)
            val fullPcmBytes = byteBuffer.array()

            var inputOffset = 0
            var isEOS = false

            while (true) {
                if (!isEOS) {
                    val inIndex = encoder.dequeueInputBuffer(1000)
                    if (inIndex >= 0) {
                        val inBuffer = encoder.getInputBuffer(inIndex)
                        inBuffer?.clear()
                        val remaining = fullPcmBytes.size - inputOffset
                        val toRead = if (remaining > 4096) 4096 else remaining

                        if (toRead > 0) {
                            inBuffer?.put(fullPcmBytes, inputOffset, toRead)
                            inputOffset += toRead
                            val pts = (inputOffset.toLong() * 1000000L / (sampleRate * 2)).toLong()
                            encoder.queueInputBuffer(inIndex, 0, toRead, pts, 0)
                        } else {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(outputBufferInfo, 1000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw RuntimeException("Format changed twice")
                    val newFormat = encoder.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) outputBufferInfo.size = 0
                    if (outputBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(outputBufferInfo.offset)
                        encodedData?.limit(outputBufferInfo.offset + outputBufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, encodedData!!, outputBufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            try { muxer?.stop(); muxer?.release() } catch(e:Exception){}
        }
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        try {
            val allSamples = ArrayList<Short>()
            
            // Grâce au decodeToPCM corrigé, toutes les données arrivent en 44100Hz.
            // On peut donc concaténer sans se soucier du pitch.
            for (file in inputs) {
                val content = decodeToPCM(file)
                for (s in content.data) allSamples.add(s)
            }
            
            val finalData = ShortArray(allSamples.size)
            for (i in allSamples.indices) finalData[i] = allSamples[i]
            
            return savePCMToAAC(finalData, output, TARGET_SAMPLE_RATE)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}