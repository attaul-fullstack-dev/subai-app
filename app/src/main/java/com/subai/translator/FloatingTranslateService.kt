package com.subai.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class FloatingTranslateService : Service() {

    private lateinit var windowManager: WindowManager

    // Overlays
    private var bubbleView: View? = null
    private var selectionOverlayView: View? = null
    private var inPlaceOverlayView: InPlaceTranslationOverlayView? = null

    // LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var selectionParams: WindowManager.LayoutParams
    private lateinit var subtitleParams: WindowManager.LayoutParams

    // MediaProjection & Screen Capture
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    // Screen Dimensions
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    // Crop Coordinates (Saved In-Place Subtitle Region)
    private var cropX = 100
    private var cropY = 1600
    private var cropWidth = 800
    private var cropHeight = 220

    // State & Engines
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val translator = MLKitTranslator()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var sourceLang = "en"
    private var targetLang = "id"
    private var isAutoRealtime = true
    private var autoScanJob: Job? = null
    private var lastRecognizedFingerprint = ""
    private var noTextCount = 0

    companion object {
        const val CHANNEL_ID = "subai_overlay_channel"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(101, notification)
            }
        } catch (e: Exception) {
            Log.e("SubAI", "Error starting foreground service: ${e.message}")
        }

        updateScreenMetrics()
        initDefaultCropRegion()
        registerDisplayListener()
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(720)
            screenHeight = bounds.height().coerceAtLeast(720)
            screenDensity = resources.configuration.densityDpi.coerceAtLeast(160)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(720)
            screenHeight = metrics.heightPixels.coerceAtLeast(720)
            screenDensity = metrics.densityDpi.coerceAtLeast(160)
        }
    }

    private fun initDefaultCropRegion() {
        if (screenWidth > screenHeight) {
            // LANDSCAPE MODE (e.g. The Walking Dead)
            cropWidth = (screenWidth * 0.70).toInt()
            cropHeight = (screenHeight * 0.22).toInt().coerceAtLeast(140)
            cropX = (screenWidth - cropWidth) / 2
            cropY = (screenHeight * 0.74).toInt()
        } else {
            // PORTRAIT MODE
            cropWidth = (screenWidth * 0.90).toInt()
            cropHeight = (screenHeight * 0.16).toInt().coerceAtLeast(140)
            cropX = (screenWidth - cropWidth) / 2
            cropY = (screenHeight * 0.72).toInt()
        }
    }

    private fun registerDisplayListener() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                handleScreenOrientationChanged()
            }
        }
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun handleScreenOrientationChanged() {
        val prevW = screenWidth
        val prevH = screenHeight
        updateScreenMetrics()

        if (prevW != screenWidth || prevH != screenHeight) {
            Log.d("SubAI", "Orientation changed: $screenWidth x $screenHeight")
            initDefaultCropRegion()
            reconfigureVirtualDisplay()

            if (::bubbleParams.isInitialized && bubbleView != null) {
                bubbleParams.x = 20
                bubbleParams.y = screenHeight / 3
                try {
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                } catch (e: Exception) {}
            }
            updateSubtitleBounds()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleScreenOrientationChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            intent?.let {
                sourceLang = it.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
                targetLang = it.getStringExtra(EXTRA_TARGET_LANG) ?: "id"

                val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = it.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode != 0 && resultData != null) {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
                    setupVirtualDisplay()
                }
            }

            if (bubbleView == null) {
                setupFloatingBubble()
                setupSubtitleView()
                showSelectionOverlay()
            }
        } catch (e: Exception) {
            Log.e("SubAI", "onStartCommand error: ${e.message}")
        }

        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SubScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e("SubAI", "VirtualDisplay setup error: ${e.message}")
        }
    }

    private fun reconfigureVirtualDisplay() {
        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay?.surface = imageReader?.surface
        } catch (e: Exception) {
            Log.e("SubAI", "Reconfigure error: ${e.message}")
            setupVirtualDisplay()
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // =========================================================================
    // 1. FLOATING BUBBLE
    // =========================================================================
    private fun setupFloatingBubble() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubAITranslator)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = screenHeight / 3
        }

        bubbleView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_bubble, null)

        val cardBubble = bubbleView?.findViewById<View>(R.id.cardBubble)
        val quickMenu = bubbleView?.findViewById<View>(R.id.layoutQuickMenu)
        val btnMenuArea = bubbleView?.findViewById<Button>(R.id.btnMenuArea)
        val btnMenuToggle = bubbleView?.findViewById<Button>(R.id.btnMenuToggleAuto)
        val btnMenuClose = bubbleView?.findViewById<View>(R.id.btnMenuClose)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        cardBubble?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        quickMenu?.visibility = if (quickMenu?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        btnMenuArea?.setOnClickListener {
            quickMenu?.visibility = View.GONE
            showSelectionOverlay()
        }

        btnMenuToggle?.setOnClickListener {
            isAutoRealtime = !isAutoRealtime
            if (isAutoRealtime) {
                btnMenuToggle.text = "▶️ Auto: ON"
                btnMenuToggle.setBackgroundColor(0xFF00E5FF.toInt())
                startRealtimeAutoScanner()
            } else {
                btnMenuToggle.text = "⏸️ Jeda"
                btnMenuToggle.setBackgroundColor(0xFFFF9800.toInt())
                autoScanJob?.cancel()
                inPlaceOverlayView?.clearItems()
                inPlaceOverlayView?.visibility = View.GONE
            }
        }

        btnMenuClose?.setOnClickListener {
            stopSelf()
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    // =========================================================================
    // 2. SELECTION OVERLAY (Calibration Window)
    // =========================================================================
    private fun showSelectionOverlay() {
        if (selectionOverlayView != null) return

        autoScanJob?.cancel()
        inPlaceOverlayView?.clearItems()
        inPlaceOverlayView?.visibility = View.GONE

        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubAITranslator)
        selectionOverlayView = LayoutInflater.from(themedContext).inflate(R.layout.layout_crop_selection, null)

        val dragScaleView = selectionOverlayView?.findViewById<DragScaleView>(R.id.dragScaleView)
        val layoutToolbar = selectionOverlayView?.findViewById<View>(R.id.layoutToolbar)
        val btnLock = selectionOverlayView?.findViewById<Button>(R.id.btnLockArea)
        val btnPreset = selectionOverlayView?.findViewById<Button>(R.id.btnPresetBottom)
        val btnCancel = selectionOverlayView?.findViewById<Button>(R.id.btnCancelArea)

        cropWidth = cropWidth.coerceIn(200, screenWidth - 20)
        cropHeight = cropHeight.coerceIn(80, screenHeight - 20)
        cropX = cropX.coerceIn(0, screenWidth - cropWidth)
        cropY = cropY.coerceIn(0, screenHeight - cropHeight)

        val lp = FrameLayout.LayoutParams(cropWidth, cropHeight).apply {
            leftMargin = cropX
            topMargin = cropY
        }
        dragScaleView?.layoutParams = lp

        fun updateToolbarPosition(boxLeft: Int, boxTop: Int, boxWidth: Int, boxHeight: Int) {
            layoutToolbar?.post {
                val tbH = layoutToolbar.height.takeIf { it > 0 } ?: (48 * resources.displayMetrics.density).toInt()
                val tbW = layoutToolbar.width.takeIf { it > 0 } ?: (280 * resources.displayMetrics.density).toInt()

                val targetY = if (boxTop - tbH - 20 > 50) {
                    (boxTop - tbH - 20).toFloat()
                } else {
                    (boxTop + boxHeight + 20).toFloat()
                }
                val targetX = (boxLeft + (boxWidth - tbW) / 2).coerceIn(16, (screenWidth - tbW - 16).coerceAtLeast(16)).toFloat()

                layoutToolbar.x = targetX
                layoutToolbar.y = targetY
            }
        }

        dragScaleView?.positionListener = object : DragScaleView.OnPositionChangeListener {
            override fun onPositionChanged(left: Int, top: Int, width: Int, height: Int) {
                cropX = left
                cropY = top
                cropWidth = width
                cropHeight = height
                updateToolbarPosition(left, top, width, height)
            }
        }

        btnLock?.setOnClickListener {
            hideSelectionOverlay()
            lastRecognizedFingerprint = ""
            updateSubtitleBounds()
            serviceScope.launch {
                delay(300)
                startRealtimeAutoScanner()
            }
        }

        btnPreset?.setOnClickListener {
            initDefaultCropRegion()
            val newLp = FrameLayout.LayoutParams(cropWidth, cropHeight).apply {
                leftMargin = cropX
                topMargin = cropY
            }
            dragScaleView?.layoutParams = newLp
            dragScaleView?.layout(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
            updateToolbarPosition(cropX, cropY, cropWidth, cropHeight)
        }

        btnCancel?.setOnClickListener {
            hideSelectionOverlay()
            startRealtimeAutoScanner()
        }

        selectionOverlayView?.post {
            updateToolbarPosition(cropX, cropY, cropWidth, cropHeight)
        }

        selectionParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(selectionOverlayView, selectionParams)
    }

    private fun hideSelectionOverlay() {
        selectionOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            selectionOverlayView = null
        }
    }

    // =========================================================================
    // 3. IN-PLACE TRANSLATION OVERLAY (Exact Text Replacement)
    // =========================================================================
    private fun setupSubtitleView() {
        if (inPlaceOverlayView != null) return
        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubAITranslator)

        // FLAG_SECURE: Excluded from VirtualDisplay screen capture (ZERO OCR feedback loop!)
        // FLAG_NOT_TOUCHABLE: Allows tapping buttons underneath in-game!
        // FLAG_NOT_FOCUSABLE: Never steals keyboard/focus!
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE

        subtitleParams = WindowManager.LayoutParams(
            cropWidth,
            cropHeight,
            getOverlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cropX
            y = cropY
        }

        inPlaceOverlayView = InPlaceTranslationOverlayView(themedContext)
        inPlaceOverlayView?.visibility = View.GONE
        windowManager.addView(inPlaceOverlayView, subtitleParams)
    }

    private fun updateSubtitleBounds() {
        if (inPlaceOverlayView == null || !::subtitleParams.isInitialized) return
        subtitleParams.x = cropX
        subtitleParams.y = cropY
        subtitleParams.width = cropWidth
        subtitleParams.height = cropHeight
        try {
            windowManager.updateViewLayout(inPlaceOverlayView, subtitleParams)
        } catch (e: Exception) {}
    }

    // =========================================================================
    // 4. ACCURATE REAL-TIME SCANNER & IN-PLACE TEXT REPLACER
    // =========================================================================
    private fun startRealtimeAutoScanner() {
        autoScanJob?.cancel()
        autoScanJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isAutoRealtime) {
                try {
                    processCropAreaForSubtitle()
                    delay(450)
                } catch (e: Exception) {
                    delay(800)
                }
            }
        }
    }

    private suspend fun processCropAreaForSubtitle() {
        val fullBitmap = captureLatestFrame() ?: return

        val bmWidth = fullBitmap.width
        val bmHeight = fullBitmap.height

        val safeX = cropX.coerceIn(0, (bmWidth - 20).coerceAtLeast(0))
        val safeY = cropY.coerceIn(0, (bmHeight - 20).coerceAtLeast(0))
        val safeW = cropWidth.coerceIn(20, bmWidth - safeX)
        val safeH = cropHeight.coerceIn(20, bmHeight - safeY)

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)
        } catch (e: Exception) {
            return
        }

        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        try {
            val visionText = textRecognizer.process(inputImage).await()
            val detectedItems = ArrayList<TranslatedItem>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text.trim()
                    val box = line.boundingBox
                    if (lineText.length >= 2 && box != null) {
                        // Skip system / UI text
                        if (lineText.contains("SubAI", ignoreCase = true) ||
                            lineText.contains("Kunci Area", ignoreCase = true) ||
                            lineText.contains("Bawah Game", ignoreCase = true) ||
                            lineText.contains("Tarik Sudut", ignoreCase = true)) {
                            continue
                        }
                        val translated = translator.translateSingleLine(lineText, sourceLang, targetLang)
                        detectedItems.add(TranslatedItem(lineText, translated, box))
                    }
                }
            }

            if (detectedItems.isNotEmpty()) {
                noTextCount = 0
                val fingerprint = detectedItems.joinToString("|") { "${it.originalText}@${it.rect.left},${it.rect.top}" }

                if (fingerprint != lastRecognizedFingerprint) {
                    lastRecognizedFingerprint = fingerprint
                    withContext(Dispatchers.Main) {
                        if (inPlaceOverlayView == null) setupSubtitleView()
                        updateSubtitleBounds()
                        inPlaceOverlayView?.setTranslatedItems(detectedItems)
                        inPlaceOverlayView?.visibility = View.VISIBLE
                    }
                }
            } else {
                noTextCount++
                if (noTextCount >= 3) {
                    withContext(Dispatchers.Main) {
                        inPlaceOverlayView?.clearItems()
                        inPlaceOverlayView?.visibility = View.GONE
                        lastRecognizedFingerprint = ""
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore OCR anomalies
        }
    }

    private fun captureLatestFrame(): Bitmap? {
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SubAI Game Translator",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SubAI Game Translator")
            .setContentText("Menerjemahkan area subtitle game secara real-time")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        autoScanJob?.cancel()
        textRecognizer.close()
        translator.close()

        displayListener?.let {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(it)
        }

        bubbleView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        hideSelectionOverlay()
        inPlaceOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
