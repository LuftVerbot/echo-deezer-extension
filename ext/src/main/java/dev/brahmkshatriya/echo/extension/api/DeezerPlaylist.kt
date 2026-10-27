package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class DeezerPlaylist(private val deezerApi: DeezerApi) {

    suspend fun playlist(playlist: Playlist): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pagePlaylist",
            paramsBuilder = {
                put("playlist_id", playlist.id)
                put("lang", deezerApi.langCode, )
                put("nb", playlist.trackCount)
                put("tags", true)
                put("start", 0)
            }
        )
    }

    suspend fun getPlaylists(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageProfile",
            paramsBuilder = {
                put("user_id", userId)
                put("tab", "playlists")
                put("nb", 10000)
            }
        )
    }

    suspend fun addFavoritePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.addFavorite",
            paramsBuilder = {
                put("PARENT_PLAYLIST_ID", id)
            }
        )
    }

    suspend fun removeFavoritePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.deleteFavorite",
            paramsBuilder = {
                put("PLAYLIST_ID", id)
            }
        )
    }

    suspend fun addToPlaylist(playlist: Playlist, tracks: List<Track>) {
        deezerApi.callApi(
            method = "playlist.addSongs",
            paramsBuilder = {
                put("playlist_id", playlist.id)
                put("songs", buildJsonArray {
                    tracks.forEach { track ->
                        add(buildJsonArray { add(track.id); add(0) })
                    }
                })
            }
        )
    }

    suspend fun removeFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        val trackIds = tracks.map { it.id }
        val ids = indexes.map { index -> trackIds[index] }

        deezerApi.callApi(
            method = "playlist.deleteSongs",
            paramsBuilder = {
                put("playlist_id", playlist.id)
                put("songs", buildJsonArray {
                    ids.forEach { id ->
                        add(buildJsonArray { add(id); add(0) })
                    }
                })
            }
        )
    }

    suspend fun createPlaylist(title: String, description: String? = ""): JsonObject {
        return deezerApi.callApi(
            method = "playlist.create",
            paramsBuilder = {
                put("title", title)
                put("description", description, )
                put("songs", buildJsonArray {})
                put("status", 0)
            }
        )
    }

    suspend fun deletePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.delete",
            paramsBuilder = {
                put("playlist_id", id)
            }
        )
    }

    suspend fun updatePlaylist(id: String, title: String, description: String? = "") {
        deezerApi.callApi(
            method = "playlist.update",
            paramsBuilder = {
                put("description", description)
                put("playlist_id", id)
                put("status", 0)
                put("title", title)
            }
        )
    }

    suspend fun updatePlaylistOrder(playlistId: String, ids: MutableList<String>) {
        deezerApi.callApi(
            method = "playlist.updateOrder",
            paramsBuilder = {
                put("order", buildJsonArray { ids.forEach { add(it) } })
                put("playlist_id", playlistId)
                put("position", 0)
            }
        )
    }
}