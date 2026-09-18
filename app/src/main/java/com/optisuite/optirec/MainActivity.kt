package com.optisuite.optirec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.optisuite.optirec.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val stateListener: (RecStatus, String) -> Unit = { status, detail ->
        render(status, detail)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render(RecState.status, RecState.detail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        // Modo
        b.spMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.mode_video), getString(R.string.mode_audio))
        )
        b.spMode.setSelection(prefs.mode.ordinal)
        b.spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.mode = Mode.entries.getOrElse(pos) { Mode.VIDEO }
                applyModeUi()
                render(RecState.status, RecState.detail)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Camara
        b.spCamera.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Trasera", "Frontal")
        )
        b.spCamera.setSelection(if (prefs.useBackCamera) 0 else 1)
        b.spCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.useBackCamera = pos == 0
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Calidad
        b.spQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Quality.entries.map { it.label }
        )
        b.spQuality.setSelection(prefs.quality.ordinal)
        b.spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.quality = Quality.from(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Linterna
        b.swTorch.isChecked = prefs.torch
        b.swTorch.setOnCheckedChangeListener { _, checked -> prefs.torch = checked }

        b.btnPrimary.setOnClickListener {
            if (RecState.status == RecStatus.RECORDING) {
                send(Actions.STOP_REC)
            } else {
                if (!hasPerms()) { requestPerms(); return@setOnClickListener }
                send(Actions.START)
            }
        }

        b.btnArm.setOnClickListener {
            if (RecState.status == RecStatus.STOPPED) {
                if (!hasPerms()) { requestPerms(); return@setOnClickListener }
                send(Actions.ARM)
            } else {
                send(Actions.EXIT)
            }
        }

        b.btnView.setOnClickListener { openRecordings() }
        b.btnPerms.setOnClickListener { requestPerms() }
        b.btnBattery.setOnClickListener { OemHelper.requestIgnoreBattery(this) }
        b.btnOem.setOnClickListener { OemHelper.openAutoStartSettings(this) }

        applyModeUi()
    }

    override fun onResume() {
        super.onResume()
        RecState.addListener(stateListener)
    }

    override fun onPause() {
        super.onPause()
        RecState.removeListener(stateListener)
    }

    private fun applyModeUi() {
        val audio = prefs.mode == Mode.AUDIO
        b.grpVideo.visibility = if (audio) View.GONE else View.VISIBLE
        b.txtAudioNotice.visibility = if (audio) View.VISIBLE else View.GONE
    }

    private fun render(status: RecStatus, detail: String) {
        b.txtStatus.text = when (status) {
            RecStatus.RECORDING -> getString(R.string.title_recording)
            RecStatus.ARMED -> getString(R.string.title_armed)
            RecStatus.STOPPED -> getString(R.string.title_ready)
        }
        b.txtDetail.text = detail
        b.btnPrimary.text = if (status == RecStatus.RECORDING)
            getString(R.string.btn_stop) else getString(R.string.btn_start)
        b.btnArm.text = if (status == RecStatus.STOPPED)
            getString(R.string.btn_arm) else getString(R.string.btn_disarm)

        b.btnPerms.visibility = if (hasPerms()) View.GONE else View.VISIBLE
        b.btnBattery.visibility =
            if (OemHelper.isIgnoringBattery(this)) View.GONE else View.VISIBLE

        // No se puede cambiar de modo/ajustes mientras se graba.
        val idle = status != RecStatus.RECORDING
        b.spMode.isEnabled = idle
        b.spCamera.isEnabled = idle
        b.spQuality.isEnabled = idle
        b.swTorch.isEnabled = idle
    }

    private fun openRecordings() {
        val audio = prefs.mode == Mode.AUDIO
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                if (audio) setDataAndType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/*")
                else setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la galería", Toast.LENGTH_SHORT).show()
        }
    }

    private fun send(action: String) {
        val i = Intent(this, RecordingService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, i)
    }

    private fun neededPerms(): List<String> {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (prefs.mode == Mode.VIDEO) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        return need
    }

    private fun hasPerms(): Boolean = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        permLauncher.launch(neededPerms().toTypedArray())
    }
}
