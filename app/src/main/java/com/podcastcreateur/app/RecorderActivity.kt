package com.podcastcreateur.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.podcastcreateur.app.databinding.ActivityRecorderBinding
import java.io.File

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputFile: File
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectPath = intent.getStringExtra("PROJECT_PATH") ?: run { finish(); return }
        val prefix = intent.getStringExtra("CHRONICLE_PREFIX") ?: "999_"
        val name = intent.getStringExtra("CHRONICLE_NAME") ?: "Temp"
        val scriptPath = intent.getStringExtra("SCRIPT_PATH")

        if (scriptPath != null) {
            val scriptFile = File(scriptPath)
            if (scriptFile.exists()) {
                val text = scriptFile.readText()
                if (text.isNotEmpty()) binding.txtScriptDisplay.text = text
            }
        }
        
        outputFile = File(projectPath, "$prefix$name.m4a")

        binding.btnRecordToggle.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT <= 32) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        val missing = requiredPermissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isEmpty()) {
            startRecording()
        } else {
            // Afficher Dialog explicatif
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_missing))
                .setMessage("L'application a besoin de l'accès au micro et au stockage pour enregistrer.")
                .setPositiveButton("OK") { _, _ ->
                    ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    // Callback permission optionnel, mais géré ici pour relancer si accepté
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startRecording()
        }
    }

    private fun startRecording() {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(Constants.BIT_RATE)
            setAudioSamplingRate(Constants.SAMPLE_RATE)
            setOutputFile(outputFile.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
                binding.btnRecordToggle.setImageResource(R.drawable.ic_stop)
                binding.chronometer.base = SystemClock.elapsedRealtime()
                binding.chronometer.start()
            } catch (e: Exception) { 
                e.printStackTrace()
                Toast.makeText(this@RecorderActivity, "Erreur initialisation micro", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        
        mediaRecorder = null
        isRecording = false
        binding.chronometer.stop()
        binding.btnRecordToggle.setImageResource(R.drawable.ic_record)
        
        Toast.makeText(this, getString(R.string.recording_finished), Toast.LENGTH_SHORT).show()
        
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("FILE_PATH", outputFile.absolutePath)
        startActivity(intent)
        
        finish() 
    }
    
    override fun onStop() {
        super.onStop()
        if (isRecording) stopRecording()
    }
}