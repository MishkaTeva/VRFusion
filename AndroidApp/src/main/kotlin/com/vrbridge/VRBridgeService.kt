package com.vrbridge

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class VRBridgeService : Service(), SensorEventListener {
    private lateinit var sensorMgr: SensorManager
    private var imuSocket: DatagramSocket? = null
    private val remoteAddr: InetAddress = InetAddress.getByName("192.168.31.2") // ПК IP
    private val imuPort = 6000
    private val audioPort = 6002
    private var audioSocket: DatagramSocket? = null
    private var isAudioRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.i("VRBridge", "Service started")
        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorMgr.registerListener(
            this,
            sensorMgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorMgr.registerListener(
            this,
            sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_FASTEST
        )
        imuSocket = DatagramSocket()
        startAudioStreaming()
    }

    // ---------------------- IMU ----------------------
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE && event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Формат: 8 байт timestamp + 3 float gyro + 3 float accel (we send both messages concatenated)
        val buffer = ByteArray(8 + 12 + 12)
        var pos = 0
        var ts = event.timestamp
        for (i in 7 downTo 0) {
            buffer[pos++] = (ts ushr (i * 8)).toByte()
        }
        // gyro first, then accel – we fill zeros for missing part
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            for (i in 0..2) {
                val v = event.values[i]
                val bits = java.lang.Float.floatToIntBits(v)
                for (b in 3 downTo 0) buffer[pos++] = (bits ushr (b * 8)).toByte()
            }
            // pad accel with zeros
            repeat(12) { buffer[pos++] = 0 }
        } else { // accelerometer
            // pad gyro with zeros
            repeat(12) { buffer[pos++] = 0 }
            for (i in 0..2) {
                val v = event.values[i]
                val bits = java.lang.Float.floatToIntBits(v)
                for (b in 3 downTo 0) buffer[pos++] = (bits ushr (b * 8)).toByte()
            }
        }
        val pkt = DatagramPacket(buffer, buffer.size, remoteAddr, imuPort)
        imuSocket?.send(pkt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------- Audio ----------------------
    private fun startAudioStreaming() {
        val sampleRate = 48000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        audioSocket = DatagramSocket()
        Thread {
            recorder.startRecording()
            val buf = ByteArray(1024)
            while (true) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    val pkt = DatagramPacket(buf, read, remoteAddr, audioPort)
                    audioSocket?.send(pkt)
                }
            }
        }.start()
        isAudioRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorMgr.unregisterListener(this)
        imuSocket?.close()
        audioSocket?.close()
        Log.i("VRBridge", "Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
