package com.example.watchsensorlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorLoggingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SensorLoggingService"

        const val ACTION_START_LOGGING = "com.example.watchsensorlogger.START_LOGGING"
        const val ACTION_STOP_LOGGING = "com.example.watchsensorlogger.STOP_LOGGING"

        // 가속도 값 UI 업데이트용 브로드캐스트
        const val ACTION_ACCEL_UPDATE = "com.example.watchsensorlogger.ACCEL_UPDATE"
        const val EXTRA_ACCEL_X = "extra_accel_x"
        const val EXTRA_ACCEL_Y = "extra_accel_y"
        const val EXTRA_ACCEL_Z = "extra_accel_z"

        private const val NOTIFICATION_CHANNEL_ID = "sensor_logging_channel"
        private const val NOTIFICATION_ID = 1001
    }

    // ---- SensorManager 기반 센서 ----
    private var sensorManager: SensorManager? = null
    private val activeSensors: MutableList<Sensor> = mutableListOf()

    // 센서 콜백을 처리할 전용 스레드
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // ---- 로그 파일 ----
    private lateinit var logFile: File
    private var isLogging: Boolean = false

    // 날짜 포맷
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // UI로 브로드캐스트 보내는 주기 (ms)
    private val accelBroadcastIntervalMs = 200L
    private var lastAccelBroadcastTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createLogFile()
        initSensorManager()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LOGGING -> {
                Log.d(TAG, "ACTION_START_LOGGING")
                startLogging()
            }
            ACTION_STOP_LOGGING -> {
                Log.d(TAG, "ACTION_STOP_LOGGING")
                stopLogging()
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopLogging()
        stopSensorThread()
    }

    // ─────────────────────────────────────────────
    //  포그라운드 서비스 Notification
    // ─────────────────────────────────────────────
    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sensor Logging",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Logging watch sensor data"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Sensor logging in progress")
            .setContentText("Collecting accelerometer data…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ─────────────────────────────────────────────
    //  로그 파일 생성 & 쓰기 (/files/sensorLog)
    // ─────────────────────────────────────────────
    private fun createLogFile() {
        val baseExternal = getExternalFilesDir(null) ?: filesDir
        val baseDir = File(baseExternal, "sensorLog")

        if (!baseDir.exists()) {
            val ok = baseDir.mkdirs()
            Log.d(TAG, "sensorLog dir created: $ok, path=${baseDir.absolutePath}")
        }

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "sensor_log_${sdf.format(Date())}.csv"
        logFile = File(baseDir, fileName)

        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
                appendCsvLine("timestamp,source,sensor_type,value1,value2,value3,extra")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create log file", e)
            }
        }

        Log.d(TAG, "Logging file: ${logFile.absolutePath}")
    }

    private fun appendCsvLine(line: String) {
        try {
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(line)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log line", e)
        }
    }

    private fun nowString(): String = timeFormatter.format(Date())

    // ─────────────────────────────────────────────
    //  일반 Android 센서 (SensorManager)
    // ─────────────────────────────────────────────
    private fun initSensorManager() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    private fun startSensorThreadIfNeeded() {
        if (sensorThread != null && sensorThread!!.isAlive) return

        sensorThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorThread!!.looper)
        Log.d(TAG, "SensorThread started")
    }

    private fun stopSensorThread() {
        try {
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
            Log.d(TAG, "SensorThread stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SensorThread", e)
        }
    }

    private fun registerSystemSensors() {
        val sm = sensorManager ?: return

        startSensorThreadIfNeeded()
        val handler = sensorHandler

        activeSensors.clear()

        val types = listOf(
            Sensor.TYPE_ACCELEROMETER,          // 1
            Sensor.TYPE_GYROSCOPE,              // 4
            Sensor.TYPE_LINEAR_ACCELERATION,    // 10
            Sensor.TYPE_GRAVITY,                // 9
            Sensor.TYPE_MAGNETIC_FIELD,         // 2
            Sensor.TYPE_ROTATION_VECTOR,        // 11
            Sensor.TYPE_STEP_COUNTER,            // 19
            Sensor.TYPE_HEART_RATE
        )

        for (type in types) {
            val sensor = sm.getDefaultSensor(type)
            if (sensor != null) {
                if (handler != null) {
                    // ★ 센서 콜백을 별도의 스레드에서 받도록 등록
                    sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                } else {
                    // (fallback) 메인 스레드 – 이 경우는 거의 없도록 설계
                    sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                }
                activeSensors.add(sensor)
                Log.d(TAG, "Registered system sensor: ${sensor.name} (type=$type)")
            } else {
                Log.d(TAG, "System sensor not available: type=$type")
            }
        }
    }

    private fun unregisterSystemSensors() {
        sensorManager?.unregisterListener(this)
        activeSensors.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return

        val sensorType = event.sensor.type
        val sensorName = sensorTypeToName(sensorType)

        val values = event.values
        val v0 = values.getOrNull(0) ?: Float.NaN
        val v1 = values.getOrNull(1) ?: Float.NaN
        val v2 = values.getOrNull(2) ?: Float.NaN

        // CSV 로깅
        // 센서 타입에 따라 로깅되는 값의 개수가 다름
        val line = when (sensorType) {
            // 1-value sensors
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_STEP_COUNTER ->
                "${nowString()},android_sensor,$sensorName,$v0,,,"
            // 3-value sensors
            else ->
                "${nowString()},android_sensor,$sensorName,$v0,$v1,$v2,"
        }
        appendCsvLine(line)

        // ACCELEROMETER 실시간 UI 로 전송 (줄임)
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAccelBroadcastTime >= accelBroadcastIntervalMs) {
                lastAccelBroadcastTime = now

                val intent = Intent(ACTION_ACCEL_UPDATE).apply {
                    putExtra(EXTRA_ACCEL_X, v0)
                    putExtra(EXTRA_ACCEL_Y, v1)
                    putExtra(EXTRA_ACCEL_Z, v2)
                }
                sendBroadcast(intent)
            }
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 필요하면 accuracy 로그
    }

    // ─────────────────────────────────────────────
    //  Logging 제어
    // ─────────────────────────────────────────────
    private fun startLogging() {
        if (isLogging) return

        isLogging = true
        registerSystemSensors()

        Log.d(TAG, "Logging started")
    }

    private fun stopLogging() {
        if (!isLogging) return

        isLogging = false
        unregisterSystemSensors()

        Log.d(TAG, "Final log file saved at: ${logFile.absolutePath}")
        Log.d(TAG, "Logging stopped")
    }

    private fun sensorTypeToName(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "ACCELEROMETER"
            Sensor.TYPE_GYROSCOPE -> "GYROSCOPE"
            Sensor.TYPE_LINEAR_ACCELERATION -> "LINEAR_ACCELERATION"
            Sensor.TYPE_GRAVITY -> "GRAVITY"
            Sensor.TYPE_MAGNETIC_FIELD -> "MAGNETIC_FIELD"
            Sensor.TYPE_ROTATION_VECTOR -> "ROTATION_VECTOR"
            Sensor.TYPE_STEP_COUNTER -> "STEP_COUNTER"
            Sensor.TYPE_HEART_RATE -> "HEART_RATE"
            else -> "UNKNOWN($type)"
        }
    }
}
