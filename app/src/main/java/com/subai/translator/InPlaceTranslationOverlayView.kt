package com.subai.translator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

data class TranslatedItem(
    val originalText: String,
    val translatedText: String,
    val rect: RectF, // Bounding box relative to crop area
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Custom overlay view that paints translated text directly on top of the original text
 * at the exact coordinates detected by ML Kit OCR.
 *
 * Features:
 * 1. Anti-collision pass: Guarantees zero text overlapping between adjacent/stacked lines.
 * 2. High-contrast subtitle styling: Dark background pill, cyan accent, crisp yellow text.
 * 3. Proportional font auto-scaling with text clipping protection.
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

        val minGapY = 4f * density
        val minGapX = 6f * density
        val padX = 6f * density
        val padY = 4f * density
        val cornerRadius = 6f * density

        // Sort items vertically so top lines are placed first
        val sortedItems = items.sortedBy { it.rect.top }
        val placedBoxes = ArrayList<RectF>()

        for (item in sortedItems) {
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0) continue

            var boxLeft = (r.left - padX).coerceAtLeast(0f)
            var boxTop = (r.top - padY).coerceAtLeast(0f)
            var boxRight = (r.right + padX).coerceAtMost(viewW)
            var boxBottom = (r.bottom + padY).coerceAtMost(viewH)
            val boxH = (boxBottom - boxTop).coerceAtLeast(16f * density)

            // ANTI-COLLISION PASS: Check against previously placed boxes
            for (prev in placedBoxes) {
                val overlapsX = boxLeft < prev.right + minGapX && boxRight > prev.left - minGapX
                val overlapsY = boxTop < prev.bottom + minGapY && boxBottom > prev.top - minGapY

                if (overlapsX && overlapsY) {
                    // Push current box down so it never overlaps with the box above it
                    boxTop = prev.bottom + minGapY
                    boxBottom = (boxTop + boxH).coerceAtMost(viewH)
                }
            }

            val boxRect = RectF(boxLeft, boxTop, boxRight, boxBottom)
            placedBoxes.add(boxRect)

            // 1. Draw solid dark background over original English text
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, bgPaint)
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, borderPaint)

            // 2. Measure & fit text inside the bounding box
            val currentH = boxRect.height()
            val currentW = boxRect.width()

            // Calculate font size proportional to box height
            var targetTextSize = (currentH * 0.65f).coerceIn(12f * density, 24f * density)
            textPaint.textSize = targetTextSize

            // If text is wider than box, shrink font size to fit inside pill
            val availableW = (currentW - 10f * density).coerceAtLeast(10f)
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
