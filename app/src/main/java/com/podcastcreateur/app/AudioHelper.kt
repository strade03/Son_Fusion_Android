package com.podcastcreateur.app

import android.media.*
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

// Structure de données
data class AudioProjectData(
    val rawFile: File,
    val peaks: FloatArray,
    val sampleRate: Int,
    val durationMs: Long
)

object AudioHelper {
    const val POINTS_PER_SECOND = 50 

    /**
     * POINT D'ENTRÉE : Prépare le projet (Cache ou Conversion)
     */
    fun prepareProject(inputFile: File, projectDir: File, onProgress: (Int) -> Unit): AudioProjectData? {
        val rawFile = File(projectDir, "working_audio.raw")
        val cachePeaks = File(projectDir, "waveform.peaks")
        
        // Vérification stricte du cache
        if (rawFile.exists() && rawFile.length() > 0 && cachePeaks.exists() && cachePeaks.length() > 0) {
            try {
                // Lecture ultra-rapide des pics
                val bytes = cachePeaks.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                
                // Estimation de la durée et du sampleRate basés sur le fichier RAW
                // (PCM 16bit = 2 bytes par sample)
                val totalSamples = rawFile.length() / 2
                val estimatedDurationMs = (totalSamples / 44.1).toLong() 
                
                return AudioProjectData(rawFile, floats, 44100, estimatedDurationMs)
            } catch (e: Exception) {
                // Cache corrompu, on supprime et on recommence
                rawFile.delete()
                cachePeaks.delete()
            }
        }

        return convertFileToRawSafe(inputFile, rawFile, cachePeaks, onProgress)
    }

    /**
     * CONVERSION ROBUSTE (Anti-Boucle Infinie)
     */
    private fun convertFileToRawSafe(inputFile: File, outputFile: File, peaksFile: File, onProgress: (Int) -> Unit): AudioProjectData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: BufferedOutputStream? = null
        
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var format: MediaFormat? = null
            var trackIdx = -1
            
