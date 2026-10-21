package com.subai.translator

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
 * 
 * Features:
 * - High-contrast AAA game subtitle styling: Vibrant Yellow fill with crisp Black outline.
 * - Guaranteed readability: Never squishes text into unreadable micro-font (floor 13.5sp).
 * - Intelligent auto-expanding pill: Adapts seamlessly when Indonesian translation is longer than English.
 * - Multi-line word wrapping: Wraps gracefully if subtitle exceeds horizontal crop bounds.
 */
class InPlaceTranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val items = ArrayList<TranslatedItem>()
    private val density = resources.displayMetrics.density

    // Semi-transparent dark pill background (85% opacity):
    // Obscures original English text completely while letting underlying game choice buttons,
    // borders, and countdown timers remain clearly visible and interactive!
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90A0D14")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600E5FF") // subtle cyan neon border
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    // High contrast game yellow fill
    private val textFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Crisp black outline stroke (Universal video game subtitle standard: 100% readable on ANY background)
    private val textStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
        val cornerRadius = 6f * density
        val hPad = 10f * density
        val vPad = 4f * density

        for (item in items) {
            val r = item.rect
            if (r.width() <= 0 || r.height() <= 0) continue
            val translated = item.translatedText.trim()
            if (translated.isEmpty()) continue

            // 1. Calculate optimal, readable font size (Guaranteed floor of 13.5sp)
            val boxH = r.height()
            val targetTextSize = (boxH * 0.72f).coerceIn(13.5f * density, 20f * density)
            textFillPaint.textSize = targetTextSize
            textStrokePaint.textSize = targetTextSize

            val textW = textFillPaint.measureText(translated)
            val maxAvailableW = (viewW - 16f * density).coerceAtLeast(40f)

            // 2. Check if text fits in a single line or needs wrapping
            if (textW + hPad * 2f <= maxAvailableW) {
                // SINGLE LINE: Auto-expand pill horizontally from center to fit longer Indonesian text
                val pillW = (textW + hPad * 2f).coerceAtLeast(r.width() + hPad)
                var pillLeft = r.centerX() - pillW / 2f
                var pillRight = pillLeft + pillW

                // Clamp within view boundaries
                if (pillLeft < 4f * density) {
                    val shift = 4f * density - pillLeft
                    pillLeft = 4f * density
                    pillRight += shift
                }
                if (pillRight > viewW - 4f * density) {
                    val shift = pillRight - (viewW - 4f * density)
                    pillRight = viewW - 4f * density
                    pillLeft = (pillLeft - shift).coerceAtLeast(4f * density)
                }

                val pillTop = (r.top - vPad).coerceAtLeast(2f * density)
                val pillBottom = (r.bottom + vPad).coerceAtMost(viewH - 2f * density)
                val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)

                // Draw pill background & cyan border
                canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
                canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

                // Draw text (Stroke first for outline, then Yellow fill on top)
                val fontMetrics = textFillPaint.fontMetrics
                val baseline = pillRect.centerY() - (fontMetrics.descent + fontMetrics.ascent) / 2f
                val drawX = pillRect.centerX() - textW / 2f

                canvas.drawText(translated, drawX, baseline, textStrokePaint)
                canvas.drawText(translated, drawX, baseline, textFillPaint)
            } else {
                // MULTI-LINE: Wrap cleanly with StaticLayout
                val wrapW = maxAvailableW.toInt()
                val fillLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(translated, 0, translated.length, textFillPaint, wrapW)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.15f)
                        .setIncludePad(false)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(translated, textFillPaint, wrapW, Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false)
                }

                val strokeLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(translated, 0, translated.length, textStrokePaint, wrapW)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.15f)
                        .setIncludePad(false)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(translated, textStrokePaint, wrapW, Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false)
                }

                val layoutW = fillLayout.width.toFloat()
                val layoutH = fillLayout.height.toFloat()
                val pillW = layoutW + hPad * 2f
                val pillH = layoutH + vPad * 2f

                var pillLeft = r.centerX() - pillW / 2f
                if (pillLeft < 4f * density) pillLeft = 4f * density
                if (pillLeft + pillW > viewW - 4f * density) pillLeft = viewW - pillW - 4f * density

                var pillTop = r.centerY() - pillH / 2f
                if (pillTop < 2f * density) pillTop = 2f * density
                if (pillTop + pillH > viewH - 2f * density) pillTop = viewH - pillH - 2f * density

                val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

                // Draw pill background & cyan border
                canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
                canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

                // Draw wrapped text
                canvas.save()
                canvas.translate(pillRect.left + hPad, pillRect.top + vPad)
                strokeLayout.draw(canvas)
                fillLayout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
