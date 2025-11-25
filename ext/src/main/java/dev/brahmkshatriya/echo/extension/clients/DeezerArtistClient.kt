package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerArtistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    fun getShelves(artist: Artist): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return@Single emptyList()

        orderedKeys.mapNotNull { key ->
            val payload = resultsObject[key] ?: return@mapNotNull null
            shelfFactories[key]?.invoke(parser, payload.jsonObject)
        }
    }.toFeed()

    suspend fun loadArtist(artist: Artist): Artist {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return artist
        return parser.run { resultsObject.toArtist() }
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val dataArray = api.getArtists()["results"]?.jsonObject
            ?.get("TAB")?.jsonObject
            ?.get("artists")?.jsonObject
            ?.get("data")?.jsonArray ?: return false

        return dataArray.any { artistItem ->
            val artistId = artistItem.jsonObject["ART_ID"]?.jsonPrimitive?.content
            artistId == item.id
        }
    }

    fun getFollowersCount(item: EchoMediaItem): Long? = item.extras["followers"]?.toLongOrNull()

    private companion object {
        private fun Shelf.isEffectivelyEmpty(): Boolean = when (this) {
            is Shelf.Lists.Items -> list.isEmpty()
            is Shelf.Lists.Tracks -> list.isEmpty()
            else -> false
        }
        private fun Shelf?.nullIfEmpty(): Shelf? = this?.takeIf { !it.isEffectivelyEmpty() }

        private val shelfFactories: Map<String, DeezerParser.(JsonObject) -> Shelf?> = mapOf(
            "TOP" to filterEmpty { jObject ->
                val shelf =
                    jObject["data"]?.jsonArray?.toShelfItemsList("Top") as? Shelf.Lists.Items
                val list = (shelf?.list as? List<Track>).orEmpty()
                if (list.isEmpty()) null
                else Shelf.Lists.Tracks(
                    id = shelf?.id.orEmpty(),
                    title = shelf?.title.orEmpty(),
                    subtitle = shelf?.subtitle,
                    type = Shelf.Lists.Type.Linear,
                    more = list.map { it.toShelf() }.toFeed(),
                    list = list.take(5)
                )
            },
            "HIGHLIGHT" to filterEmpty { jObject ->
                jObject["ITEM"]?.jsonObject?.toShelfItemsList("Highlight").nullIfEmpty()
            },
            "SELECTED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Selected Playlists").nullIfEmpty()
            },
            "RELATED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Related Playlists").nullIfEmpty()
            },
            "RELATED_ARTISTS" to filterEmpty { jObject ->
                val shelf =
                    jObject["data"]?.jsonArray?.toShelfItemsList("Related Artists") as? Shelf.Lists.Items
                val list = shelf?.list.orEmpty()
                if (list.isEmpty()) null
                else Shelf.Lists.Items(
                    id = shelf?.id.orEmpty(),
                    title = shelf?.title.orEmpty(),
                    subtitle = shelf?.subtitle,
                    type = Shelf.Lists.Type.Linear,
                    more = list.map { it.toShelf() }.toFeed(),
                    list = list
                )
            },
            "ALBUMS" to filterEmpty { jObject ->
                val shelf =
                    jObject["data"]?.jsonArray?.toShelfItemsList("Albums") as? Shelf.Lists.Items
                val list = shelf?.list.orEmpty()
                if (list.isEmpty()) null
                else Shelf.Lists.Items(
                    id = shelf?.id.orEmpty(),
                    title = shelf?.title.orEmpty(),
                    subtitle = shelf?.subtitle,
                    type = Shelf.Lists.Type.Linear,
                    more = list.map { it.toShelf() }.toFeed(),
                    list = list
                )
            }
        )

        private fun filterEmpty(
            block: DeezerParser.(JsonObject) -> Shelf?
        ): DeezerParser.(JsonObject) -> Shelf? = { json ->
            block(this, json)?.takeIf { !it.isEffectivelyEmpty() }
        }

        private val orderedKeys = listOf(
            "TOP",
            "HIGHLIGHT",
            "SELECTED_PLAYLIST",
            "ALBUMS",
            "RELATED_PLAYLIST",
            "RELATED_ARTISTS"
        )
    }
}