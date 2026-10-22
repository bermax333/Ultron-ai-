package de.ultron.relay

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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRelayService : Service() {
    companion object {
        const val EXTRA_URL = "relay_url"
        const val EXTRA_TOKEN = "relay_token"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "ultron_screen_relay"
        private const val NOTIFICATION_ID = 4701
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var baseUrl = ""
    private var authToken = ""
    private var lastSentAt = 0L
    private val sending = AtomicBoolean(false)
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        baseUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trimEnd('/')
        authToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (baseUrl.isBlank() || authToken.isBlank() || resultData == null) { stopSelf(); return START_NOT_STICKY }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        beginProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ULTRON Bildschirmstream", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("ULTRON überträgt den Bildschirm")
            .setContentText("Wechsle zu DJI Fly. Tippe auf Stream stoppen, um die Übertragung zu beenden.")
            .setOngoing(true)
            .build()
    }

    private fun beginProjection(resultCode: Int, resultData: Intent) {
        try {
            worker = HandlerThread("ultron-screen-capture").also { it.start() }
            handler = Handler(worker!!.looper)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            val metrics = resources.displayMetrics
            val ratio = minOf(1.0, 960.0 / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = (metrics.widthPixels * ratio).toInt().coerceAtLeast(2)
            val height = (metrics.heightPixels * ratio).toInt().coerceAtLeast(2)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ imageReader -> captureFrame(imageReader) }, handler)
            display = projection?.createVirtualDisplay(
                "ULTRON DJI Fly Relay", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, handler
            )
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun captureFrame(imageReader: ImageReader) {
        val image: Image = try { imageReader.acquireLatestImage() ?: return } catch (_: Exception) { return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAt < 250 || !sending.compareAndSet(false, true)) { image.close(); return }
        lastSentAt = now
        val jpeg = try { imageToJpeg(image) } catch (_: Exception) { null } finally { image.close() }
        if (jpeg == null) { sending.set(false); return }
        uploadExecutor.execute {
            try {
                val connection = (URL("$baseUrl/api/frame").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $authToken")
                    setRequestProperty("Content-Type", "image/jpeg")
                    setFixedLengthStreamingMode(jpeg.size)
                }
                connection.outputStream.use { it.write(jpeg) }
                connection.responseCode
                connection.disconnect()
            } catch (_: Exception) {
                // A dropped frame is harmless; the next screen frame retries.
            } finally {
                sending.set(false)
            }
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val maxSide = 960
        val scale = minOf(1f, maxSide.toFloat() / maxOf(cropped.width, cropped.height))
        val scaledWidth = (cropped.width * scale).toInt().coerceAtLeast(2)
        val scaledHeight = (cropped.height * scale).toInt().coerceAtLeast(2)
        val output = if (scaledWidth == cropped.width && scaledHeight == cropped.height) cropped
            else Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true)
        val jpeg = ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, 62, stream)
            stream.toByteArray()
        }
        if (output !== cropped) output.recycle()
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        return jpeg
    }

    override fun onDestroy() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        worker?.quitSafely(); worker = null; handler = null
        uploadExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
