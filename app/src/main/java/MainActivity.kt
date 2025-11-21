package com.example.watchsensorlogger

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvAccel: TextView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // 앱에 필요한 권한 목록
    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS
    )

    // 서비스에서 보낸 가속도 데이터 수신용 리시버
    private val accelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SensorLoggingService.ACTION_ACCEL_UPDATE) return

            val ax = intent.getFloatExtra(SensorLoggingService.EXTRA_ACCEL_X, Float.NaN)
            val ay = intent.getFloatExtra(SensorLoggingService.EXTRA_ACCEL_Y, Float.NaN)
            val az = intent.getFloatExtra(SensorLoggingService.EXTRA_ACCEL_Z, Float.NaN)

            if (!ax.isNaN() && !ay.isNaN() && !az.isNaN()) {
                tvAccel.text = String.format("ACC\nx=%.3f\ny=%.3f\nz=%.3f", ax, ay, az)
            }
        }
    }

    private fun updateButtonColors(isLogging: Boolean) {
        if (isLogging) {
            btnStart.setBackgroundColor(Color.RED)
            btnStop.setBackgroundColor(Color.DKGRAY)
            tvAccel.text = "Log Start"
        } else {
            btnStart.setBackgroundColor(Color.DKGRAY)
            btnStop.setBackgroundColor(Color.RED)
            tvAccel.text = "Log End"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btn_start_logging)
        btnStop = findViewById(R.id.btn_stop_logging)
        tvAccel = findViewById(R.id.tvHeartRate)

        // 권한이 부여될 때까지 버튼 비활성화
        btnStart.isEnabled = false
        btnStop.isEnabled = false

        if (!hasPermissions()) {
            requestPermissions()
        } else {
            // 이미 권한이 있는 경우 버튼 활성화
            btnStart.isEnabled = true
            btnStop.isEnabled = true
        }

        updateButtonColors(isLogging = false)

        val filter = IntentFilter(SensorLoggingService.ACTION_ACCEL_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(accelReceiver, filter)
        }

        btnStart.setOnClickListener {
            val intent = Intent(this, SensorLoggingService::class.java).apply {
                action = SensorLoggingService.ACTION_START_LOGGING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateButtonColors(isLogging = true)
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, SensorLoggingService::class.java).apply {
                action = SensorLoggingService.ACTION_STOP_LOGGING
            }
            startService(intent)
            updateButtonColors(isLogging = false)
        }
    }

    // 필요한 모든 권한이 있는지 확인
    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 권한 요청
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    // 권한 요청 결과 처리
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 권한이 부여되면 버튼 활성화
                btnStart.isEnabled = true
                btnStop.isEnabled = true
            } else {
                // 권한이 거부되면 버튼을 비활성화 상태로 유지하고 메시지 표시
                btnStart.isEnabled = false
                btnStop.isEnabled = false
                tvAccel.text = "BODY_SENSORS permission denied. Cannot start logging."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(accelReceiver)
    }
}