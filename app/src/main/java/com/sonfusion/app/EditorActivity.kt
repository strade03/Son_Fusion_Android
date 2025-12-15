package com.podcastcreateur.app

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    private var pcmData: ShortArray = ShortArray(0)
    private var currentSampleRate = 44100 // Stockage de la fréquence détectée
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    
    private var zoomLevel = 1.0f
    private var screenWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        screenWidth = resources.displayMetrics.widthPixels

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.progressBar.visibility = View.VISIBLE
        
        loadWaveform()

        binding.btnPlay.setOnClickListener { if(!isPlaying) playAudio() else stopAudio() }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { saveFile() }
        
        // Zoom sans Toast
        binding.btnZoomIn.setOnClickListener { applyZoom(zoomLevel * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(zoomLevel / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Refaire l'enregistrement ?")
                .setMessage("L'audio actuel sera remplacé.")
                .setPositiveButton("Oui") { _, _ ->
                    stopAudio()
                    val regex = Regex("^(\\d{3}_)(.*)\\.(.*)$")
                    val match = regex.find(currentFile.name)
                    if (match != null) {
                        val (prefix, name, _) = match.destructured
                        val projectPath = currentFile.parent
                        val scriptPath = File(projectPath, "$prefix$name.txt").absolutePath
                        val intent = Intent(this, RecorderActivity::class.java)
                        intent.putExtra("PROJECT_PATH", projectPath)
                        intent.putExtra("CHRONICLE_NAME", name)
                        intent.putExtra("CHRONICLE_PREFIX", prefix)
                        intent.putExtra("SCRIPT_PATH", scriptPath)
                        startActivity(intent)
                        finish()
                    }
                }
                .setNegativeButton("Annuler", null).show()
        }
    }

    private fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(1.0f, 20.0f) // Augmenter max zoom
        if (abs(clamped - zoomLevel) < 0.05f) return
        
        zoomLevel = clamped
        
        // Calcul nouvelle largeur
        val newWidth = (screenWidth * zoomLevel).toInt()
        
        // On applique directement au layoutParams
        val params = binding.waveformView.layoutParams
        params.width = newWidth
        binding.waveformView.layoutParams = params
        
        // On force la vue à se redessiner
        binding.waveformView.invalidate()
        binding.waveformView.requestLayout()
    }

    private fun loadWaveform() {
        Thread {
            try {
                // Utilisation de la nouvelle méthode qui renvoie data + sampleRate
                val content = AudioHelper.decodeToPCM(currentFile)
                pcmData = content.data
                currentSampleRate = content.sampleRate // On stocke la fréquence réelle
                
                runOnUiThread {
                    binding.waveformView.setWaveform(pcmData)
                    binding.progressBar.visibility = View.GONE
                    binding.txtDuration.text = formatTime(pcmData.size)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Erreur lecture", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun formatTime(samples: Int): String {
        if (currentSampleRate == 0) return "00:00"
        val sec = samples / currentSampleRate // Utilisation fréquence réelle
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun playAudio() {
        if (pcmData.isEmpty()) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        Thread {
            // Configuration AudioTrack avec la fréquence réelle du fichier
            val minBuf = AudioTrack.getMinBufferSize(currentSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC, 
                currentSampleRate, // ICI : 44100 ou 48000 selon le fichier
                AudioFormat.CHANNEL_OUT_MONO, 
                AudioFormat.ENCODING_PCM_16BIT, 
                minBuf, 
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()

            var startIdx = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
            startIdx = startIdx.coerceIn(0, pcmData.size)

            val bufferSize = 4096
            var offset = startIdx
            val endIdx = if (binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else pcmData.size

            while (isPlaying && offset < endIdx) {
                val len = minOf(bufferSize, endIdx - offset)
                audioTrack?.write(pcmData, offset, len)
                offset += len
                
                // Mise à jour UI moins fréquente pour performance
                if (offset % (currentSampleRate / 5) == 0) { 
                     runOnUiThread { 
                         binding.waveformView.playheadPos = offset
                         binding.waveformView.invalidate()
                         autoScroll(offset)
                     }
                }
            }

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isPlaying = false
            runOnUiThread { 
                binding.btnPlay.setImageResource(R.drawable.ic_play) 
                if (offset >= endIdx) binding.waveformView.playheadPos = startIdx
                binding.waveformView.invalidate()
            }
        }.start()
    }
    
    private fun autoScroll(sampleIdx: Int) {
        val totalSamples = pcmData.size
        if (totalSamples == 0) return
        
        // Largeur actuelle de la vue (qui peut être très grande si zoomée)
        val viewWidth = binding.waveformView.width 
        val x = (sampleIdx.toFloat() / totalSamples) * viewWidth
        
        // On centre
        val scrollX = (x - screenWidth / 2).toInt()
        
        binding.scroller.smoothScrollTo(scrollX, 0)
    }

    private fun stopAudio() {
        isPlaying = false
    }
    
    private fun cutSelection() {
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return

        AlertDialog.Builder(this)
            .setTitle("Couper ?")
            .setPositiveButton("Oui") { _, _ ->
                 val newPcm = ShortArray(pcmData.size - (end - start))
                System.arraycopy(pcmData, 0, newPcm, 0, start)
                System.arraycopy(pcmData, end, newPcm, start, pcmData.size - end)

                pcmData = newPcm
                binding.waveformView.setWaveform(pcmData)
                binding.waveformView.clearSelection()
                binding.txtDuration.text = formatTime(pcmData.size)
            }
            .setNegativeButton("Non", null).show()
    }

    private fun normalizeSelection() {
        val start = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val end = if (binding.waveformView.selectionEnd > start) binding.waveformView.selectionEnd else pcmData.size

        var maxVal = 0
        for (i in start until end) {
            if (abs(pcmData[i].toInt()) > maxVal) maxVal = abs(pcmData[i].toInt())
        }

        if (maxVal > 0) {
            val factor = 32767f / maxVal
            for (i in start until end) {
                pcmData[i] = (pcmData[i] * factor).toInt().toShort()
            }
            binding.waveformView.invalidate()
            Toast.makeText(this, "Normalisé !", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile() {
        binding.progressBar.visibility = View.VISIBLE
        Thread {
            // On sauvegarde avec la même fréquence que le fichier source pour ne pas altérer la vitesse
            val success = AudioHelper.savePCMToAAC(pcmData, currentFile, currentSampleRate)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }
    
    override fun onStop() {
        super.onStop()
        stopAudio()
    }
}