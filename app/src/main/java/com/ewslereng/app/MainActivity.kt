package com.ewslereng.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ewslereng.app.databinding.ActivityMainBinding
import com.ewslereng.app.databinding.ItemSensorBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var uiPollJob: Job? = null
    private val sensorViews = mutableMapOf<String, ItemSensorBinding>()

    private val dataUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Service sudah mendeteksi perubahan; Activity cukup refresh tampilannya sendiri
            refreshNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildSensorCards()
        requestNotificationPermissionIfNeeded()
        startMonitoringService()

        binding.btnRefresh.setOnClickListener { refreshNow() }
        binding.btnSilence.setOnClickListener {
            val intent = Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_SILENCE)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            dataUpdatedReceiver, IntentFilter(MonitoringService.ACTION_DATA_UPDATED)
        )
        uiPollJob = lifecycleScope.launch {
            while (isActive) {
                refreshNow()
                delay(Config.POLL_INTERVAL_UI_MS)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        uiPollJob?.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataUpdatedReceiver)
    }

    private fun buildSensorCards() {
        binding.containerSensors.removeAllViews()
        sensorViews.clear()
        for (id in Config.SENSOR_IDS) {
            val itemBinding = ItemSensorBinding.inflate(LayoutInflater.from(this), binding.containerSensors, false)
            itemBinding.tvSensorLoc.text = "$id (${Config.SENSOR_LOKASI[id]})"
            binding.containerSensors.addView(itemBinding.root)
            sensorViews[id] = itemBinding
        }
    }

    private fun refreshNow() {
        lifecycleScope.launch {
            when (val result = SensorRepository.fetchLatest()) {
                is FetchResult.Success -> renderData(result.data)
                is FetchResult.Error -> {
                    binding.tvStatusDesc.text = "Gagal mengambil data: ${result.message}"
                }
            }
        }
    }

    private fun renderData(data: Map<String, SensorLatest>) {
        val worst = SensorRepository.worstStatus(data)
        val color = colorFor(worst)

        binding.tvStatusLabel.text = labelOf(worst)
        binding.tvStatusLabel.setTextColor(color)
        binding.tvStatusDesc.text = when (worst) {
            "aman" -> "Tidak ada potensi longsor, kondisi lereng dalam batas aman"
            "waspada" -> "Terdeteksi pergeseran ringan, perlu pemantauan lebih sering"
            "siaga" -> "Pergeseran signifikan terdeteksi, segera periksa lapangan"
            "bahaya" -> "Pergeseran melewati ambang bahaya, segera ambil tindakan"
            else -> "Menunggu data pertama dari sensor lapangan"
        }
        binding.tvLastUpdate.text = "Update terakhir: ${data.values.maxOfOrNull { it.t ?: "" } ?: "-"}"

        for (id in Config.SENSOR_IDS) {
            val view = sensorViews[id] ?: continue
            val s = data[id]
            if (s == null) {
                view.tvSensorStatus.text = "Menunggu data"
                view.tvSensorStatus.setBackgroundColor(Color.parseColor("#1B2436"))
                view.tvSensorStatus.setTextColor(Color.parseColor("#5B6479"))
                view.tvSensorDetail.text = "Belum ada data masuk dari unit ini"
            } else {
                view.tvSensorStatus.text = labelOf(s.status)
                view.tvSensorStatus.setBackgroundColor(colorFor(s.status) and 0x33FFFFFF)
                view.tvSensorStatus.setTextColor(colorFor(s.status))
                view.tvSensorDetail.text = "Pergeseran: ${"%.2f".format(s.pergeseran)}°   |   Update: ${s.t}"
            }
        }
    }

    private fun colorFor(status: String?): Int = when (status) {
        "bahaya" -> Color.parseColor("#EF4444")
        "siaga" -> Color.parseColor("#F0900A")
        "waspada" -> Color.parseColor("#F0B90B")
        "aman" -> Color.parseColor("#22C55E")
        else -> Color.parseColor("#8B95AC")
    }

    private fun startMonitoringService() {
        val intent = Intent(this, MonitoringService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }
}
