package com.subai.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class MLKitTranslator {

    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()

        val translator = Translation.getClient(options)
        return try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            translator.translate(text).await()
        } catch (e: Exception) {
            text
        } finally {
            translator.close()
        }
    }
}
