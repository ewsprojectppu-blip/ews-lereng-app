package com.ewslereng.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SensorLatest(
    val t: String?,
    val loc: String?,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val sudutX: Double,
    val sudutY: Double,
    val pergeseran: Double,
    val status: String?,
    val baterai: Double,
    val sinyal: Double
)

sealed class FetchResult {
    data class Success(val data: Map<String, SensorLatest>) : FetchResult()
    data class Error(val message: String) : FetchResult()
}

object SensorRepository {

    suspend fun fetchLatest(): FetchResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(Config.APPS_SCRIPT_URL + "?api=data")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code != 200) {
                return@withContext FetchResult.Error("HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val sensorsObj = json.optJSONObject("sensors") ?: JSONObject()

            val map = mutableMapOf<String, SensorLatest>()
            for (id in Config.SENSOR_IDS) {
                val entry = sensorsObj.optJSONObject(id) ?: continue
                val latest = entry.optJSONObject("latest") ?: continue
                map[id] = SensorLatest(
                    t = latest.optString("t", ""),
                    loc = latest.optString("loc", Config.SENSOR_LOKASI[id] ?: ""),
                    ax = latest.optDouble("ax", 0.0),
                    ay = latest.optDouble("ay", 0.0),
                    az = latest.optDouble("az", 0.0),
                    sudutX = latest.optDouble("sudutX", 0.0),
                    sudutY = latest.optDouble("sudutY", 0.0),
                    pergeseran = latest.optDouble("pergeseran", 0.0),
                    status = latest.optString("status", "aman"),
                    baterai = latest.optDouble("baterai", 0.0),
                    sinyal = latest.optDouble("sinyal", 0.0)
                )
            }
            FetchResult.Success(map)
        } catch (e: Exception) {
            FetchResult.Error(e.message ?: "Gagal mengambil data")
        } finally {
            conn?.disconnect()
        }
    }

    /** Status terparah di antara semua sensor yang sudah pernah mengirim data. */
    fun worstStatus(data: Map<String, SensorLatest>): String {
        var worst = "aman"
        var worstSev = 0
        for (s in data.values) {
            val sev = severityOf(s.status)
            if (sev > worstSev) {
                worstSev = sev
                worst = s.status ?: "aman"
            }
        }
        return worst
    }
}
