package de.ultron.relay

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PointCloudActivity : Activity() {
    private lateinit var modelUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriText = intent.getStringExtra(EXTRA_MODEL_URI)
        if (uriText.isNullOrBlank()) {
            finish()
            return
        }
        modelUri = Uri.parse(uriText)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 16)
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        root.addView(TextView(this).apply {
            text = "ULTRON · 3D-Karte"
            textSize = 23f
            setTextColor(Color.rgb(244, 60, 79))
        })
        root.addView(TextView(this).apply {
            text = "Mit einem Finger drehen · mit zwei Fingern zoomen"
            textSize = 13f
            setTextColor(Color.rgb(190, 202, 214))
            setPadding(0, 8, 0, 8)
        })
        val surface = FrameLayout(this)
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply {
            text = "PLY teilen / auf PC übertragen"
            setOnClickListener {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(android.content.Intent.EXTRA_STREAM, modelUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(send, "3D-Karte teilen"))
            }
        })
        root.addView(buttons)
        setContentView(root)
        val loading = TextView(this).apply {
            text = "3D-Datei wird geöffnet …"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        surface.addView(loading, FrameLayout.LayoutParams(-1, -1))
        Thread {
            try {
                val points = readAsciiPly(this, modelUri)
                runOnUiThread {
                    surface.removeAllViews()
                    surface.addView(PointCloudView(this, points), FrameLayout.LayoutParams(-1, -1))
                    Toast.makeText(this, "${points.size} 3D-Punkte geladen", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading.text = "PLY konnte nicht geöffnet werden: ${error.message ?: "Datei prüfen"}"
                }
            }
        }.start()
    }

    private fun readAsciiPly(context: Context, uri: Uri): List<RenderPoint> {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Datei kann nicht gelesen werden")
        stream.use {
            val reader = BufferedReader(InputStreamReader(it, Charsets.UTF_8))
            var ascii = false
            var vertexCount = 0
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line == "format ascii 1.0") ascii = true
                if (line.startsWith("element vertex ")) vertexCount = line.substringAfterLast(' ').toIntOrNull() ?: 0
                if (line == "end_header") break
            }
            if (!ascii) error("Nur ASCII-PLY-Dateien werden in der Handy-Ansicht unterstützt")
            if (vertexCount <= 0) error("Die PLY-Datei enthält keine Punktzahl im Header")
            val result = ArrayList<RenderPoint>(min(vertexCount, 90_000))
            val stride = max(1, vertexCount / 90_000)
            var vertexIndex = 0
            while (vertexIndex < vertexCount) {
                line = reader.readLine() ?: break
                val parts = line!!.trim().split(Regex("\\s+"))
                if (vertexIndex % stride == 0 && parts.size >= 6) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    val z = parts[2].toFloatOrNull()
                    if (x != null && y != null && z != null) {
                        val red = parts[3].toIntOrNull()?.coerceIn(0, 255) ?: 255
                        val green = parts[4].toIntOrNull()?.coerceIn(0, 255) ?: 255
                        val blue = parts[5].toIntOrNull()?.coerceIn(0, 255) ?: 255
                        result.add(RenderPoint(x, y, z, Color.rgb(red, green, blue)))
                    }
                }
                vertexIndex++
            }
            if (result.size < 10) error("Die Datei enthält keine ausreichende Punktwolke")
            return result
        }
    }

    companion object {
        const val EXTRA_MODEL_URI = "de.ultron.relay.MODEL_URI"
    }
}

private data class RenderPoint(val x: Float, val y: Float, val z: Float, val color: Int)

private class PointCloudView(context: Context, points: List<RenderPoint>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloud = points
    private val centerX = points.map { it.x }.average().toFloat()
    private val centerY = points.map { it.y }.average().toFloat()
    private val centerZ = points.map { it.z }.average().toFloat()
    private val extent = max(
        points.maxOf { it.x } - points.minOf { it.x },
        max(points.maxOf { it.y } - points.minOf { it.y }, points.maxOf { it.z } - points.minOf { it.z })
    ).coerceAtLeast(0.001f)
    private var yaw = 0.65f
    private var pitch = 0.25f
    private var zoom = 1f
    private var previousX = 0f
    private var previousY = 0f
    private var previousPinch = 0f
    private var bitmap: Bitmap? = null
    private var pixelBuffer = IntArray(0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(8, 11, 16))
        if (width <= 0 || height <= 0) return
        if (bitmap == null || bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixelBuffer = IntArray(width * height)
        }
        pixelBuffer.fill(Color.rgb(8, 11, 16))
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val scale = min(width, height) * 0.85f * zoom
        val cameraDistance = 2.8f
        val step = max(1, cloud.size / 90_000)
        var index = 0
        while (index < cloud.size) {
            val point = cloud[index]
            val x = (point.x - centerX) / extent
            val y = (point.y - centerY) / extent
            val z = (point.z - centerZ) / extent
            val rotatedX = x * cosYaw - z * sinYaw
            val rotatedZ = x * sinYaw + z * cosYaw
            val rotatedY = y * cosPitch - rotatedZ * sinPitch
            val depth = y * sinPitch + rotatedZ * cosPitch + cameraDistance
            if (depth > 0.02f) {
                val screenX = (width * 0.5f + rotatedX * scale / depth).toInt()
                val screenY = (height * 0.5f - rotatedY * scale / depth).toInt()
                if (screenX in 0 until width && screenY in 0 until height) {
                    val offset = screenY * width + screenX
                    pixelBuffer[offset] = point.color
                    if (screenX + 1 < width) pixelBuffer[offset + 1] = point.color
                }
            }
            index += step
        }
        bitmap?.setPixels(pixelBuffer, 0, width, 0, 0, width, height)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        paint.color = Color.rgb(130, 145, 160)
        paint.textSize = 13f * resources.displayMetrics.density
        canvas.drawText("${cloud.size} Punkte · relative Größe", 16f, height - 18f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) previousPinch = distance(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val currentPinch = distance(event)
                    if (previousPinch > 0f) zoom = (zoom * currentPinch / previousPinch).coerceIn(0.35f, 5f)
                    previousPinch = currentPinch
                } else {
                    yaw += (event.x - previousX) * 0.008f
                    pitch = (pitch + (event.y - previousY) * 0.008f).coerceIn(-1.45f, 1.45f)
                    previousX = event.x
                    previousY = event.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                previousPinch = 0f
                return true
            }
        }
        return true
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
