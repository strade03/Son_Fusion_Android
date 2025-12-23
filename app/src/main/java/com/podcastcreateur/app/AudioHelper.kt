package com.podcastcreateur.app

import android.media.*
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, 
    val totalSamples: Long
)

object AudioHelper {
    private const val TAG = "AudioHelper"

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) {
            Log.e(TAG, "getAudioMetadata: File does not exist: ${input.absolutePath}")
            return null
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return null
            val format = extractor.getTrackFormat(trackIdx)
            
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else Constants.SAMPLE_RATE
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else Constants.CHANNELS
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            val totalSamples = (duration * sampleRate * channels) / 1000
            
            AudioMetadata(sampleRate, channels, duration, totalSamples)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading metadata", e)
            null
        } finally {
            extractor.release()
        }
    }

    fun calculatePeak(inputFile: File): Float {
        var maxPeakFound = 0f
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return 0f
            extractor.selectTrack(trackIdx)
            
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0f
            
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            
            while (!isEOS) {
                val inIdx = decoder.dequeueInputBuffer(Constants.TIMEOUT_US)
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
                
                var outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        var i = 0
                        while (i < shorts.remaining()) {
                            val sample = abs(shorts.get(i).toFloat() / 32768f)
                            if (sample > maxPeakFound) maxPeakFound = sample
                            i += 10 
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                }
            }
            return maxPeakFound
        } catch (e: Exception) { 
            Log.e(TAG, "Error calculating peak", e)
            return 0f 
        } finally {
            try { decoder?.stop() } catch(e:Exception){ Log.w(TAG, "Decoder stop failed", e) }
            try { decoder?.release() } catch(e:Exception){ Log.w(TAG, "Decoder release failed", e) }
            extractor.release()
        }
    }

    fun saveWithCutsAndGain(input: File, output: File, cutRanges: List<Pair<Long, Long>>, gain: Float): Boolean {
        val sortedCuts = cutRanges.sortedBy { it.first }
        val applyGain = gain > 1.01f || gain < 0.99f
        
        return runTranscode(input, output) { sampleIndex, sampleValue ->
            var shouldKeep = true
            for (range in sortedCuts) {
                if (sampleIndex >= range.first && sampleIndex < range.second) {
                    shouldKeep = false
                    break
                }
                if (sampleIndex < range.first) break 
            }
            
            if (!shouldKeep) {
                null
            } else {
                if (applyGain) {
                    (sampleValue * gain).toInt().coerceIn(-32768, 32767).toShort()
                } else {
                    sampleValue
                }
            }
        }
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        var sampleRate = Constants.SAMPLE_RATE
        var channels = Constants.CHANNELS
        
        val scanEx = MediaExtractor()
        try {
            scanEx.setDataSource(inputs[0].absolutePath)
            val idx = selectAudioTrack(scanEx)
            if (idx >= 0) {
                val f = scanEx.getTrackFormat(idx)
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        } catch(e:Exception){
            Log.e(TAG, "Error scanning first file", e)
        } finally { 
            scanEx.release() 
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        // FIX: Déclaration déplacée ici, hors du try
        var muxerStarted = false 
        
        try {
            val format = MediaFormat.createAudioFormat(Constants.MIME_TYPE_AUDIO, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, Constants.BIT_RATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            encoder = MediaCodec.createEncoderByType(Constants.MIME_TYPE_AUDIO)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            
            val encBufferInfo = MediaCodec.BufferInfo()

            for (input in inputs) {
                if (!input.exists()) continue
                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null
                
                try {
                    extractor.setDataSource(input.absolutePath)
                    val trackIdx = selectAudioTrack(extractor)
                    if (trackIdx < 0) continue
                    
                    extractor.selectTrack(trackIdx)
                    val decFormat = extractor.getTrackFormat(trackIdx)
                    val mime = decFormat.getString(MediaFormat.KEY_MIME) ?: continue
                    
                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(decFormat, null, null, 0)
                    decoder.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var isEOS = false
                    
                    while (!isEOS) {
                        val inIdx = decoder.dequeueInputBuffer(Constants.TIMEOUT_US)
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
                        
                        var outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                        while (outIdx >= 0) {
                            val outBuf = decoder.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.get(chunk)
                                feedEncoder(encoder, chunk)
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                        }
                        
                        var encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
                        while (encOutIdx >= 0 || encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            } else if (encOutIdx >= 0) {
                                val encodedData = encoder.getOutputBuffer(encOutIdx)
                                if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                                if (encBufferInfo.size != 0 && muxerStarted) {
                                    encodedData?.position(encBufferInfo.offset)
                                    encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                                    muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                                }
                                encoder.releaseOutputBuffer(encOutIdx, false)
                            }
                            encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing file: ${input.name}", e)
                } finally {
                    try { decoder?.stop(); decoder?.release() } catch(e:Exception){}
                    extractor.release()
                }
            }
            
            val inIdx = encoder.dequeueInputBuffer(Constants.TIMEOUT_US)
            if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            
            var encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
            while (encOutIdx >= 0) {
                if (encOutIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(encOutIdx)
                    if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                    if (encBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(encBufferInfo.offset)
                        encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                }
                encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            return false
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (e:Exception){}
            // FIX: Ici muxerStarted est maintenant visible
            try { if (muxer != null && muxerStarted) muxer.stop(); muxer?.release() } catch (e:Exception){}
        }
    }

    private fun runTranscode(input: File, output: File, process: (Long, Short) -> Short?): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        // FIX: Déclaration hors du try pour visibilité dans finally
        var muxerStarted = false 
        
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return false
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else Constants.SAMPLE_RATE
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else Constants.CHANNELS

            val encFormat = MediaFormat.createAudioFormat(Constants.MIME_TYPE_AUDIO, sampleRate, channels)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, Constants.BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            encoder = MediaCodec.createEncoderByType(Constants.MIME_TYPE_AUDIO)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val encBufferInfo = MediaCodec.BufferInfo()
            val bufferInfo = MediaCodec.BufferInfo()
            var muxerTrackIndex = -1
            var totalSamplesProcessed = 0L
            var isInputEOS = false
            var isDecodedEOS = false
            
            while (!isDecodedEOS) {
                if (!isInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(Constants.TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                var outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val chunkBytes = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(chunkBytes)
                        val shortBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val processedStream = java.io.ByteArrayOutputStream()
                        val tempShortBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        
                        while (shortBuffer.hasRemaining()) {
                            val sample = shortBuffer.get()
                            val newSample = process(totalSamplesProcessed, sample)
                            totalSamplesProcessed++
                            if (newSample != null) {
                                tempShortBuf.clear()
                                tempShortBuf.putShort(newSample)
                                processedStream.write(tempShortBuf.array())
                            }
                        }
                        val bytesToWrite = processedStream.toByteArray()
                        if (bytesToWrite.isNotEmpty()) feedEncoder(encoder, bytesToWrite)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isDecodedEOS = true
                        val inIdx = encoder.dequeueInputBuffer(Constants.TIMEOUT_US)
                        if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    outIdx = decoder.dequeueOutputBuffer(bufferInfo, Constants.TIMEOUT_US)
                }
                
                var encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
                while (encOutIdx >= 0 || encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (encOutIdx >= 0) {
                        val encodedData = encoder.getOutputBuffer(encOutIdx)
                        if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                        if (encBufferInfo.size != 0 && muxerStarted) {
                            encodedData?.position(encBufferInfo.offset)
                            encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, Constants.TIMEOUT_US)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed", e)
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch(e:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            // FIX: muxerStarted est maintenant visible
            try { if (muxer != null && muxerStarted) muxer.stop(); muxer?.release() } catch(e:Exception){}
            try { extractor?.release() } catch(e:Exception){}
        }
    }

    private fun feedEncoder(encoder: MediaCodec, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val inIdx = encoder.dequeueInputBuffer(Constants.TIMEOUT_US)
            if (inIdx >= 0) {
                val buf = encoder.getInputBuffer(inIdx)
                val remaining = data.size - offset
                val toWrite = if (remaining > buf!!.capacity()) buf.capacity() else remaining
                buf.clear()
                buf.put(data, offset, toWrite)
                encoder.queueInputBuffer(inIdx, 0, toWrite, System.nanoTime()/1000, 0)
                offset += toWrite
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }
}