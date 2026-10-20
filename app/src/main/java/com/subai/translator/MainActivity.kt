package com.subai.translator

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.subai.translator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null

    private val subtitleList = mutableListOf<SubtitleItem>()
    private lateinit var adapter: SubtitleAdapter
    private val translator = MLKitTranslator()

    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val activeItem = subtitleList.firstOrNull { item ->
                        currentPos in item.startMs..item.endMs
                    }
                    if (activeItem != null) {
                        binding.tvActiveSubtitle.text = activeItem.translatedText.ifEmpty { activeItem.originalText }
                        binding.tvActiveSubtitle.visibility = View.VISIBLE
                    } else {
                        binding.tvActiveSubtitle.visibility = View.GONE
                    }
                }
            }
            syncHandler.postDelayed(this, 100)
        }
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedVideoUri = it
            binding.tvPlaceholder.visibility = View.GONE
            setupPlayer(it)
            binding.tvStatus.text = "Video berhasil dimuat. Siap diproses."
        }
    }

    private val exportSrtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri: Uri? ->
        uri?.let { saveSrtToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupLanguages()
    }

    private fun setupUI() {
        adapter = SubtitleAdapter(subtitleList)
        binding.rvSubtitles.layoutManager = LinearLayoutManager(this)
        binding.rvSubtitles.adapter = adapter

        binding.btnSelectVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        binding.btnProcess.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processSubtitles()
        }

        binding.btnExportSrt.setOnClickListener {
            if (subtitleList.isEmpty()) {
                Toast.makeText(this, "Belum ada subtitle untuk diexport!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportSrtLauncher.launch("subtitle_terjemahan.srt")
        }
    }

    private fun setupLanguages() {
        val languageMap = listOf(
            "Indonesian" to TranslateLanguage.INDONESIAN,
            "English" to TranslateLanguage.ENGLISH,
            "Japanese" to TranslateLanguage.JAPANESE,
            "Korean" to TranslateLanguage.KOREAN,
            "Spanish" to TranslateLanguage.SPANISH,
            "French" to TranslateLanguage.FRENCH,
            "German" to TranslateLanguage.GERMAN,
            "Arabic" to TranslateLanguage.ARABIC,
            "Chinese" to TranslateLanguage.CHINESE
        )

        val langNames = languageMap.map { it.first }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langNames)

        binding.spinnerSourceLang.adapter = spinnerAdapter
        binding.spinnerTargetLang.adapter = spinnerAdapter

        // Default: Source English (index 1), Target Indonesian (index 0)
        binding.spinnerSourceLang.setSelection(1)
        binding.spinnerTargetLang.setSelection(0)
    }

    private fun setupPlayer(uri: Uri) {
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
        binding.playerView.player = exoPlayer
        syncHandler.post(syncRunnable)
    }

    private fun processSubtitles() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnProcess.isEnabled = false
        binding.tvStatus.text = "Memproses audio dan menerjemahkan..."

        val sourceLangCode = getSelectedLanguageCode(binding.spinnerSourceLang.selectedItemPosition)
        val targetLangCode = getSelectedLanguageCode(binding.spinnerTargetLang.selectedItemPosition)

        lifecycleScope.launch(Dispatchers.IO) {
            // Demo generator / parser: membuat segmen subtitle untuk video
            val duration = withContext(Dispatchers.Main) { exoPlayer?.duration ?: 10000L }.coerceAtLeast(6000L)
            val segments = mutableListOf<SubtitleItem>()
            val segmentDuration = 3000L
            var currentMs = 0L
            var index = 1

            while (currentMs < duration) {
                val endMs = (currentMs + segmentDuration).coerceAtMost(duration)
                val originalText = "Audio dialog segmen #$index"
                val translated = translator.translate(originalText, sourceLangCode, targetLangCode)

                segments.add(SubtitleItem(index, currentMs, endMs, originalText, translated))
                currentMs += segmentDuration
                index++
            }

            withContext(Dispatchers.Main) {
                subtitleList.clear()
                subtitleList.addAll(segments)
                adapter.updateData(subtitleList)

                binding.progressBar.visibility = View.GONE
                binding.btnProcess.isEnabled = true
                binding.btnExportSrt.isEnabled = true
                binding.tvStatus.text = "Selesai! Subtitle siap diputar & diexport."
                Toast.makeText(this@MainActivity, "Subtitle berhasil diterjemahkan!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSelectedLanguageCode(position: Int): String {
        return when (position) {
            0 -> TranslateLanguage.INDONESIAN
            1 -> TranslateLanguage.ENGLISH
            2 -> TranslateLanguage.JAPANESE
            3 -> TranslateLanguage.KOREAN
            4 -> TranslateLanguage.SPANISH
            5 -> TranslateLanguage.FRENCH
            6 -> TranslateLanguage.GERMAN
            7 -> TranslateLanguage.ARABIC
            8 -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.INDONESIAN
        }
    }

    private fun saveSrtToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
                val srtContent = buildString {
                    subtitleList.forEach { append(it.toSrtBlock()) }
                }
                outputStream.write(srtContent.toByteArray())
            }
            Toast.makeText(this, "File .SRT berhasil disimpan!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacks(syncRunnable)
        exoPlayer?.release()
        exoPlayer = null
    }
}
