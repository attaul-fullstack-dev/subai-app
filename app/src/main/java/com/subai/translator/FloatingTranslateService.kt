package com.subai.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
    private var selectionBoxView: View? = null
    private var subtitleView: View? = null

    // LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var boxParams: WindowManager.LayoutParams
    private lateinit var subtitleParams: WindowManager.LayoutParams

    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Dimensions
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    // Crop Coordinates (The exact box over the game's subtitle area)
    private var cropX = 50
    private var cropY = 1600
    private var cropWidth = 980
    private var cropHeight = 350
    private var isBoxLocked = false

    // State
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val translator = MLKitTranslator()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var sourceLang = "en"
    private var targetLang = "id"
    private var isAutoRealtime = true
    private var autoScanJob: Job? = null
    private var lastRecognizedText = ""
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
            Log.e("SubAI", "Error foreground: ${e.message}")
        }

        getScreenMetrics()
    }

    private fun getScreenMetrics() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels.coerceAtLeast(720)
        screenHeight = metrics.heightPixels.coerceAtLeast(1280)
        screenDensity = metrics.densityDpi.coerceAtLeast(160)

        // Default crop box: bottom 30% of screen (standard subtitle position)
        cropWidth = (screenWidth * 0.92).toInt()
        cropX = (screenWidth - cropWidth) / 2
        cropHeight = (screenHeight * 0.22).toInt().coerceAtLeast(200)
        cropY = (screenHeight * 0.70).toInt()
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
                setupSelectionBox()
                setupSubtitleView()
                startRealtimeAutoScanner()
            }
        } catch (e: Exception) {
            Log.e("SubAI", "onStartCommand error: ${e.message}")
            Toast.makeText(this, "Error memulai: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SubScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e("SubAI", "VirtualDisplay error: ${e.message}")
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // 1. FLOATING BUBBLE CONTROLS
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
            y = screenHeight / 4
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
                        // Toggle Menu Visibility
                        quickMenu?.visibility = if (quickMenu?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        // Menu: Show / Adjust Selection Area Box
        btnMenuArea?.setOnClickListener {
            quickMenu?.visibility = View.GONE
            showSelectionBoxForAdjustment()
        }

        // Menu: Toggle Realtime ON / OFF
        btnMenuToggle?.setOnClickListener {
            isAutoRealtime = !isAutoRealtime
            if (isAutoRealtime) {
                btnMenuToggle.text = "▶️ Auto: ON"
                btnMenuToggle.setBackgroundColor(0xFF00E5FF.toInt())
                startRealtimeAutoScanner()
                Toast.makeText(this, "Real-Time Auto: AKTIF", Toast.LENGTH_SHORT).show()
            } else {
                btnMenuToggle.text = "⏸️ Jeda"
                btnMenuToggle.setBackgroundColor(0xFFFF9800.toInt())
                autoScanJob?.cancel()
                subtitleView?.visibility = View.GONE
                Toast.makeText(this, "Real-Time Auto: JEDA", Toast.LENGTH_SHORT).show()
            }
        }

        btnMenuClose?.setOnClickListener {
            stopSelf()
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    // 2. SELECTION / CROP BOX (Like base.apk's DragScaleView)
    private fun setupSelectionBox() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubAITranslator)
        boxParams = WindowManager.LayoutParams(
            cropWidth,
            cropHeight,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cropX
            y = cropY
        }

        selectionBoxView = LayoutInflater.from(themedContext).inflate(R.layout.layout_selection_box, null)

        val btnLock = selectionBoxView?.findViewById<Button>(R.id.btnLockArea)
        val header = selectionBoxView?.findViewById<View>(R.id.headerSelectionBox)
        val tvHint = selectionBoxView?.findViewById<View>(R.id.tvBoxHint)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        selectionBoxView?.setOnTouchListener { _, event ->
            if (isBoxLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = boxParams.x
                    initialY = boxParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    boxParams.x = (initialX + (event.rawX - touchX).toInt()).coerceIn(0, screenWidth - cropWidth)
                    boxParams.y = (initialY + (event.rawY - touchY).toInt()).coerceIn(0, screenHeight - cropHeight)
                    cropX = boxParams.x
                    cropY = boxParams.y
                    windowManager.updateViewLayout(selectionBoxView, boxParams)
                    true
                }
                else -> false
            }
        }

        btnLock?.setOnClickListener {
            isBoxLocked = true
            header?.visibility = View.GONE
            tvHint?.visibility = View.GONE
            selectionBoxView?.setBackgroundResource(0) // completely transparent during gameplay
            Toast.makeText(this, "Area subtitle terkunci! Mulai scan real-time.", Toast.LENGTH_SHORT).show()
        }

        windowManager.addView(selectionBoxView, boxParams)
    }

    private fun showSelectionBoxForAdjustment() {
        isBoxLocked = false
        selectionBoxView?.setBackgroundResource(R.drawable.bg_selection_box)
        selectionBoxView?.findViewById<View>(R.id.headerSelectionBox)?.visibility = View.VISIBLE
        selectionBoxView?.findViewById<View>(R.id.tvBoxHint)?.visibility = View.VISIBLE
        Toast.makeText(this, "Geser kotak ke tempat dialog game muncul, lalu klik Kunci Area.", Toast.LENGTH_LONG).show()
    }

    // 3. SUBTITLE OVERLAY (Displayed safely ABOVE the selection box to prevent OCR recursion!)
    private fun setupSubtitleView() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubAITranslator)
        subtitleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            // Positioned ABOVE the crop box (with 10px margin) so OCR NEVER sees it!
            y = (cropY - 180).coerceAtLeast(60)
        }

        subtitleView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_subtitle, null)
        subtitleView?.findViewById<View>(R.id.btnCloseSubtitle)?.setOnClickListener {
            subtitleView?.visibility = View.GONE
        }
        subtitleView?.visibility = View.GONE

        windowManager.addView(subtitleView, subtitleParams)
    }

    // 4. CONTINUOUS REAL-TIME OCR & TRANSLATION ENGINE
    private fun startRealtimeAutoScanner() {
        autoScanJob?.cancel()
        autoScanJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isAutoRealtime) {
                try {
                    processCropAreaForSubtitle()
                    delay(550) // Scan every 550ms
                } catch (e: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private suspend fun processCropAreaForSubtitle() {
        val fullBitmap = captureLatestFrame() ?: return

        // 1. CROP ONLY THE SELECTED GAME SUBTITLE AREA
        val safeX = cropX.coerceIn(0, fullBitmap.width - 10)
        val safeY = cropY.coerceIn(0, fullBitmap.height - 10)
        val safeW = cropWidth.coerceAtMost(fullBitmap.width - safeX)
        val safeH = cropHeight.coerceAtMost(fullBitmap.height - safeY)

        val croppedBitmap = try {
            Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)
        } catch (e: Exception) {
            return
        }

        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        try {
            val visionText = textRecognizer.process(inputImage).await()
            val rawText = visionText.text.trim().replace("\n", " ")

            // Filter out system words or empty noise
            val cleanText = if (rawText.contains("SubAI", ignoreCase = true) || rawText.contains("Real-Time", ignoreCase = true)) {
                ""
            } else {
                rawText
            }

            if (cleanText.length >= 2) {
                noTextCount = 0
                // ONLY translate if text actually changed!
                if (cleanText != lastRecognizedText) {
                    lastRecognizedText = cleanText
                    val translated = translator.translate(cleanText, sourceLang, targetLang)
                    withContext(Dispatchers.Main) {
                        updateSubtitleDisplay(cleanText, translated)
                    }
                }
            } else {
                noTextCount++
                // Automatically hide when dialogue finished (> 2.5 seconds empty)
                if (noTextCount >= 5) {
                    withContext(Dispatchers.Main) {
                        subtitleView?.visibility = View.GONE
                        lastRecognizedText = ""
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore temporary OCR errors
        }
    }

    private fun updateSubtitleDisplay(original: String, translated: String) {
        subtitleView?.findViewById<TextView>(R.id.tvTranslatedGameText)?.text = translated
        subtitleView?.findViewById<TextView>(R.id.tvOriginalGameText)?.text = original

        // Keep subtitle bar neatly above the crop box
        subtitleParams.y = (cropY - 180).coerceAtLeast(60)
        try {
            windowManager.updateViewLayout(subtitleView, subtitleParams)
        } catch (e: Exception) {}

        subtitleView?.visibility = View.VISIBLE
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
                "Game Subtitle Service",
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

        bubbleView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        selectionBoxView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        subtitleView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
