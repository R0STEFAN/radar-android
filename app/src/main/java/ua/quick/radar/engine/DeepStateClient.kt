package ua.quick.radar.engine

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DeepStateClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val foreignKeywords = listOf(
        "Курильські", "Абхазія", "Пруссія", "Карелія", "Ічкерія",
        "Латвії", "Естонії", "Петсамо", "Салла", "Москва",
        "Мінськ", "Пхеньян", "Придністров", "Саатсе", "Цхінвалі", "Фіцо"
    )

    fun fetchDeepStateData(context: Context): String? {
        val cacheFile = File(context.cacheDir, "deepstate_cache.json")
        val nowSec = System.currentTimeMillis() / 1000.0

        // 1. Check local cache (valid for 2 hours)
        try {
            if (cacheFile.exists()) {
                val cachedContent = cacheFile.readText(Charsets.UTF_8)
                val cachedObj = gson.fromJson(cachedContent, JsonObject::class.java)
                val cachedAt = cachedObj.get("cached_at")?.asDouble ?: 0.0
                if (nowSec - cachedAt < 7200.0 && cachedObj.has("geojson")) {
                    return cachedContent
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted cache and fetch fresh
        }

        // 2. Fetch latest history metadata
        return try {
            val histUrl = "https://deepstatemap.live/api/history/public"
            val histReq = Request.Builder()
                .url(histUrl)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .get()
                .build()

            val histBody = client.newCall(histReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val histArray = gson.fromJson(histBody, JsonArray::class.java)
            if (histArray.size() == 0) return null

            val latestItem = histArray.get(histArray.size() - 1).asJsonObject
            val latestId = latestItem.get("id")?.asLong ?: return null
            val latestDatetime = latestItem.get("datetime")?.asString ?: ""
            val latestDesc = latestItem.get("description")?.asString ?: ""

            // 3. Fetch GeoJSON for the latest history ID
            val geoUrl = "https://deepstatemap.live/api/history/$latestId/geojson"
            val geoReq = Request.Builder()
                .url(geoUrl)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .get()
                .build()

            val geoBody = client.newCall(geoReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val geoObj = gson.fromJson(geoBody, JsonObject::class.java)
            val features = geoObj.getAsJsonArray("features") ?: JsonArray()

            val filteredFeatures = JsonArray()
            for (fElem in features) {
                if (!fElem.isJsonObject) continue
                val feat = fElem.asJsonObject
                val geom = feat.getAsJsonObject("geometry") ?: continue
                val geomType = geom.get("type")?.asString ?: ""

                if (geomType != "Polygon" && geomType != "MultiPolygon") continue

                val props = feat.getAsJsonObject("properties")
                val name = props?.get("name")?.asString ?: ""
                if (foreignKeywords.any { name.contains(it) }) continue

                val propsStr = props?.toString() ?: ""
                if (propsStr.contains("units") || propsStr.contains("geoJSON.units")) continue

                filteredFeatures.add(feat)
            }

            val cleanGeoJson = JsonObject().apply {
                addProperty("type", "FeatureCollection")
                add("features", filteredFeatures)
            }

            val resultObj = JsonObject().apply {
                addProperty("status", "ok")
                addProperty("id", latestId)
                addProperty("datetime", latestDatetime)
                addProperty("description", latestDesc)
                addProperty("cached_at", nowSec)
                add("geojson", cleanGeoJson)
            }

            val resultJson = gson.toJson(resultObj)
            try {
                cacheFile.writeText(resultJson, Charsets.UTF_8)
            } catch (e: Exception) {
                // Ignore cache write failure
            }

            resultJson
        } catch (e: Exception) {
            // Return cached version if available even if older
            if (cacheFile.exists()) {
                try { cacheFile.readText(Charsets.UTF_8) } catch (_: Exception) { null }
            } else null
        }
    }
}
