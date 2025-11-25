package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DeezerMedia(private val deezerApi: DeezerApi, private val clientNP: OkHttpClient) {

    suspend fun getMP3MediaUrl(track: Track, arl: String, sid: String, licenseToken: String, is128: Boolean): JsonObject {
        val headers = Headers.Builder().apply {
            //add("Accept-Encoding", "gzip")
            add("Accept-Language", deezerApi.langCode)
            add("Cache-Control", "max-age=0")
            add("Connection", "Keep-alive")
            add("Content-Type", "application/json; charset=utf-8")
            add("Cookie", "arl=$arl&sid=$sid")
            add("Host", "media.deezer.com")
            add(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36"
            )
        }.build()

        val requestBody = deezerApi.encodeJson {
            put("license_token", licenseToken)
            putJsonArray("media") {
                add(buildJsonObject {
                    put("type", "FULL")
                    putJsonArray("formats") {
                        add(buildJsonObject {
                            put("cipher", "BF_CBC_STRIPE")
                            put("format", if (!is128) "MP3_MISC" else "MP3_128")
                        })
                    }
                })
            }
            putJsonArray("track_tokens") { add(track.extras["TRACK_TOKEN"]) }
        }.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://media.deezer.com/v1/get_url")
            .post(requestBody)
            .headers(headers)
            .build()

        val response = clientNP.newCall(request).await()
        val responseBody = response.body.string()

        return deezerApi.decodeJson(responseBody)
    }

    suspend fun getMediaUrl(track: Track, quality: String): JsonObject {
        val formats = when (quality) {
            "128" -> arrayOf("MP3_128")
            "flac" -> arrayOf("FLAC")
            else -> arrayOf("MP3_320")
        }

        val requestBody = deezerApi.encodeJson {
            put("formats", buildJsonArray { formats.forEach { add(it) } })
            put("ids", buildJsonArray { add(track.id.toLong()) })
        }.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://lufts-dzmedia.fly.dev/get_url")
            .post(requestBody)
            .build()

        val response = clientNP.newCall(request).await()
        val responseBody = response.body.string()

        // If the service is currently unavailable, go back to deezer
        if (responseBody.contains("All accounts are cooling down")) {
            return deezerApi.getMP3MediaUrl(
                track,
                quality == "128"
            )
        }

        return deezerApi.decodeJson(responseBody)
    }
}