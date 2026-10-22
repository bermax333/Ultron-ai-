package de.ultron.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MicRelayService : Service() {
    companion object {
        const val EXTRA_URL = "mic_relay_url"
        const val EXTRA_TOKEN = "mic_relay_token"
        const val ACTION_STATUS = "de.ultron.relay.MIC_STATUS"
        const val EXTRA_STATUS = "status_message"
        private const val CHANNEL_ID = "ultron_mic_relay"
        private const val NOTIFICATION_ID = 4702
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 8_192
    }

    private val running = AtomicBoolean(false)
    private val sender = Executors.newSingleThreadExecutor()
    private var recorder: AudioRecord? = null
    private var baseUrl = ""
    private var authToken = ""
    private var networkFailureReported = false
    private var lastNetworkReportAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        baseUrl = intent?.getStringExtra(EXTRA_URL).orEmpty().trimEnd('/')
        authToken = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (baseUrl.isBlank() || authToken.isBlank() ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            reportStatus("Mikrofonstart abgebrochen: ULTRON-Verbindung, Code oder Mikrofonfreigabe prüfen.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (!running.compareAndSet(false, true)) return START_NOT_STICKY
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) throw IllegalStateException("Android unterstützt die Mikrofonaufnahme nicht.")
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, CHUNK_SAMPLES * 2) * 2
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Android konnte das Mikrofon nicht öffnen.")
            }
            recorder?.startRecording()
            reportStatus("Handy-Mikrofon aktiv. ULTRON-PC-Seite öffnen und „PC-Mikrofon hören“ starten.")
            sender.execute { captureAndSend() }
        } catch (error: Exception) {
            running.set(false)
            reportStatus("Mikrofon konnte nicht gestartet werden: ${error.message ?: "Android-Berechtigung oder Gerät prüfen"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ULTRON Mikrofonstream", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ULTRON überträgt das Handy-Mikrofon")
            .setContentText("Tippe in der ULTRON-PC-Seite auf „PC-Mikrofon hören“, um den Ton abzuspielen.")
            .setOngoing(true)
            .build()
    }

    private fun captureAndSend() {
        val pcm = ShortArray(CHUNK_SAMPLES)
        try {
            while (running.get()) {
                var collected = 0
                while (collected < pcm.size && running.get()) {
                    val count = recorder?.read(pcm, collected, pcm.size - collected, AudioRecord.READ_BLOCKING) ?: -1
                    if (count > 0) collected += count
                    else if (count < 0) {
                        reportStatus("Android hat die Mikrofonaufnahme beendet (AudioRecord $count).")
                        running.set(false)
                        break
                    }
                }
                if (collected == pcm.size && running.get()) {
                    try {
                        sendChunk(toWav(pcm))
                        if (networkFailureReported) reportStatus("Mikrofonverbindung wiederhergestellt.")
                        networkFailureReported = false
                    } catch (error: Exception) {
                        val now = System.currentTimeMillis()
                        if (!networkFailureReported || now - lastNetworkReportAt >= 3000) {
                            reportStatus("Mikrofon erreicht den PC nicht: ${error.message ?: "WLAN/Pairing prüfen"}")
                            lastNetworkReportAt = now
                        }
                        networkFailureReported = true
                    }
                }
            }
        } catch (error: Exception) {
            reportStatus("Mikrofonaufnahme fehlgeschlagen: ${error.message ?: "Android-Audio prüfen"}")
        } finally {
            running.set(false)
            stopSelf()
        }
    }

    private fun sendChunk(wav: ByteArray) {
        val connection = (URL("$baseUrl/api/mic").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 2_000
            doOutput = true
            setFixedLengthStreamingMode(wav.size)
            setRequestProperty("Authorization", "Bearer $authToken")
            setRequestProperty("Content-Type", "audio/wav")
        }
        try {
            connection.outputStream.use { it.write(wav) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
                throw IllegalStateException("PC antwortet mit HTTP $code${if (detail.isBlank()) "" else ": $detail"}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun reportStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun toWav(samples: ShortArray): ByteArray {
        val pcmBytes = samples.size * 2
        val wav = ByteArray(44 + pcmBytes)
        fun writeAscii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(wav, offset)
        }
        fun writeInt(offset: Int, value: Int) {
            for (index in 0 until 4) wav[offset + index] = (value ushr (index * 8)).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            wav[offset] = (value and 0xff).toByte()
            wav[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        writeAscii(0, "RIFF")
        writeInt(4, 36 + pcmBytes)
        writeAscii(8, "WAVE")
        writeAscii(12, "fmt ")
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, 1)
        writeInt(24, SAMPLE_RATE)
        writeInt(28, SAMPLE_RATE * 2)
        writeShort(32, 2)
        writeShort(34, 16)
        writeAscii(36, "data")
        writeInt(40, pcmBytes)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            wav[44 + index * 2] = (value and 0xff).toByte()
            wav[45 + index * 2] = ((value ushr 8) and 0xff).toByte()
        }
        return wav
    }

    override fun onDestroy() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        sender.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
