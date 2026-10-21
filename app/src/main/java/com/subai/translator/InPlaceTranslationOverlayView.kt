package com.subai.translator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

data class TranslatedItem(
    val originalText: String,
    val translatedText: String,
    val rect: RectF, // Exact bounding box relative to crop area
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Custom overlay view that paints translated text directly on top of the original text
 * at the exact coordinates detected by ML Kit OCR.
 * Completely replaces the original text in-place (timpa teks asli) without arbitrary shifting.
 */
class InPlaceTranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val items = ArrayList<TranslatedItem>()
    private val density = resources.displayMetrics.density

    // Semi-transparent dark pill background (70% opacity):
    // Obscures original English text while letting underlying game choice buttons,
    // borders, and the countdown timer bar remain clearly visible to the player!
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B30A0D14") // 70% opacity dark pill
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D00E5FF") // subtle cyan border
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B") // High contrast game subtitle yellow
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    fun setTranslatedItems(newItems: List<TranslatedItem>) {
        items.clear()
        items.addAll(newItems)
        invalidate()
    }

    fun clearItems() {
        items.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val cornerRadius = 4f * density

        for (item in items) {
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0) continue

            // Use exact detected bounding box coordinates without arbitrary shifts
            val left = r.left.coerceAtLeast(0f)
            val top = r.top.coerceAtLeast(0f)
            val right = r.right.coerceAtMost(viewW)
            val bottom = r.bottom.coerceAtMost(viewH)

            val boxRect = RectF(left, top, right, bottom)

            // 1. Draw solid dark background over original English text
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, bgPaint)
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, borderPaint)

            // 2. Measure & fit text inside the bounding box
            val currentH = boxRect.height()
            val currentW = boxRect.width()

            // Calculate font size proportional to box height
            var targetTextSize = (currentH * 0.70f).coerceIn(11f * density, 24f * density)
            textPaint.textSize = targetTextSize

            // If text is wider than box, shrink font size to fit inside pill
            val availableW = (currentW - 6f * density).coerceAtLeast(10f)
            val textWidth = textPaint.measureText(item.translatedText)
            if (textWidth > availableW) {
                val scale = availableW / textWidth
                targetTextSize = (targetTextSize * scale).coerceAtLeast(9f * density)
                textPaint.textSize = targetTextSize
            }

            // 3. Draw translated text centered at exact position
            val fontMetrics = textPaint.fontMetrics
            val baseline = boxRect.centerY() - (fontMetrics.descent + fontMetrics.ascent) / 2f
            canvas.drawText(item.translatedText, boxRect.centerX(), baseline, textPaint)
        }
    }
}
