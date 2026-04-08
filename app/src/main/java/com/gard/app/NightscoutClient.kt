package com.gard.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.*
import kotlin.concurrent.thread

class NightscoutClient(
    private var baseUrl: String, 
    private var apiSecret: String,
    private val logCallback: ((String) -> Unit)? = null
) {

    private fun log(msg: String, error: Boolean = false) {
        if (error) Log.e("NightscoutClient", msg) else Log.i("NightscoutClient", msg)
        logCallback?.invoke(msg)
    }

    fun updateConfig(url: String, secret: String) {
        this.baseUrl = url.trimEnd('/')
        this.apiSecret = secret
        log("Config updated: $baseUrl")
    }

    private fun getHashedSecret(): String {
        if (apiSecret.isBlank()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(apiSecret.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log("Hash error: ${e.message}", true)
            ""
        }
    }

    private fun readStream(connection: HttpURLConnection): String {
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            stream?.bufferedReader()?.use { it.readText() } ?: "(empty response)"
        } catch (e: Exception) {
            "Error reading stream: ${e.message}"
        }
    }

    fun uploadGlucose(glucose: Int, timestamp: Long, direction: String = "Flat") {
        uploadGlucoseMulti(listOf(GlucoseEntry(glucose, timestamp, direction)))
    }

    data class GlucoseEntry(val glucose: Int, val timestamp: Long, val direction: String)

    fun uploadGlucoseMulti(entries: List<GlucoseEntry>) {
        if (entries.isEmpty() || baseUrl.isBlank()) return
        
        val hashed = getHashedSecret()
        
        thread(start = true, name = "NightscoutUpload") {
            try {
                val fullUrl = "$baseUrl/api/v1/entries"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GarD-Android")
                
                if (apiSecret.isNotEmpty()) {
                    conn.setRequestProperty("api-secret", hashed)
                    conn.setRequestProperty("API-SECRET", apiSecret)
                }

                conn.doOutput = true

                val jsonArray = JSONArray()
                entries.forEach { entry ->
                    val obj = JSONObject().apply {
                        put("sgv", entry.glucose)
                        put("date", entry.timestamp)
                        put("mills", entry.timestamp)
                        put("dateString", ISO8601Utils.format(Date(entry.timestamp)))
                        put("type", "sgv")
                        put("direction", entry.direction)
                        put("device", "GarD")
                    }
                    jsonArray.put(obj)
                }
                
                val jsonPayload = jsonArray.toString()
                log("POST ${entries.size} entries to $fullUrl")
                
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(jsonPayload) }
                
                val responseCode = conn.responseCode
                val responseBody = readStream(conn)
                
                if (responseCode in 200..299) {
                    log("SUCCESS ($responseCode): Uploaded ${entries.size} entries")
                } else {
                    log("FAILED ($responseCode): $responseBody", true)
                }
                conn.disconnect()
            } catch (e: Exception) {
                log("UPLOAD ERROR: ${e.javaClass.simpleName}: ${e.message}", true)
            }
        }
    }

    fun uploadBolus(units: Double, timestamp: Long, notes: String = "") {
        if (baseUrl.isBlank()) return
        
        thread(start = true, name = "NightscoutBolus") {
            try {
                val url = URL("$baseUrl/api/v1/treatments")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                
                val hashed = getHashedSecret()
                if (apiSecret.isNotEmpty()) {
                    conn.setRequestProperty("api-secret", hashed)
                    conn.setRequestProperty("API-SECRET", apiSecret)
                }
                conn.doOutput = true

                val treatment = JSONObject().apply {
                    put("eventType", "Bolus")
                    put("insulin", units)
                    put("notes", notes)
                    put("created_at", ISO8601Utils.format(Date(timestamp)))
                }
                
                OutputStreamWriter(conn.outputStream).use { it.write(treatment.toString()) }
                
                val responseCode = conn.responseCode
                val responseBody = readStream(conn)
                log("Bolus Result ($responseCode): $responseBody")
                conn.disconnect()
            } catch (e: Exception) {
                log("Bolus Error: ${e.message}", true)
            }
        }
    }

    object ISO8601Utils {
        fun format(date: Date): String {
            val tz = TimeZone.getTimeZone("UTC")
            val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            df.timeZone = tz
            return df.format(date)
        }
    }
}
