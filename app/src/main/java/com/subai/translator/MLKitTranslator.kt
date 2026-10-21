package com.subai.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class MLKitTranslator {

    private var currentTranslator: Translator? = null
    private var currentSource: String = ""
    private var currentTarget: String = ""
    private val mutex = Mutex()

    // Fast in-memory cache to make game translation instant & flicker-free
    private val translationCache = LinkedHashMap<String, String>(100, 0.75f, true)

    private suspend fun getOrCreateClient(source: String, target: String): Translator? {
        mutex.withLock {
            if (currentTranslator != null && currentSource == source && currentTarget == target) {
                return currentTranslator
            }
            try {
                currentTranslator?.close()
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
                val client = Translation.getClient(options)
                val conditions = DownloadConditions.Builder().build()
                client.downloadModelIfNeeded(conditions).await()
                currentTranslator = client
                currentSource = source
                currentTarget = target
                return client
            } catch (e: Exception) {
                return null
            }
        }
    }

    suspend fun translateSingleLine(line: String, source: String, target: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ""
        
        // Check cache first
        synchronized(translationCache) {
            translationCache[trimmed]?.let { return it }
        }

        val client = getOrCreateClient(source, target) ?: return trimmed
        return try {
            val result = client.translate(trimmed).await().trim()
            synchronized(translationCache) {
                if (translationCache.size > 200) {
                    val firstKey = translationCache.keys.firstOrNull()
                    if (firstKey != null) translationCache.remove(firstKey)
                }
                translationCache[trimmed] = result
            }
            result
        } catch (e: Exception) {
            trimmed
        }
    }

    /**
     * Translates text while strictly preserving lines and structure.
     * Prevents multi-line menus (PLAY / EPISODES / HELP) or dialogue choices
     * from being squashed into nonsensical merged sentences.
     */
    suspend fun translateAccurate(rawText: String, source: String, target: String): String {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        if (lines.size == 1) {
            return translateSingleLine(lines[0], source, target)
        }

        // Multi-line translation (e.g. menus or multiple dialogue choices)
        val translated = lines.map { line ->
            translateSingleLine(line, source, target)
        }
        return translated.joinToString("\n")
    }

    fun close() {
        try {
            currentTranslator?.close()
            currentTranslator = null
        } catch (e: Exception) {}
    }
}
