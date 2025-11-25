package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerRadioClient(private val api: DeezerApi, private val parser: DeezerParser) {

    fun loadTracks(radio: Radio): Feed<Track> = PagedData.Single {
        val kind = radio.kind()
        val dataArray: JsonArray = when (kind) {
            RadioKind.TRACK -> api.mix(radio.id).resultsArray("data")
            RadioKind.ARTIST -> api.mixArtist(radio.id).resultsArray("data")
            RadioKind.PLAYLIST,
            RadioKind.ALBUM -> api.radio(radio.id, radio.extras["artist"].orEmpty())
                .resultsArray("data")
            RadioKind.FLOW -> api.flow(radio.id).resultsArray("data")
        } ?: JsonArray(emptyList())

        dataArray.mapIndexed { index, song ->
            val track = song.safeObj()?.toTrack(parser) ?: return@mapIndexed null
            val next = dataArray.getOrNull(index + 1)?.safeObj()?.toTrack(parser)
            val nextId = next?.id.orEmpty()

            val addlExtras = when (kind) {
                RadioKind.TRACK -> mapOf("artist_id" to track.artists.firstOrNull()?.id.orEmpty())
                RadioKind.ARTIST -> mapOf("artist_id" to radio.id)
                RadioKind.PLAYLIST,
                RadioKind.ALBUM -> mapOf("artist_id" to radio.extras["artist"].orEmpty())
                RadioKind.FLOW -> mapOf("user_id" to "0")
            }

            track.copy(
                extras = track.extras + mapOf("NEXT" to nextId) + addlExtras
            )
        }.filterNotNull()
    }.toFeed()

    suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = when (item) {
        is Artist -> Radio(
            id = item.id,
            title = item.name,
            cover = item.cover,
            extras = mapOf("radio" to "artist")
        )

        is Album -> {
            val last = api.album(item).lastTrackFromSongs(parser)
                ?: error("No Radio")
            Radio(
                id = last.id,
                title = last.title,
                cover = last.cover,
                extras = mapOf(
                    "radio" to "album",
                    "artist" to last.artists.firstOrNull()?.id.orEmpty()
                )
            )
        }

        is Playlist -> {
            val last = api.playlist(item).lastTrackFromSongs(parser)
                ?: error("No Radio")
            Radio(
                id = last.id,
                title = last.title,
                cover = last.cover,
                extras = mapOf(
                    "radio" to "playlist",
                    "artist" to last.artists.firstOrNull()?.id.orEmpty()
                )
            )
        }

        is Track -> {
            val track = item
            when (context) {
                null -> track.asTrackRadio()
                is Radio -> when (context.kind()) {
                    RadioKind.TRACK -> track.asTrackRadio()
                    RadioKind.ARTIST -> context.asArtistRadio()
                    RadioKind.PLAYLIST -> track.asCollectionTrackRadio("playlist")
                    RadioKind.ALBUM -> track.asCollectionTrackRadio("album")
                    RadioKind.FLOW -> context
                }
                is Artist -> Radio(context.id, context.name, context.cover,
                    mapOf("radio" to "artist") as List<Artist>
                )
                is Playlist -> track.asCollectionTrackRadio("playlist")
                is Album -> track.asCollectionTrackRadio("album")
                else -> error("No Radio")
            }
        }

        is Radio -> item
    }

    private enum class RadioKind { TRACK, ARTIST, PLAYLIST, ALBUM, FLOW }

    private fun Radio.kind(): RadioKind = when (extras["radio"]) {
        "track" -> RadioKind.TRACK
        "artist" -> RadioKind.ARTIST
        "playlist" -> RadioKind.PLAYLIST
        "album" -> RadioKind.ALBUM
        else -> RadioKind.FLOW
    }

    private fun Track.asTrackRadio() = Radio(
        id = id,
        title = title,
        cover = cover,
        extras = mapOf("radio" to "track")
    )

    private fun Radio.asArtistRadio() = Radio(
        id = id,
        title = title,
        cover = cover,
        extras = mapOf("radio" to "artist")
    )

    private fun Track.asCollectionTrackRadio(tag: String) = Radio(
        id = id,
        title = title,
        cover = cover,
        extras = mapOf(
            "radio" to tag,
            "artist" to artists.firstOrNull()?.id.orEmpty()
        )
    )

    private fun JsonObject.resultsArray(key: String): JsonArray? =
        this["results"]?.jsonObject?.get(key)?.jsonArray

    private fun JsonElement.safeObj(): JsonObject? =
        runCatching { this.jsonObject }.getOrNull()

    private fun JsonObject.toTrack(parser: DeezerParser): Track =
        parser.run { this@toTrack.toTrack() }

    private fun JsonObject.lastTrackFromSongs(parser: DeezerParser): Track? =
        runCatching {
            val songs = this["results"]?.jsonObject
                ?.get("SONGS")?.jsonObject
                ?.get("data")?.jsonArray
                ?: return null
            val lastObj = songs.lastOrNull()?.safeObj() ?: return null
            lastObj.toTrack(parser)
        }.getOrNull()
}