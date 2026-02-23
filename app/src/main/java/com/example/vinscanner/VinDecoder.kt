package com.example.vinscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Simple VIN decoder using NHTSA VPIC API. Extracts relevant fields.
 */
object VinDecoder {

    suspend fun decode(vin: String): Result<VehicleInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanVin = vin.trim().uppercase(Locale.US)
            val apiUrl = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/$cleanVin?format=json"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("VIN API responded with $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.use { it.readText() }
            parseResponse(cleanVin, response)
        }
    }

    private fun parseResponse(vin: String, response: String): VehicleInfo {
        val json = JSONObject(response)
        val results = json.getJSONArray("Results")

        fun sanitize(value: String?): String? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalized = text.lowercase(Locale.US)
            return if (normalized in setOf("not applicable", "null", "n/a", "na")) null else text
        }

        fun findValue(variable: String): String? {
            for (i in 0 until results.length()) {
                val entry = results.getJSONObject(i)
                if (entry.optString("Variable") == variable) {
                    sanitize(entry.optString("Value"))?.let { return it }
                }
            }
            return null
        }

        fun getFirst(vararg names: String, fallback: String = ""): String {
            for (name in names) {
                val value = findValue(name)
                if (!value.isNullOrBlank()) return value
            }
            return fallback
        }

        fun joinParts(vararg parts: String?): String = parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" • ")

        val engine = joinParts(
            getFirst("Engine Model", "Engine Configuration"),
            getFirst("Engine Manufacturer"),
            listOfNotNull(
                findValue("Engine Displacement (L)")?.let { "${it}L" },
                findValue("Engine Displacement (CI)")?.let { "${it} CI" },
                findValue("Engine Horsepower")?.let { "$it HP" }
            ).joinToString(" / ").takeIf { it.isNotBlank() }
        ).ifBlank { "Unknown" }

        val drive = getFirst("Drive Type", "Drive (FWD/4WD)", "Drive", fallback = "Unknown")
        val transmission = joinParts(
            getFirst("Transmission Style"),
            getFirst("Transmission Description"),
            getFirst("Transmission Speeds")
        ).ifBlank { "Unknown" }

        val body = getFirst("Body Class", fallback = "Unknown")
        val fuel = getFirst("Fuel Type - Primary", "Fuel Type - Secondary", fallback = "Unknown")

        return VehicleInfo(
            vin = vin,
            make = getFirst("Make", fallback = "Unknown"),
            model = getFirst("Model", fallback = "Unknown"),
            modelYear = getFirst("Model Year", fallback = "Unknown"),
            engine = engine,
            driveType = drive,
            transmission = transmission,
            bodyClass = body,
            fuelType = fuel,
            trim = getFirst("Trim", "Series").ifBlank { null },
            plant = getFirst("Plant City", "Plant Company Name").ifBlank { null },
            notes = getFirst("Other Engine Info", "Other Restraint System Info").ifBlank { null }
        )
    }
}
