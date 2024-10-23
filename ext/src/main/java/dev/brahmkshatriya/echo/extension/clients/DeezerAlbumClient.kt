package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerAlbumClient(private val api: DeezerApi, private val parser: DeezerParser) {

    suspend fun loadAlbum(album: Album): Album {
        if (album.extras["__TYPE__"] == "show") {
            val jsonObject = api.show(album)
            val resultsObject = jsonObject["results"]!!.jsonObject
            return parser.run { resultsObject.toShow(true) }
        } else {
            val jsonObject = api.album(album)
            val resultsObject = jsonObject["results"]!!.jsonObject
            return parser.run { resultsObject.toAlbum(true) }
        }
    }

    fun loadTracks(album: Album): PagedData<Track> = PagedData.Single {
        if (album.extras["__TYPE__"] == "show") {
            val jsonObject = api.show(album)
            val resultsObject = jsonObject["results"]!!.jsonObject
            val episodesObject = resultsObject["EPISODES"]!!.jsonObject
            val dataArray = episodesObject["data"]!!.jsonArray
            val data = dataArray.map { episode ->
                parser.run {
                    episode.jsonObject.toEpisode()
                }
            }.reversed()
            data
        } else {
            val jsonObject = api.album(album)
            val resultsObject = jsonObject["results"]!!.jsonObject
            val songsObject = resultsObject["SONGS"]!!.jsonObject
            val dataArray = songsObject["data"]!!.jsonArray
            val data = dataArray.mapIndexed { index, song ->
                val currentTrack = parser.run { song.jsonObject.toTrack() }
                val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.jsonObject?.toTrack() }
                Track(
                    id = currentTrack.id,
                    title = currentTrack.title,
                    cover = currentTrack.cover,
                    duration = currentTrack.duration,
                    releaseDate = currentTrack.releaseDate,
                    artists = currentTrack.artists,
                    extras = currentTrack.extras.plus(
                        mapOf(
                            Pair("NEXT", nextTrack?.id.orEmpty()),
                            Pair("album_id", album.id)
                        )
                    )
                )
            }
            data
        }
    }
}