package com.podcastcreateur.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var metadata: AudioMetadata? = null
    
    // NOUVEAU : MediaPlayer au lieu de AudioTrack/MediaCodec pour la lecture
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 2.5f 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.waveformView.setZoomLevel(currentZoom)
        
        loadWaveformStreaming()

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { 
            Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Refaire ?").setPositiveButton("Oui") { _, _ ->
                stopAudio()
                val regex = Regex("^(\\d{3}_)(.*)\\.(.*)$")
                val match = regex.find(currentFile.name)
                if (match != null) {
                    val (prefix, name, _) = match.destructured
                    val intent = Intent(this, RecorderActivity::class.java)
                    intent.putExtra("PROJECT_PATH", currentFile.parent)
                    intent.putExtra("CHRONICLE_NAME", name)
                    intent.putExtra("CHRONICLE_PREFIX", prefix)
                    startActivity(intent)
                    finish()
                }
            }.setNegativeButton("Non", null).show()
        }
    }

    private fun loadWaveformStreaming() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata
            
            if (meta == null) {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                binding.waveformView.initialize(meta.totalSamples)
            }

            AudioHelper.loadWaveformStream(currentFile) { newChunk ->
                runOnUiThread {
                    binding.waveformView.appendData(newChunk)
                    if (binding.progressBar.visibility == View.VISIBLE) {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun formatTime(durationMs: Long): String {
        val sec = (durationMs / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(0.5f, 50.0f)
        currentZoom = clamped
        val centerSample = binding.waveformView.getCenterSample(binding.scroller.scrollX, binding.scroller.width)
        binding.waveformView.setZoomLevel(currentZoom)
        binding.waveformView.post {
            val newScrollX = binding.waveformView.sampleToPixel(centerSample) - (binding.scroller.width / 2)
            binding.scroller.scrollTo(newScrollX.toInt().coerceAtLeast(0), 0)
        }
    }

    // --- NOUVELLE LECTURE VIA MEDIAPLAYER ---
    private fun playAudio() {
        val meta = metadata ?: return
        
        // Arrêter l'ancien si existant
        stopAudio()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                prepare()
                
                // Calcul position départ
                val startSample = if(binding.waveformView.selectionStart >= 0) 
                                    binding.waveformView.selectionStart 
                                  else 
                                    binding.waveformView.playheadPos
                
                val startMs = (startSample * 1000L) / meta.sampleRate
                
                seekTo(startMs.toInt())
                start()
                
                setOnCompletionListener { 
                    stopAudio() 
                }
            }
            
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            // Boucle de mise à jour UI (Curseur)
            playbackJob = lifecycleScope.launch {
                val endSample = if(binding.waveformView.selectionEnd > binding.waveformView.selectionStart && binding.waveformView.selectionStart >= 0) 
                                    binding.waveformView.selectionEnd 
                                else 
                                    meta.totalSamples.toInt()
                                    
                while (mediaPlayer?.isPlaying == true) {
                    val currentMs = mediaPlayer?.currentPosition ?: 0
                    
                    // Conversion Ms -> Sample
                    val currentSample = ((currentMs.toLong() * meta.sampleRate) / 1000).toInt()
                    
                    // Mise à jour vue
                    binding.waveformView.playheadPos = currentSample
                    binding.waveformView.invalidate()
                    autoScroll(currentSample)
                    
                    // Arrêt si fin de sélection atteinte
                    if (binding.waveformView.selectionStart >= 0 && currentSample >= endSample) {
                        mediaPlayer?.pause()
                        break
                    }
                    
                    delay(25) // ~40 FPS
                }
                
                // Fin de lecture (soit arrêt manuel, soit fin selection)
                if (mediaPlayer?.isPlaying != true) {
                    stopAudio()
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur lecture", Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        // Scroll fluide
        if (abs(binding.scroller.scrollX - target) > 10) {
            binding.scroller.scrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    private fun cutSelection() {
        val meta = metadata ?: return
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return
        
        stopAudio() // Arrêter la lecture avant de couper
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(currentFile.parent, "tmp_cut.m4a")
            val success = AudioHelper.deleteRegionStreaming(currentFile, tmp, start, end)
            
            if(success) {
                currentFile.delete()
                tmp.renameTo(currentFile)
                withContext(Dispatchers.Main) {
                    binding.waveformView.clearData()
                    loadWaveformStreaming()
                    Toast.makeText(this@EditorActivity, "Coupé !", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur coupe", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun normalizeSelection() {
        val meta = metadata ?: return
        val start = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val end = if(binding.waveformView.selectionEnd > start) binding.waveformView.selectionEnd else meta.totalSamples.toInt()
        
        stopAudio()
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(currentFile.parent, "tmp_norm.m4a")
            val sMs = (start * 1000L)/meta.sampleRate
            val eMs = (end * 1000L)/meta.sampleRate
            if(AudioHelper.normalizeAudio(currentFile, tmp, sMs, eMs, meta.sampleRate, 0.95f) {}) {
                currentFile.delete(); tmp.renameTo(currentFile)
                withContext(Dispatchers.Main) {
                    binding.waveformView.clearData()
                    loadWaveformStreaming() 
                    Toast.makeText(this@EditorActivity, "Normalisé", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onStop() { super.onStop(); stopAudio() }
}