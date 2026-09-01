package com.ewslereng.app

object Config {
    // URL Web App Apps Script yang sama dengan yang dipakai dashboard & ESP32
    const val APPS_SCRIPT_URL =
        "https://script.google.com/macros/s/AKfycbwtVNJSKvX-1NGhpWPHsWdeAzRpd-mZNdNCtqygf3Z0DsKd3NX23OD0ZlU8WKIP5XCh/exec"

    // Sesuai layout 6 sensor di Dashboard.html
    val SENSOR_IDS = listOf("S1", "S2", "S3", "S4", "S5", "S6")
    val SENSOR_LOKASI = mapOf(
        "S1" to "Tengah Bawah - Kiri",
        "S2" to "Tengah Bawah - Kanan",
        "S3" to "Tengah Atas - Kiri",
        "S4" to "Tengah Atas - Kanan",
        "S5" to "Puncak - Kiri",
        "S6" to "Puncak - Kanan"
    )

    // Jeda pengecekan data di background service (mengikuti kecepatan ESP32 mengirim: 1 menit saat waspada+)
    const val POLL_INTERVAL_SERVICE_MS = 60_000L
    // Jeda refresh tampilan saat aplikasi sedang dibuka (lebih sering, untuk UI yang responsif)
    const val POLL_INTERVAL_UI_MS = 15_000L
}

fun severityOf(status: String?): Int = when (status) {
    "bahaya" -> 3
    "siaga" -> 2
    "waspada" -> 1
    else -> 0
}

fun labelOf(status: String?): String = when (status) {
    "bahaya" -> "BAHAYA"
    "siaga" -> "SIAGA"
    "waspada" -> "WASPADA"
    "aman" -> "AMAN"
    else -> "MENUNGGU DATA"
}
