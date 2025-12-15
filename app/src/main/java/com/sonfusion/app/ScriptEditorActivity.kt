package com.podcastcreateur.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var scriptFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)

        val path = intent.getStringExtra("SCRIPT_PATH")
        if (path == null) { finish(); return }
        scriptFile = File(path)

        val input = findViewById<EditText>(R.id.inputScript)
        val btnSave = findViewById<Button>(R.id.btnSaveScript)

        if (scriptFile.exists()) {
            input.setText(scriptFile.readText())
        }

        btnSave.setOnClickListener {
            scriptFile.writeText(input.text.toString())
            Toast.makeText(this, "Script sauvegardé", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}