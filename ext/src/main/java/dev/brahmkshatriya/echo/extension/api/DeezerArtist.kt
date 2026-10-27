package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DeezerArtist(private val deezerApi: DeezerApi) {

    suspend fun artist(id: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageArtist",
            paramsBuilder = {
                put("art_id", id)
                put ("lang", deezerApi.langCode)
            }
        )
    }

    suspend fun getArtists(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageProfile",
            paramsBuilder = {
                put("nb", 40)
                put("tab", "artists")
                put("user_id", userId)
            }
        )
    }

    suspend fun followArtist(id: String) {
        deezerApi.callApi(
            method = "artist.addFavorite",
            paramsBuilder = {
                put("ART_ID", id)
                putJsonObject("CTXT") {
                    put("id", id)
                    put("t", "artist_smartradio")
                }
            }
        )
    }

    suspend fun unfollowArtist(id: String) {
        deezerApi.callApi(
            method = "artist.deleteFavorite",
            paramsBuilder = {
                put("ART_ID", id)
                putJsonObject("CTXT") {
                    put("id", id)
                    put("t", "artist_smartradio")
                }
            }
        )
    }
}