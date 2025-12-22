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

import com.linc.amplituda.Amplituda
import com.linc.amplituda.Compress

import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var sampleRate = 44100
    private var totalDurationMs = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f 
    private lateinit var amplituda: Amplituda

    // Gestion des coupes virtuelles
    // Stocke des paires (StartMS, EndMS) à ignorer
    private val pendingCuts = ArrayList<Pair<Long, Long>>() 
    
    // Pour mapper l'affichage (qui rétrécit) au fichier physique (qui reste entier jusqu'à sauvegarde)
    private var currentPointsPerSecond = 50 // Sera calculé dynamiquement

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        amplituda = Amplituda(this)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        loadWaveformFast()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index) }

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        // Bouton Coupe : Virtuel maintenant
        binding.btnCut.setOnClickListener { performVirtualCut() }
        
        // Bouton Normaliser : Reste direct pour l'instant (ou pourrait être différé aussi, mais plus complexe)
        binding.btnNormalize.setOnClickListener { normalizeSelectionStreaming() }
        
        // Bouton Save : Applique tout
        binding.btnSave.setOnClickListener { saveChangesAndExit() }
        
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

    private fun loadWaveformFast() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = AudioHelper.getAudioMetadata(currentFile)
            if (meta != null) {
                sampleRate = meta.sampleRate
                totalDurationMs = meta.duration
            }
            
            // --- OPTIMISATION RÉSOLUTION DYNAMIQUE ---
            val durationSec = totalDurationMs / 1000
            
            // Plus le fichier est court, plus on veut de points par seconde pour la précision
            val targetPps = when {
                durationSec < 60 -> 400   // Très court (<1min) : Ultra précis
                durationSec < 300 -> 200  // Court (<5min) : Précis
                durationSec < 900 -> 100  // Moyen (15min) : Standard
                else -> 50                // Très long : Low res pour perf
            }
            currentPointsPerSecond = targetPps

            amplituda.processAudio(
                currentFile.absolutePath, 
                Compress.withParams(Compress.AVERAGE, targetPps) 
            ).get({ result ->
                val amplitudes = result.amplitudesAsList()
                
                val maxVal = amplitudes.maxOrNull() ?: 1
                val floats = FloatArray(amplitudes.size)
                
                for (i in amplitudes.indices) {
                    floats[i] = amplitudes[i].toFloat() / maxVal.toFloat() 
                }

                runOnUiThread {
                    binding.txtDuration.text = formatTime(totalDurationMs)
                    binding.waveformView.initialize(floats.size.toLong())
                    binding.waveformView.appendData(floats)
                    
                    // --- ZOOM AUTO : Remplir l'écran ---
                    binding.scroller.post {
                        val screenWidth = binding.scroller.width
                        if (screenWidth > 0 && floats.isNotEmpty()) {
                            // On calcule le zoom pour que tout tienne
                            val fitZoom = screenWidth.toFloat() / floats.size.toFloat()
                            // On applique, mais avec un min de 0.5 pour pas que ce soit illisible sur les très longs
                            applyZoom(fitZoom.coerceAtLeast(0.5f))
                        }
                    }
                    
                    binding.progressBar.visibility = View.GONE
                }
            }, { error ->
                error.printStackTrace()
                runOnUiThread { 
                    Toast.makeText(this@EditorActivity, "Erreur chargement onde", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            })
        }
    }

    // --- LOGIQUE COUPE VIRTUELLE ---

    private fun performVirtualCut() {
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        
        // 1. Calculer le temps RÉEL correspondant à ce qui est affiché
        // C'est complexe car si on a déjà coupé avant, l'index visuel est décalé.
        // Pour simplifier : On calcule le temps "visuel" et on projette sur le temps réel.
        
        // Temps visuel (ce que l'utilisateur voit)
        val msPerPoint = 1000.0 / currentPointsPerSecond
        val visualStartMs = (startIdx * msPerPoint).toLong()
        val visualEndMs = (endIdx * msPerPoint).toLong()
        val durationCut = visualEndMs - visualStartMs
        
        // On doit retrouver où ça tombe dans le fichier physique.
        // On additionne la durée de toutes les coupes précédentes qui ont eu lieu AVANT ce point.
        var offsetStart = 0L
        // (Note: C'est une approximation pour l'UI, le vrai mapping parfait nécessiterait de reconstruire l'index complet
        // mais pour l'usage courant c'est acceptable si on coupe du début à la fin).
        
        val realStartMs = mapVisualToRealTime(visualStartMs)
        val realEndMs = realStartMs + durationCut
        
        // 2. Ajouter à la liste des coupes (Mémoire)
        pendingCuts.add(Pair(realStartMs, realEndMs))
        
        // 3. Mettre à jour l'UI (On supprime l'onde visuellement)
        binding.waveformView.deleteRange(startIdx, endIdx)
        
        // 4. Mettre à jour la durée affichée
        val newTotalDuration = totalDurationMs - durationCut
        // On ne change pas totalDurationMs (physique) mais on peut afficher une durée virtuelle
        binding.txtDuration.text = formatTime(newTotalDuration) // Affichage seulement
        
        Toast.makeText(this, "Coupe (en attente de sauvegarde)", Toast.LENGTH_SHORT).show()
    }
    
    // Fonction helper pour retrouver le temps réel
    private fun mapVisualToRealTime(visualMs: Long): Long {
        var realMs = visualMs
        // On parcourt les coupes triées
        val sortedCuts = pendingCuts.sortedBy { it.first }
        
        for (cut in sortedCuts) {
            if (cut.first < realMs) {
                // Si une coupe a eu lieu avant mon point, mon point est décalé d'autant
                val cutLen = cut.second - cut.first
                realMs += cutLen
            }
        }
        return realMs
    }

    private fun saveChangesAndExit() {
        if (pendingCuts.isEmpty()) {
            finish()
            return
        }
        
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpFile = File(currentFile.parent, "tmp_save_final.m4a")
            
            // Convertir les MS en SAMPLES pour AudioHelper
            val samplesCuts = ArrayList<Pair<Long, Long>>()
            val meta = AudioHelper.getAudioMetadata(currentFile) ?: return@launch
            
            // Ratio Sample/MS précis
            val samplesPerMs = meta.sampleRate / 1000.0
            
            for (cut in pendingCuts) {
                val sStart = (cut.first * samplesPerMs).toLong()
                val sEnd = (cut.second * samplesPerMs).toLong()
                samplesCuts.add(Pair(sStart, sEnd))
            }
            
            // Appliquer TOUTES les coupes d'un coup
            val success = AudioHelper.saveWithCuts(currentFile, tmpFile, samplesCuts)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    Toast.makeText(this@EditorActivity, "Enregistré avec succès", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur sauvegarde", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun normalizeSelectionStreaming() {
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpFile = File(currentFile.parent, "tmp_norm.m4a")
            val success = AudioHelper.normalizeAudio(currentFile, tmpFile, 0, totalDurationMs, sampleRate, 0.98f) {}
            
            withContext(Dispatchers.Main) {
                if(success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    // Reset UI car le fichier a changé
                    pendingCuts.clear() 
                    binding.waveformView.clearData()
                    loadWaveformFast() 
                    Toast.makeText(this@EditorActivity, "Normalisé", Toast.LENGTH_SHORT).show()
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur normalisation", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        // Affichage du temps "Visuel" (relatif à ce qui reste)
        val msPerPoint = 1000.0 / currentPointsPerSecond
        val visualMs = (index * msPerPoint).toLong()
        binding.txtCurrentTime.text = formatTime(visualMs)
    }
    
    private fun formatTime(durationMs: Long): String {
        val sec = (durationMs / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(0.1f, 50.0f)
        currentZoom = clamped
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun playAudio() {
        stopAudio()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                prepare()
                
                // Position de départ : On doit convertir l'index visuel en temps réel
                val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
                val msPerPoint = 1000.0 / currentPointsPerSecond
                val visualStartMs = (startIdx * msPerPoint).toLong()
                
                // Si on a des coupes, il faut sauter au bon endroit réel
                val realStartMs = mapVisualToRealTime(visualStartMs)
                
                seekTo(realStartMs.toInt())
                start()
                setOnCompletionListener { stopAudio() }
            }
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            playbackJob = lifecycleScope.launch {
                // On trie les coupes pour la lecture
                val sortedCuts = pendingCuts.sortedBy { it.first }
                
                while (mediaPlayer?.isPlaying == true) {
                    val currentRealMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    
                    // 1. LOGIQUE DE SAUT (SKIP)
                    var inCut = false
                    for (cut in sortedCuts) {
                        // Si on est DANS une coupe
                        if (currentRealMs >= cut.first && currentRealMs < cut.second) {
                            // On saute à la fin de la coupe
                            mediaPlayer?.seekTo(cut.second.toInt())
                            inCut = true
                            break
                        }
                    }
                    
                    if (!inCut) {
                        // 2. LOGIQUE D'AFFICHAGE CURSEUR
                        // Convertir Real Time -> Visual Time (l'inverse de tout à l'heure)
                        var visualMs = currentRealMs
                        for (cut in sortedCuts) {
                            if (currentRealMs > cut.second) {
                                visualMs -= (cut.second - cut.first)
                            }
                        }
                        
                        val msPerPoint = 1000.0 / currentPointsPerSecond
                        val currentIdx = (visualMs / msPerPoint).toInt()
                        
                        binding.waveformView.playheadPos = currentIdx
                        binding.waveformView.invalidate()
                        runOnUiThread { updateCurrentTimeDisplay(currentIdx) }
                        autoScroll(currentIdx)
                        
                        // Stop selection
                        // Note : La sélection de fin est visuelle, donc on compare avec l'index visuel
                        if (binding.waveformView.selectionEnd > 0 && currentIdx >= binding.waveformView.selectionEnd) {
                            mediaPlayer?.pause()
                            break
                        }
                    }
                    
                    delay(40)
                }
                if (mediaPlayer?.isPlaying != true) stopAudio()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 50) {
            binding.scroller.smoothScrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    override fun onStop() { super.onStop(); stopAudio() }
}