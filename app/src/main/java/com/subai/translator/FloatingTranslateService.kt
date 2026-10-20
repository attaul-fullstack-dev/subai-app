package com.subai.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class FloatingTranslateService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var subtitleView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val translator = MLKitTranslator()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var sourceLang = "en"
    private var targetLang = "id"

    // Real-time automatic scanner
    private var isAutoRealtime = true
    private var autoScanJob: Job? = null
    private var lastRecognizedText = ""
    private var emptyCount = 0

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
        startForeground(101, createNotification())
        getScreenMetrics()
    }

    private fun getScreenMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            sourceLang = it.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
            targetLang = it.getStringExtra(EXTRA_TARGET_LANG) ?: "id"

            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = it.getParcelableExtra(EXTRA_RESULT_DATA)

            if (resultCode != 0 && resultData != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
                setupVirtualDisplay()
                startRealtimeAutoScanner()
            }
        }

        if (bubbleView == null) {
            setupFloatingBubble()
        }

        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupFloatingBubble() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = screenHeight / 3
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = (event.rawX - initialTouchX).toInt()
                    val diffY = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) {
                        isClick = false
                    }
                    params.x = initialX + diffX
                    params.y = initialY + diffY
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        // Toggle Realtime ON / OFF
                        isAutoRealtime = !isAutoRealtime
                        if (isAutoRealtime) {
                            startRealtimeAutoScanner()
                            Toast.makeText(this, "Real-Time Auto Translate: AKTIF", Toast.LENGTH_SHORT).show()
                        } else {
                            autoScanJob?.cancel()
                            hideFloatingSubtitle()
                            Toast.makeText(this, "Real-Time Auto Translate: JEDA", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun startRealtimeAutoScanner() {
        autoScanJob?.cancel()
        autoScanJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isAutoRealtime) {
                try {
                    processScreenForSubtitle()
                    delay(500) // Scan setiap 500ms (Real-Time lancar tanpa lag)
                } catch (e: Exception) {
                    delay(1000)
                }
            }
        }
    }

    private suspend fun processScreenForSubtitle() {
        val frameBitmap = captureLatestFrame() ?: return

        // Crop area subtitle (fokus ke 40% bagian bawah layar tempat teks dialog game berada)
        val startY = (screenHeight * 0.55).toInt().coerceAtMost(screenHeight - 100)
        val cropHeight = (screenHeight - startY)
        val croppedBitmap = try {
            Bitmap.createBitmap(frameBitmap, 0, startY, screenWidth, cropHeight)
        } catch (e: Exception) {
            frameBitmap
        }

        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        try {
            val visionText = textRecognizer.process(inputImage).await()
            val cleanText = visionText.text.trim().replace("\n", " ")

            if (cleanText.length >= 3) {
                emptyCount = 0
                // Hanya terjemahkan jika teks dialog game berubah
                if (cleanText != lastRecognizedText) {
                    lastRecognizedText = cleanText
                    val translated = translator.translate(cleanText, sourceLang, targetLang)
                    withContext(Dispatchers.Main) {
                        showFloatingSubtitle(cleanText, translated)
                    }
                }
            } else {
                emptyCount++
                // Sembunyikan jika tidak ada subtitle game selama 3 detik
                if (emptyCount >= 6) {
                    withContext(Dispatchers.Main) {
                        hideFloatingSubtitle()
                    }
                }
            }
        } catch (e: Exception) {
            // Abaikan error frame sementara
        }
    }

    private fun captureLatestFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
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
        image.close()

        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun showFloatingSubtitle(original: String, translated: String) {
        if (subtitleView == null) {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 60
            }

            subtitleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_subtitle, null)

            subtitleView?.findViewById<View>(R.id.btnCloseSubtitle)?.setOnClickListener {
                hideFloatingSubtitle()
            }

            windowManager.addView(subtitleView, params)
        }

        subtitleView?.findViewById<TextView>(R.id.tvOriginalGameText)?.text = original
        subtitleView?.findViewById<TextView>(R.id.tvTranslatedGameText)?.text = translated
        subtitleView?.visibility = View.VISIBLE
    }

    private fun hideFloatingSubtitle() {
        subtitleView?.visibility = View.GONE
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Translator Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SubAI Real-Time Game Translator Aktif")
            .setContentText("Menerjemahkan subtitle game secara otomatis real-time")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        autoScanJob?.cancel()
        textRecognizer.close()

        bubbleView?.let { windowManager.removeView(it) }
        subtitleView?.let { windowManager.removeView(it) }

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
