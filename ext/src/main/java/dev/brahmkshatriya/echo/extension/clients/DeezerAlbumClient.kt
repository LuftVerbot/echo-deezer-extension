package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils.asObject
import dev.brahmkshatriya.echo.extension.Utils.str
import kotlinx.serialization.json.jsonArray

class DeezerAlbumClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private suspend fun results(album: Album) =
        (if (album.type == Album.Type.Show) api.show(album) else api.album(album))["results"]!!.asObject()

    suspend fun loadAlbum(album: Album): Album {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        return parser.run { if (album.type == Album.Type.Show) resultsObject.toShow() else resultsObject.toAlbum() }
    }

    fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        if (album.type == Album.Type.Show) {
            val dataArray = resultsObject["EPISODES"]!!.asObject()["data"]!!.jsonArray
            val bookmarkMap = api.getBookmarkedEpisodes()["results"]!!.asObject()["data"]!!.jsonArray.associate { ep ->
                ep.asObject().str("EPISODE_ID") to
                        ep.asObject().str("OFFSET")?.toLongOrNull()
            }

            dataArray.map { episode ->
                parser.run { episode.asObject().toEpisode(bookmarkMap) }
            }.reversed()
        } else {
            val dataArray = resultsObject["SONGS"]!!.asObject()["data"]!!.jsonArray
            dataArray.mapIndexed { index, song ->
                val currentTrack = parser.run { song.asObject().toTrack() }
                val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.asObject()?.toTrack() }
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