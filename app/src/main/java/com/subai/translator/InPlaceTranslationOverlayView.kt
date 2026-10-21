package com.subai.translator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

data class TranslatedItem(
    val originalText: String,
    val translatedText: String,
    val rect: Rect // Bounding box relative to crop area
)

/**
 * Custom overlay view that paints translated text directly on top of the original text
 * at the exact coordinates detected by ML Kit OCR.
 * Completely replaces the original text in-place (timpa teks asli).
 */
class InPlaceTranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val items = ArrayList<TranslatedItem>()
    private val density = resources.displayMetrics.density

    // Background pill to obscure / cover original English text
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60A0D14") // 90% opaque dark background
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

        for (item in items) {
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0) continue

            // Add slight padding around detected bounding box
            val padX = 6f * density
            val padY = 4f * density

            val left = (r.left.toFloat() - padX).coerceAtLeast(0f)
            val top = (r.top.toFloat() - padY).coerceAtLeast(0f)
            val right = (r.right.toFloat() + padX).coerceAtMost(viewW)
            val bottom = (r.bottom.toFloat() + padY).coerceAtMost(viewH)

            val boxRect = RectF(left, top, right, bottom)
            val cornerRadius = 6f * density

            // 1. Draw solid dark background over original English text
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, bgPaint)
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, borderPaint)

            // 2. Measure & fit text inside the bounding box
            val boxH = boxRect.height()
            val boxW = boxRect.width()

            // Calculate font size proportional to box height
            var targetTextSize = (boxH * 0.65f).coerceIn(12f * density, 24f * density)
            textPaint.textSize = targetTextSize

            // If text is wider than box, shrink font size to fit
            val textWidth = textPaint.measureText(item.translatedText)
            if (textWidth > boxW - (8f * density) && boxW > 20f) {
                val scale = (boxW - (8f * density)) / textWidth
                targetTextSize = (targetTextSize * scale).coerceAtLeast(10f * density)
                textPaint.textSize = targetTextSize
            }

            // 3. Draw translated text centered at exact position
            val fontMetrics = textPaint.fontMetrics
            val baseline = boxRect.centerY() - (fontMetrics.descent + fontMetrics.ascent) / 2f
            canvas.drawText(item.translatedText, boxRect.centerX(), baseline, textPaint)
        }
    }
}
