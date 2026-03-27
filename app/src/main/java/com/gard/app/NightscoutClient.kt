package com.gard.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.*
import kotlin.concurrent.thread

class NightscoutClient(private var baseUrl: String, private var apiSecret: String) {

    fun updateConfig(url: String, secret: String) {
        this.baseUrl = url.trimEnd('/')
        this.apiSecret = secret
    }

    private fun getHashedSecret(): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(apiSecret.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun uploadGlucose(glucose: Int, timestamp: Long) {
        if (baseUrl.isBlank()) return
        
        thread {
            try {
                val url = URL("$baseUrl/api/v1/entries")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("api-secret", getHashedSecret())
                conn.doOutput = true

                val entry = JSONObject().apply {
                    put("type", "sgv")
                    put("sgv", glucose)
                    put("date", timestamp)
                    put("direction", "None") // Trend not always available
                }
                
                val array = JSONArray().put(entry)
                
                OutputStreamWriter(conn.outputStream).use { it.write(array.toString()) }
                
                val responseCode = conn.responseCode
                Log.i("NightscoutClient", "Glucose Upload Status: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("NightscoutClient", "Glucose Upload Error: ${e.message}")
            }
        }
    }

    fun uploadBolus(units: Double, timestamp: Long, notes: String = "") {
        if (baseUrl.isBlank()) return
        
        thread {
            try {
                val url = URL("$baseUrl/api/v1/treatments")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("api-secret", getHashedSecret())
                conn.doOutput = true

                val treatment = JSONObject().apply {
                    put("eventType", "Bolus")
                    put("insulin", units)
                    put("notes", notes)
                    put("created_at", ISO8601Utils.format(Date(timestamp)))
                }
                
                OutputStreamWriter(conn.outputStream).use { it.write(treatment.toString()) }
                
                val responseCode = conn.responseCode
                Log.i("NightscoutClient", "Bolus Upload Status: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("NightscoutClient", "Bolus Upload Error: ${e.message}")
            }
        }
    }

    // Helper for ISO8601 date formatting
    object ISO8601Utils {
        fun format(date: Date): String {
            val tz = TimeZone.getTimeZone("UTC")
            val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            df.timeZone = tz
            return df.format(date)
        }
    }
}
