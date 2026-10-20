package com.subai.translator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.subai.translator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val sourceLangCode = getSelectedLanguageCode(binding.spinnerSourceLang.selectedItemPosition)
            val targetLangCode = getSelectedLanguageCode(binding.spinnerTargetLang.selectedItemPosition)

            val serviceIntent = Intent(this, FloatingTranslateService::class.java).apply {
                putExtra(FloatingTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingTranslateService.EXTRA_RESULT_DATA, result.data)
                putExtra(FloatingTranslateService.EXTRA_SOURCE_LANG, sourceLangCode)
                putExtra(FloatingTranslateService.EXTRA_TARGET_LANG, targetLangCode)
            }

            ContextCompat.startForegroundService(this, serviceIntent)
            binding.tvStatus.text = "Status: Bola Melayang Aktif! Silakan buka game Anda (The Walking Dead, dll.)."
            binding.btnStartService.visibility = View.GONE
            binding.btnStopService.visibility = View.VISIBLE
            Toast.makeText(this, "Bola melayang aktif! Buka game Anda sekarang.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Izin rekam layar ditolak.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupLanguages()
        setupListeners()
        updatePermissionStatus()
    }

    private fun setupLanguages() {
        val languageMap = listOf(
            "English (Bahasa Game)" to TranslateLanguage.ENGLISH,
            "Indonesian (Indonesia)" to TranslateLanguage.INDONESIAN,
            "Japanese (Jepang)" to TranslateLanguage.JAPANESE,
            "Korean (Korea)" to TranslateLanguage.KOREAN,
            "Chinese (Mandarin)" to TranslateLanguage.CHINESE,
            "Spanish (Spanyol)" to TranslateLanguage.SPANISH,
            "French (Prancis)" to TranslateLanguage.FRENCH,
            "German (Jerman)" to TranslateLanguage.GERMAN,
            "Arabic (Arab)" to TranslateLanguage.ARABIC
        )

        val langNames = languageMap.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langNames)

        binding.spinnerSourceLang.adapter = adapter
        binding.spinnerTargetLang.adapter = adapter

        // Default: Game English (index 0) -> Target Indonesian (index 1)
        binding.spinnerSourceLang.setSelection(0)
        binding.spinnerTargetLang.setSelection(1)
    }

    private fun setupListeners() {
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Izin layar mengambang sudah aktif!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Harap berikan Izin Layar Mengambang terlebih dahulu (Langkah 1)!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, FloatingTranslateService::class.java))
            binding.btnStartService.visibility = View.VISIBLE
            binding.btnStopService.visibility = View.GONE
            binding.tvStatus.text = "Status: Bola melayang telah dimatikan."
            Toast.makeText(this, "Tombol melayang dimatikan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            binding.btnOverlayPermission.text = "✓ Izin Layar Mengambang Sudah Aktif"
            binding.btnOverlayPermission.isEnabled = false
            binding.btnOverlayPermission.setBackgroundColor(0xFF2E7D32.toInt())
            binding.tvStatus.text = "Status: Siap! Klik 'Aktifkan Bola Melayang'."
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun getSelectedLanguageCode(position: Int): String {
        return when (position) {
            0 -> TranslateLanguage.ENGLISH
            1 -> TranslateLanguage.INDONESIAN
            2 -> TranslateLanguage.JAPANESE
            3 -> TranslateLanguage.KOREAN
            4 -> TranslateLanguage.CHINESE
            5 -> TranslateLanguage.SPANISH
            6 -> TranslateLanguage.FRENCH
            7 -> TranslateLanguage.GERMAN
            8 -> TranslateLanguage.ARABIC
            else -> TranslateLanguage.ENGLISH
        }
    }
}
