package com.subai.translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Interactive Drag & Scale Selection Box (Reconstructed from base.apk DragScaleView)
 * Allows dragging anywhere inside to move, and dragging any of the 4 corners or 4 edges
 * to resize smoothly in real-time.
 */
class DragScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnPositionChangeListener {
        fun onPositionChanged(left: Int, top: Int, width: Int, height: Int)
    }

    var positionListener: OnPositionChangeListener? = null

    // Touch Modes
    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_TL = 2 // Top-Left
        private const val MODE_TR = 3 // Top-Right
        private const val MODE_BL = 4 // Bottom-Left
        private const val MODE_BR = 5 // Bottom-Right
        private const val MODE_T = 6  // Top Edge
        private const val MODE_B = 7  // Bottom Edge
        private const val MODE_L = 8  // Left Edge
        private const val MODE_R = 9  // Right Edge
    }

    private var currentMode = MODE_NONE

    // Threshold for touching corner handles & edges (in px)
    private val density = resources.displayMetrics.density
    private val cornerThreshold = (32 * density).toInt()
    private val edgeThreshold = (20 * density).toInt()

    // Minimum size of the crop box
    private val minWidth = (120 * density).toInt()
    private val minHeight = (45 * density).toInt()

    // Touch tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialLeft = 0
    private var initialTop = 0
    private var initialRight = 0
    private var initialBottom = 0

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2600E5FF") // 15% translucent cyan fill
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 6f * density), 0f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val handleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val tipText = "↔ Geser / Tarik Sudut untuk Atur Ukuran ↕"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return super.onTouchEvent(event)
        val parentWidth = parentView.width
        val parentHeight = parentView.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialLeft = left
                initialTop = top
                initialRight = right
                initialBottom = bottom
                downRawX = event.rawX
                downRawY = event.rawY

                currentMode = detectTouchMode(event.x, event.y)
                parentView.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()

                var newL = initialLeft
                var newT = initialTop
                var newR = initialRight
                var newB = initialBottom

                when (currentMode) {
                    MODE_DRAG -> {
                        val w = initialRight - initialLeft
                        val h = initialBottom - initialTop
                        newL = (initialLeft + dx).coerceIn(0, parentWidth - w)
                        newT = (initialTop + dy).coerceIn(0, parentHeight - h)
                        newR = newL + w
                        newB = newT + h
                    }
                    MODE_BR -> {
                        newR = (initialRight + dx).coerceIn(initialLeft + minWidth, parentWidth)
                        newB = (initialBottom + dy).coerceIn(initialTop + minHeight, parentHeight)
                    }
                    MODE_BL -> {
                        newL = (initialLeft + dx).coerceIn(0, initialRight - minWidth)
                        newB = (initialBottom + dy).coerceIn(initialTop + minHeight, parentHeight)
                    }
                    MODE_TR -> {
                        newR = (initialRight + dx).coerceIn(initialLeft + minWidth, parentWidth)
                        newT = (initialTop + dy).coerceIn(0, initialBottom - minHeight)
                    }
                    MODE_TL -> {
                        newL = (initialLeft + dx).coerceIn(0, initialRight - minWidth)
                        newT = (initialTop + dy).coerceIn(0, initialBottom - minHeight)
                    }
                    MODE_R -> {
                        newR = (initialRight + dx).coerceIn(initialLeft + minWidth, parentWidth)
                    }
                    MODE_L -> {
                        newL = (initialLeft + dx).coerceIn(0, initialRight - minWidth)
                    }
                    MODE_B -> {
                        newB = (initialBottom + dy).coerceIn(initialTop + minHeight, parentHeight)
                    }
                    MODE_T -> {
                        newT = (initialTop + dy).coerceIn(0, initialBottom - minHeight)
                    }
                }

                // Update layout immediately
                layout(newL, newT, newR, newB)
                val lp = layoutParams as? FrameLayout.LayoutParams
                lp?.let {
                    it.leftMargin = newL
                    it.topMargin = newT
                    it.width = newR - newL
                    it.height = newB - newT
                    layoutParams = it
                }

                positionListener?.onPositionChanged(newL, newT, newR - newL, newB - newT)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentMode = MODE_NONE
                positionListener?.onPositionChanged(left, top, width, height)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectTouchMode(touchX: Float, touchY: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        val cThres = cornerThreshold.toFloat()
        val eThres = edgeThreshold.toFloat()

        // 1. Corners (Priority)
        val isLeft = touchX < cThres
        val isRight = touchX > w - cThres
        val isTop = touchY < cThres
        val isBottom = touchY > h - cThres

        if (isLeft && isTop) return MODE_TL
        if (isRight && isTop) return MODE_TR
        if (isLeft && isBottom) return MODE_BL
        if (isRight && isBottom) return MODE_BR

        // 2. Edges
        if (touchY < eThres) return MODE_T
        if (touchY > h - eThres) return MODE_B
        if (touchX < eThres) return MODE_L
        if (touchX > w - eThres) return MODE_R

        // 3. Inside area -> Drag whole box
        return MODE_DRAG
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Fill translucent box
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        // 2. Dashed Border
        canvas.drawRect(2f, 2f, w - 2f, h - 2f, borderPaint)

        // 3. Corner L-Brackets (Corner Handles)
        val bracketLen = (24 * density).coerceAtMost(w / 3f).coerceAtMost(h / 3f)

        // Top-Left (┏)
        canvas.drawLine(0f, 0f, bracketLen, 0f, handlePaint)
        canvas.drawLine(0f, 0f, 0f, bracketLen, handlePaint)

        // Top-Right (┓)
        canvas.drawLine(w, 0f, w - bracketLen, 0f, handlePaint)
        canvas.drawLine(w, 0f, w, bracketLen, handlePaint)

        // Bottom-Left (┗)
        canvas.drawLine(0f, h, bracketLen, h, handlePaint)
        canvas.drawLine(0f, h, 0f, h - bracketLen, handlePaint)

        // Bottom-Right (┛)
        canvas.drawLine(w, h, w - bracketLen, h, handlePaint)
        canvas.drawLine(w, h, w, h - bracketLen, handlePaint)

        // 4. Edge Drag Indicators (Small dots at midpoints)
        val dotRadius = 4f * density
        canvas.drawCircle(w / 2f, 2f, dotRadius, handleDotPaint) // Top mid
        canvas.drawCircle(w / 2f, h - 2f, dotRadius, handleDotPaint) // Bottom mid
        canvas.drawCircle(2f, h / 2f, dotRadius, handleDotPaint) // Left mid
        canvas.drawCircle(w - 2f, h / 2f, dotRadius, handleDotPaint) // Right mid

        // 5. Center Hint Text
        val textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(tipText, w / 2f, textY, textPaint)
    }
}
