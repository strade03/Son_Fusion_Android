package com.podcastcreateur.app

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
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
    
    private var fileSampleRate = 44100 
    private var metadata: AudioMetadata? = null
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var currentZoom = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.progressBar.visibility = View.VISIBLE
        
        loadWaveform()

        binding.btnPlay.setOnClickListener { if(!isPlaying) playAudio() else stopAudio() }
        binding.btnCut.setOnClickListener { showUnsupportedMessage() }
        binding.btnNormalize.setOnClickListener { showUnsupportedMessage() }
        binding.btnSave.setOnClickListener { showUnsupportedMessage() }
        
        binding.btnZoomIn.setOnClickListener { 
            applyZoom(currentZoom * 1.5f)
        }
        binding.btnZoomOut.setOnClickListener { 
            applyZoom(currentZoom / 1.5f)
        }
        
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

    private fun showUnsupportedMessage() {
        Toast.makeText(this, "Édition non disponible. Utilisez Audacity pour éditer ce fichier.", Toast.LENGTH_LONG).show()
    }

    private fun applyZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(1.0f, 20.0f)
        
        val oldWidth = binding.waveformView.width
        val playheadRelativePos = if (oldWidth > 0 && pcmData.isNotEmpty()) {
            binding.waveformView.playheadPos.toFloat() / pcmData.size
        } else {
            0.5f
        }
        
        currentZoom = clampedZoom
        binding.waveformView.setZoomLevel(currentZoom)
        
        binding.waveformView.post {
            val newWidth = binding.waveformView.width
            val screenWidth = resources.displayMetrics.widthPixels
            val playheadX = playheadRelativePos * newWidth
            val targetScrollX = (playheadX - screenWidth / 2).toInt().coerceAtLeast(0)
            binding.scroller.smoothScrollTo(targetScrollX, 0)
        }
    }

    private fun loadWaveform() {
        Thread {
            try {
                // Charger les métadonnées
                metadata = AudioHelper.getAudioMetadata(currentFile)
                
                if (metadata == null) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this, "Impossible de lire ce fichier", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }
                
                fileSampleRate = metadata!!.sampleRate
                
                // Charger uniquement un aperçu (~10000 points)
                val preview = AudioHelper.loadWaveformPreview(currentFile)
                pcmData = preview.data
                
                runOnUiThread {
                    binding.waveformView.setWaveform(pcmData)
                    binding.progressBar.visibility = View.GONE
                    binding.txtDuration.text = formatDuration(metadata!!.durationSeconds)
                    
                    val sizeMb = currentFile.length() / (1024 * 1024)
                    Toast.makeText(
                        this, 
                        "Fichier: ${sizeMb}Mo • ${metadata!!.durationSeconds/60}min - Mode visualisation uniquement", 
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun playAudio() {
        if (metadata == null) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        // Pour la lecture, on utilise MediaPlayer qui streame automatiquement
        Thread {
            try {
                val player = android.media.MediaPlayer()
                player.setDataSource(currentFile.absolutePath)
                player.prepare()
                player.start()
                
                val duration = player.duration
                val startTime = System.currentTimeMillis()
                
                while (isPlaying && player.isPlaying) {
                    val elapsed = (System.currentTimeMillis() - startTime).toInt()
                    val progress = (elapsed.toFloat() / duration * pcmData.size).toInt()
                    
                    runOnUiThread {
                        binding.waveformView.playheadPos = progress.coerceIn(0, pcmData.size)
                        binding.waveformView.invalidate()
                        autoScroll(progress)
                    }
                    Thread.sleep(50)
                }
                
                player.release()
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            isPlaying = false
            runOnUiThread {
                binding.btnPlay.setImageResource(R.drawable.ic_play)
                binding.waveformView.playheadPos = 0
                binding.waveformView.invalidate()
            }
        }.start()
    }
    
    private fun autoScroll(sampleIdx: Int) {
        val totalSamples = pcmData.size
        if (totalSamples == 0) return
        val viewWidth = binding.waveformView.width 
        val screenW = resources.displayMetrics.widthPixels
        val x = (sampleIdx.toFloat() / totalSamples) * viewWidth
        val scrollX = (x - screenW / 2).toInt()
        binding.scroller.smoothScrollTo(scrollX, 0)
    }

    private fun stopAudio() { 
        isPlaying = false 
    }
    
    override fun onStop() { 
        super.onStop()
        stopAudio()
    }
}