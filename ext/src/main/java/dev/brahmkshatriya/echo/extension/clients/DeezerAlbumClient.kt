package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerAlbumClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private suspend fun results(album: Album) =
        (if (album.type == Album.Type.Show) api.show(album) else api.album(album))["results"]!!.jsonObject

    suspend fun loadAlbum(album: Album): Album {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        return parser.run { if (album.type == Album.Type.Show) resultsObject.toShow() else resultsObject.toAlbum() }
    }

    fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        if (album.type == Album.Type.Show) {
            val dataArray = resultsObject["EPISODES"]!!.jsonObject["data"]!!.jsonArray
            val bookmarkMap = api.getBookmarkedEpisodes()["results"]!!.jsonObject["data"]!!.jsonArray.associate { ep ->
                ep.jsonObject["EPISODE_ID"]?.jsonPrimitive?.content to
                        ep.jsonObject["OFFSET"]?.jsonPrimitive?.content?.toLongOrNull()
            }

            dataArray.map { episode ->
                parser.run { episode.jsonObject.toEpisode(bookmarkMap) }
            }.reversed()
        } else {
            val dataArray = resultsObject["SONGS"]!!.jsonObject["data"]!!.jsonArray
            dataArray.mapIndexed { index, song ->
                val currentTrack = parser.run { song.jsonObject.toTrack() }
                val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.jsonObject?.toTrack() }
                currentTrack.copy(
                    extras = currentTrack.extras + mapOf(
                        "NEXT" to nextTrack?.id.orEmpty(),
                        "album_id" to album.id
                    )
                )
            }
        }
    }.toFeed()
}