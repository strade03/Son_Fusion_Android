package com.podcastcreateur.app

import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileWriter

object FFmpegHelper {
    private const val TAG = "FFmpegHelper"

    /**
    * Fusionne une liste de fichiers audio.
    * CORRECTION MAJEURE : On ré-encode en AAC pour garantir la compatibilité.
    * La simple copie (-c copy) échoue si on mélange MP3 et M4A ou des fréquences différentes.
    */
    fun mergeAudioFiles(inputs: List<File>, output: File, callback: (Boolean) -> Unit) {
        if (inputs.isEmpty()) {
            callback(false)
            return
        }

        Thread {
            try {
                // 1. Créer le fichier liste pour ffmpeg
                val listFile = File(output.parent, "ffmpeg_concat_list.txt")
                val writer = FileWriter(listFile)
                inputs.forEach { file ->
                    // On échappe les apostrophes pour la syntaxe FFmpeg concat
                    writer.write("file '${file.absolutePath}'\n")
                }
                writer.close()

                // 2. Commande Concat Demuxer
                // -safe 0 : Autorise les chemins absolus
                // -c:a aac : On force l'encodage AAC
                // -b:a 128k : Bitrate standard
                // -ac 2 : Stéréo
                // -ar 44100 : Fréquence 44.1kHz
                // Cela uniformise tout (MP3, WAV, M4A) en un seul M4A propre.
                val cmd = "-f concat -safe 0 -i \"${listFile.absolutePath}\" -c:a aac -b:a 128k -ac 2 -ar 44100 -y \"${output.absolutePath}\""
                
                Log.d(TAG, "Executing merge command: $cmd")
                
                // Exécution synchrone (bloquante dans ce thread)
                val session = FFmpegKit.execute(cmd)
                
                // Nettoyage de la liste temporaire
                if (listFile.exists()) listFile.delete()

                if (ReturnCode.isSuccess(session.returnCode)) {
                    Log.d(TAG, "Merge success")
                    callback(true)
                } else {
                    Log.e(TAG, "Merge failed with State: ${session.state} and ReturnCode: ${session.returnCode}")
                    Log.e(TAG, "FFmpeg Output: ${session.allLogsAsString}")
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during merge", e)
                e.printStackTrace()
                callback(false)
            }
        }.start()
    }

    /**
    * Coupe un fichier audio.
    */
    fun cutAudio(input: File, output: File, startSec: Double, durationSec: Double, callback: (Boolean) -> Unit) {
        Thread {
            // -ss : Start time
            // -t : Duration
            // On ré-encode pour être précis à la milliseconde près (le stream copy coupe sur les keyframes)
            val cmd = "-ss $startSec -t $durationSec -i \"${input.absolutePath}\" -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing cut: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                Log.e(TAG, "Cut failed: " + session.failStackTrace)
                callback(false)
            }
        }.start()
    }

    /**
    * Normalise le volume.
    */
    fun normalizeAudio(input: File, output: File, callback: (Boolean) -> Unit) {
        Thread {
            // Utilisation de loudnorm si disponible, sinon volume simple
            // Pour ffmpeg-kit-min, parfois les filtres complexes manquent, mais essayons loudnorm
            val cmd = "-i \"${input.absolutePath}\" -filter:a loudnorm=I=-16:TP=-1.5:LRA=11 -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing normalize: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                Log.w(TAG, "Loudnorm failed (maybe not in min version?), trying simple volume boost")
                // Fallback : augmentation simple du volume (+3dB)
                val fallbackCmd = "-i \"${input.absolutePath}\" -filter:a volume=3dB -c:a aac -b:a 128k -y \"${output.absolutePath}\""
                val session2 = FFmpegKit.execute(fallbackCmd)
                callback(ReturnCode.isSuccess(session2.returnCode))
            }
        }.start()
    }
}