            // Recherche de la piste audio
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    format = f; trackIdx = i; break
                }
            }
            if (format == null || trackIdx == -1) return null

            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch(e:Exception) { 0L }
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outputStream = BufferedOutputStream(FileOutputStream(outputFile))
            val peaksList = ArrayList<Float>()
            
            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            
            var outputSampleRate = 44100
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            
            var channelCount = 1
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            var samplesPerPoint = outputSampleRate / POINTS_PER_SECOND
            var peakAccumulator = 0f
            var sampleCounter = 0
            
            // SÉCURITÉ ANTI-BOUCLE
            var noOutputCounter = 0 
            val TIMEOUT_US = 5000L

            while (!isOutputEOS) {
                // 1. INPUT : Lire le fichier
                if (!isInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        
                        if (sz < 0) {
                            // Fin du fichier source
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. OUTPUT : Récupérer le PCM décodé
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFmt = codec.outputFormat
                    outputSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = if(newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                    samplesPerPoint = outputSampleRate / POINTS_PER_SECOND
                } 
                else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Le décodeur a besoin de temps. 
                    noOutputCounter++
                    
                    // Si on a fini de lire l'entrée mais que la sortie ne vient pas après 50 tentatives, on force l'arrêt.
                    if (isInputEOS && noOutputCounter > 50) {
                        isOutputEOS = true
                    } else {
                        // Petite pause pour ne pas figer le téléphone
                        try { Thread.sleep(5) } catch (e: InterruptedException) {}
                    }
                } 
                else if (outIdx >= 0) {
                    noOutputCounter = 0 // On a reçu des données, on reset le compteur de timeout
                    
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        
                        // Buffer temporaire pour calculs
                        val chunkData = ByteArray(info.size)
                        outBuf.get(chunkData)
                        
                        val shorts = ByteBuffer.wrap(chunkData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        
                        // Buffer pour écriture disque (Mono)
                        // Taille = (Nombre de samples total) / (Nombre de canaux) * 2 octets
                        val monoBytesCount = (shorts.remaining() / channelCount) * 2
                        val chunkBytesOut = ByteArray(monoBytesCount)
                        val chunkBufferOut = ByteBuffer.wrap(chunkBytesOut).order(ByteOrder.LITTLE_ENDIAN)

                        while (shorts.hasRemaining()) {
                            // Mixage Stéréo -> Mono
                            var sum = 0
                            var countMix = 0
                            // On lit N canaux pour en faire 1 sample
                            for (c in 0 until channelCount) {
                                if (shorts.hasRemaining()) {
                                    sum += shorts.get()
                                    countMix++
                                }
                            }
                            
                            val monoSample = if (countMix > 0) (sum / countMix).toShort() else 0
                            
                            // Écriture dans le buffer de sortie
                            chunkBufferOut.putShort(monoSample)
                            
                            // Calcul des pics pour l'affichage
                            val absVal = abs(monoSample.toFloat() / 32768f)
                            if (absVal > peakAccumulator) peakAccumulator = absVal
                            sampleCounter++
                            
                            if (sampleCounter >= samplesPerPoint) {
                                peaksList.add(peakAccumulator)
                                peakAccumulator = 0f
                                sampleCounter = 0
                            }
                        }
                        
                        // Écriture physique sur le disque
                        outputStream.write(chunkBytesOut)
                        
                        // Progression
                        if (durationUs > 0) {
                            val p = ((info.presentationTimeUs.toDouble() / durationUs.toDouble()) * 100).toInt()
                            onProgress(p.coerceIn(0, 100))
                        }
                    }
                    
                    codec.releaseOutputBuffer(outIdx, false)
                    
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            codec.stop()
            codec.release()
            extractor.release()

            // Sauvegarde des pics
            val finalPeaks = peaksList.toFloatArray()
            val peakBb = ByteBuffer.allocate(finalPeaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            finalPeaks.forEach { peakBb.putFloat(it) }
            peaksFile.writeBytes(peakBb.array())

            return AudioProjectData(outputFile, finalPeaks, outputSampleRate, durationUs / 1000)

        } catch (e: Exception) {
            e.printStackTrace()
            try { outputStream?.close() } catch(x:Exception){}
            try { codec?.stop(); codec?.release() } catch(x:Exception){}
            return null
        }
    }

    // --- FONCTIONS SUPPORTS (EXPORT & COUPE) ---

    fun cutRawFile(rawFile: File, sampleRate: Int, startPoint: Int, endPoint: Int): Boolean {
        // 1 sample (16bit) = 2 bytes
        val bytesPerSec = sampleRate * 2
        val bytesPerPoint = bytesPerSec / POINTS_PER_SECOND
        
        val startByte = startPoint.toLong() * bytesPerPoint
        val endByte = endPoint.toLong() * bytesPerPoint
        
        val tempFile = File(rawFile.parent, "temp_cut.raw")
        
        try {
            val raf = RandomAccessFile(rawFile, "r")
            val fos = FileOutputStream(tempFile)
            val buffer = ByteArray(64 * 1024) // Gros buffer pour aller vite
            
            // Copie partie 1
            raf.seek(0)
            var remaining = startByte
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break
                fos.write(buffer, 0, read)
                remaining -= read
            }
            
            // Saut partie 2
            raf.seek(endByte)
            
            // Copie partie 3 (jusqu'à la fin)
            var read: Int
            while (raf.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
            }
            
            raf.close()
            fos.close()
            
            if (rawFile.delete()) {
                tempFile.renameTo(rawFile)
                return true
            }
            return false
        } catch(e: Exception) { return false }
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        
        // On crée un RAW temporaire pour tout assembler
        val tempRaw = File(output.parent, "temp_global_merge.raw")
        val buffer = ByteArray(4096)
        var sampleRate = 44100
        
        try {
            val fos = BufferedOutputStream(FileOutputStream(tempRaw))
            
            for (file in inputs) {
                // Ici, on triche un peu : on suppose que les inputs sont déjà des M4A/MP3 valides.
                // Pour faire propre, il faudrait convertir chaque input en RAW via convertFileToRawSafe
                // Mais pour l'export final, on peut réutiliser la logique simplifiée ou appeler convertFileToRawSafe
                // Pour simplifier ici, on réutilise le décodage safe mais en appendant
                
                val pData = convertFileToRawSafe(file, File(file.parent, "temp_part.raw"), File(file.parent, "temp_part.peaks")) { }
                if (pData != null && pData.rawFile.exists()) {
                    sampleRate = pData.sampleRate // On garde le SR du dernier
                    val fis = FileInputStream(pData.rawFile)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                    fis.close()
                    pData.rawFile.delete() // Nettoyage
                    File(file.parent, "temp_part.peaks").delete()
                }
            }
            fos.flush()
            fos.close()
            
            // Convertir le RAW global en M4A
            val success = exportRawToM4A(tempRaw, output, sampleRate)
            tempRaw.delete()
            return success
            
        } catch (e: Exception) { return false }
    }

    fun exportRawToM4A(rawFile: File, destFile: File, sampleRate: Int): Boolean {
        if (!rawFile.exists()) return false
        var fis: FileInputStream? = null
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        
        try {
            fis = FileInputStream(rawFile)
            val buffer = ByteArray(8192)
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1) // Mono
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var isEOS = false
            
            while (true) {
                if (!isEOS) {
                    val inIdx = encoder.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val read = fis.read(buffer)
                        if (read == -1) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val inputBuf = encoder.getInputBuffer(inIdx)
                            inputBuf?.clear()
                            inputBuf?.put(buffer, 0, read)
                            encoder.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                
                val outIdx = encoder.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIdx)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    
                    if (info.size != 0 && muxerStarted) {
                        encodedData?.position(info.offset)
                        encodedData?.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encodedData!!, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return true
        } catch(e: Exception) { 
            e.printStackTrace()
            return false 
        }
        finally {
            try { fis?.close() } catch(e:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            try { muxer?.stop(); muxer?.release() } catch(e:Exception){}
        }
    }
    
    // Normalisation basique (sur fichier RAW)
    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float) -> Unit): Boolean {
        // En mode RAW, la normalisation est complexe car elle nécessite deux passes (Lecture Peak -> Ecriture Gain).
        // Pour simplifier ici, on renvoie false ou on implémente une passe simple si besoin.
        // La structure actuelle favorise la coupe et l'export.
        return false 
    }
}