package de.ultron.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var serverField: EditText
    private lateinit var pinField: EditText
    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var videoButton: Button
    private var token: String? = null
    private var baseUrl: String = ""

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            status.text = "QR-Scan abgebrochen."
        } else {
            try {
                val payload = JSONObject(contents)
                val url = payload.optString("url").trim().trimEnd('/')
                val pin = payload.optString("pin").trim()
                if (payload.optString("type") != "ultron-pair-v1" || !isAllowedServerUrl(url) || pin.length != 6) {
                    status.text = "Das ist kein gültiger ULTRON-Kopplungs-QR-Code."
                } else {
                    serverField.setText(url)
                    pinField.setText(pin)
                    pair()
                }
            } catch (_: Exception) {
                status.text = "QR-Code konnte nicht gelesen werden."
            }
        }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uploadVideo(uri)
    }

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenRelayService::class.java).apply {
                putExtra(ScreenRelayService.EXTRA_URL, baseUrl)
                putExtra(ScreenRelayService.EXTRA_TOKEN, token)
                putExtra(ScreenRelayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRelayService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            status.text = "Bildschirmfreigabe läuft. Wechsle jetzt zu DJI Fly."
        } else {
            status.text = "Bildschirmfreigabe abgebrochen. ULTRON erhält kein Bild."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xff080b10.toInt())
        }
        fun label(text: String, size: Float, color: Int = 0xffedf3f8.toInt()) =
            TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
        root.addView(label("ULTRON", 26f, 0xfff43c4f.toInt()))
        root.addView(label("DJI FLY · SICHERER BILDSCHIRM-RELAY", 12f, 0xffa9b5c1.toInt()))
        root.addView(label("Verbinde PC und Handy mit demselben privaten WLAN und scanne den QR-Code am PC.", 14f).apply {
            setPadding(0, 24, 0, 12)
        })
        serverField = EditText(this).apply {
            hint = "http://192.168.1.23:8787 oder https://…ts.net"
            setSingleLine(true)
            setTextColor(0xffedf3f8.toInt())
            setHintTextColor(0xff8895a2.toInt())
        }
        root.addView(serverField, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        scanButton = Button(this).apply {
            text = "QR-Code scannen"
            setOnClickListener {
                qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("ULTRON-QR-Code am PC scannen").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        root.addView(scanButton)
        pinField = EditText(this).apply {
            hint = "6-stelliger Code vom ULTRON-PC"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(0xffedf3f8.toInt())
            setHintTextColor(0xff8895a2.toInt())
        }
        root.addView(pinField, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        pairButton = Button(this).apply { text = "Mit ULTRON koppeln"; setOnClickListener { pair() } }
        root.addView(pairButton)
        startButton = Button(this).apply { text = "DJI-Fly-Bild freigeben"; isEnabled = false; setOnClickListener { requestCapture() } }
        root.addView(startButton)
        videoButton = Button(this).apply {
            text = "Neo-Video für 3D an ULTRON senden"
            isEnabled = false
            setOnClickListener { videoPicker.launch(arrayOf("video/mp4", "video/quicktime")) }
        }
        root.addView(videoButton)
        root.addView(label("Nach dem Flug: Video in DJI Fly aufs Handy laden, hier auswählen und an den PC senden. Die 3D-Rekonstruktion läuft lokal am PC.", 12f, 0xffa9b5c1.toInt()).apply { setPadding(0, 6, 0, 4) })
        root.addView(Button(this).apply {
            text = "Stream stoppen"
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenRelayService::class.java)); status.text = "Stream gestoppt." }
        })
        status = label("Noch nicht gekoppelt. Das Bild wird erst nach deiner Android-Freigabe übertragen.", 13f, 0xffa9b5c1.toInt()).apply {
            setPadding(0, 16, 0, 0)
        }
        root.addView(status)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun isAllowedServerUrl(rawUrl: String): Boolean {
        val uri = try { URI(rawUrl) } catch (_: Exception) { return false }
        val host = uri.host ?: return false
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val octets = host.split(".").map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    private fun pair() {
        val url = serverField.text.toString().trim().trimEnd('/')
        val pin = pinField.text.toString().trim()
        if (!isAllowedServerUrl(url) || pin.length != 6) {
            status.text = "Bitte den QR-Code scannen oder eine private WLAN-/HTTPS-Adresse und den sechsstelligen Code eingeben."
            return
        }
        pairButton.isEnabled = false
        status.text = "Verbinde …"
        io.execute {
            try {
                val connection = (URL("$url/api/pair").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(JSONObject().put("pin", pin).toString().toByteArray(StandardCharsets.UTF_8)) }
                val response = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val json = JSONObject(response.bufferedReader().use { it.readText() })
                connection.disconnect()
                runOnUiThread {
                    val newToken = json.optString("token")
                    if (newToken.isNotBlank()) {
                        token = newToken
                        baseUrl = url
                        startButton.isEnabled = true
                        videoButton.isEnabled = true
                        pinField.text.clear()
                        status.text = "Gekoppelt. Tippe auf „DJI-Fly-Bild freigeben“ und bestätige Androids Bildschirmfreigabe."
                    } else status.text = json.optString("error", "Kopplung fehlgeschlagen.")
                    pairButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Verbindung fehlgeschlagen: ${error.message ?: "Server nicht erreichbar"}"
                    pairButton.isEnabled = true
                }
            }
        }
    }

    private fun uploadVideo(uri: Uri) {
        val currentToken = token
        if (currentToken == null || baseUrl.isBlank()) { status.text = "Kopple zuerst mit ULTRON."; return }
        videoButton.isEnabled = false
        status.text = "Neo-Video wird sicher zum PC übertragen …"
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                val mime = contentResolver.getType(uri) ?: "video/mp4"
                val uploadConnection = (URL(baseUrl + "/api/video-phone").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    doOutput = true
                    setChunkedStreamingMode(256 * 1024)
                    setRequestProperty("Content-Type", mime)
                    setRequestProperty("Authorization", "Bearer " + currentToken)
                }
                connection = uploadConnection
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Videodatei kann nicht geöffnet werden.")
                input.use { source ->
                    uploadConnection.outputStream.use { output -> source.copyTo(output, 256 * 1024) }
                }
                val code = uploadConnection.responseCode
                val response = if (code in 200..299) uploadConnection.inputStream else uploadConnection.errorStream
                val json = JSONObject(response.bufferedReader().use { it.readText() })
                runOnUiThread {
                    if (code in 200..299) status.text = "Video am PC angekommen. ULTRON erstellt jetzt das 3D-Modell."
                    else status.text = json.optString("error", "Video-Upload fehlgeschlagen.")
                    videoButton.isEnabled = token != null
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Video-Upload fehlgeschlagen: " + (error.message ?: "Verbindung prüfen")
                    videoButton.isEnabled = token != null
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun requestCapture() {
        if (token == null) { status.text = "Kopple zuerst mit ULTRON."; return }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
