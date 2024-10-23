package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Date

class DeezerParser(private val session: DeezerSession) {

    fun JsonElement.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val itemsArray = jsonObject["items"]?.jsonArray ?: return null
        val list = itemsArray.mapNotNull { it.jsonObject.toEchoMediaItem() }
        return if (list.isNotEmpty()) {
            Shelf.Lists.Items(
                title = name,
                list = list
            )
        } else {
            null
        }
    }

    fun JsonObject.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val item = toEchoMediaItem() ?: return null
        return Shelf.Lists.Items(
            title = name,
            list = listOf(item)
        )
    }

    fun JsonArray.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val list = mapNotNull { it.jsonObject.toEchoMediaItem() }
        return if (list.isNotEmpty()) {
            Shelf.Lists.Items(
                title = name,
                list = list
            )
        } else {
            null
        }
    }

    fun JsonElement.toShelfCategoryList(
        name: String = "Unknown",
        block: suspend (String) -> List<Shelf>
    ): Shelf.Lists.Categories {
        val itemsArray = jsonObject["items"]?.jsonArray ?: return Shelf.Lists.Categories(name, emptyList())
        return Shelf.Lists.Categories(
            title = name,
            list = itemsArray.take(5).mapNotNull { it.jsonObject.toShelfCategory(block) },
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Single {
                itemsArray.mapNotNull { it.jsonObject.toShelfCategory(block) }
            }
        )
    }

    fun JsonObject.toShelfCategory(block: suspend (String) -> List<Shelf>): Shelf.Category? {
        val data = this["data"]?.jsonObject ?: this
        val type = data["__TYPE__"]?.jsonPrimitive?.content ?: return null
        return when {
            "channel" in type -> toChannel(block)
            else -> null
        }
    }

    fun JsonObject.toChannel(block: suspend (String) -> List<Shelf>): Shelf.Category {
        val data = this["data"]?.jsonObject ?: this
        val title = data["title"]?.jsonPrimitive?.content.orEmpty()
        val target = this["target"]?.jsonPrimitive?.content.orEmpty()
        return Shelf.Category(
            title = title,
            items = PagedData.Single {
                block(target)
            },
        )
    }

    fun JsonObject.toEchoMediaItem(): EchoMediaItem? {
        val data = this["data"]?.jsonObject ?: this
        val type = data["__TYPE__"]?.jsonPrimitive?.content ?: return null
        return when {
            "playlist" in type -> EchoMediaItem.Lists.PlaylistItem(toPlaylist())
            "album" in type -> EchoMediaItem.Lists.AlbumItem(toAlbum())
            "song" in type -> EchoMediaItem.TrackItem(toTrack())
            "artist" in type -> EchoMediaItem.Profile.ArtistItem(toArtist())
            "show" in type -> EchoMediaItem.Lists.AlbumItem(toShow())
            "episode" in type -> EchoMediaItem.TrackItem(toEpisode())
            "flow" in type -> EchoMediaItem.Lists.RadioItem(toRadio())
            else -> null
        }
    }

    fun JsonObject.toShow(loaded: Boolean = false): Album {
        val data = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this
        val md5 = data["SHOW_ART_MD5"]?.jsonPrimitive?.content.orEmpty()
        return Album(
            id = data["SHOW_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["SHOW_NAME"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "talk", loaded),
            tracks = this["EPISODES"]?.jsonObject?.get("total")?.jsonPrimitive?.int,
            artists = listOf(Artist(id = "", name = "")),
            description = data["SHOW_DESCRIPTION"]?.jsonPrimitive?.content.orEmpty(),
            extras = mapOf("__TYPE__" to "show")
        )
    }

    fun JsonObject.toEpisode(): Track {
        val data = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this
        val md5 = data["SHOW_ART_MD5"]?.jsonPrimitive?.content.orEmpty()
        val title = data["EPISODE_TITLE"]?.jsonPrimitive?.content.orEmpty()
        return Track(
            id = data["EPISODE_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = title,
            cover = getCover(md5, "talk", false),
            duration = data["DURATION"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            streamables = listOf(
                Streamable.audio(
                    id = data["EPISODE_DIRECT_STREAM_URL"]?.jsonPrimitive?.content.orEmpty(),
                    title = title,
                    quality = 1
                )
            ),
            extras = mapOf(
                "TRACK_TOKEN" to data["TRACK_TOKEN"]?.jsonPrimitive?.content.orEmpty(),
                "FILESIZE_MP3_MISC" to (data["FILESIZE_MP3_MISC"]?.jsonPrimitive?.content ?: "0"),
                "MD5" to md5,
                "TYPE" to "talk",
                "__TYPE__" to "show"
            )
        )
    }

    fun JsonObject.toAlbum(loaded: Boolean = false): Album {
        val data = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this
        val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val artistObject = data["ARTISTS"]?.jsonArray?.firstOrNull()?.jsonObject
        val artistMd5 = artistObject?.get("ART_PICTURE")?.jsonPrimitive?.content.orEmpty()
        return Album(
            id = data["ALB_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["ALB_TITLE"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "cover", loaded),
            tracks = this["SONGS"]?.jsonObject?.get("total")?.jsonPrimitive?.int,
            artists = listOfNotNull(
                artistObject?.let {
                    Artist(
                        id = it["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
                        name = it["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
                        cover = getCover(artistMd5, "artist")
                    )
                }
            ),
            description = this["description"]?.jsonPrimitive?.content.orEmpty(),
            subtitle = this["subtitle"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    fun JsonObject.toArtist(isFollowing: Boolean = false, loaded: Boolean = false): Artist {
        val data = this["data"]?.jsonObject ?: this
        val md5 = data["ART_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        return Artist(
            id = data["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
            name = data["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "artist", loaded),
            description = this["description"]?.jsonPrimitive?.content.orEmpty(),
            subtitle = this["subtitle"]?.jsonPrimitive?.content.orEmpty(),
            isFollowing = isFollowing
        )
    }

    @Suppress("NewApi")
    fun JsonObject.toTrack(loaded: Boolean = false): Track {
        val data = this["data"]?.jsonObject ?: this
        val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val artistObject = data["ARTISTS"]?.jsonArray?.firstOrNull()?.jsonObject ?: data
        val artistMd5 = artistObject["ART_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        return Track(
            id = data["SNG_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["SNG_TITLE"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "cover", loaded),
            duration = data["DURATION"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            releaseDate = data["DATE_ADD"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                Date.from(Instant.ofEpochSecond(it)).toString()
            },
            artists = listOfNotNull(
                Artist(
                    id = artistObject["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
                    name = artistObject["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
                    cover = getCover(artistMd5, "artist")
                )
            ),
            isExplicit = data["EXPLICIT_LYRICS"]?.jsonPrimitive?.content?.equals("1") ?: false,
            extras = mapOf(
                "TRACK_TOKEN" to data["TRACK_TOKEN"]?.jsonPrimitive?.content.orEmpty(),
                "FILESIZE_MP3_MISC" to (data["FILESIZE_MP3_MISC"]?.jsonPrimitive?.content ?: "0"),
                "MD5" to md5,
                "TYPE" to "cover"
            )
        )
    }

    fun JsonObject.toPlaylist(loaded: Boolean = false): Playlist {
        val data = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this
        val type = data["PICTURE_TYPE"]?.jsonPrimitive?.content.orEmpty()
        val md5 = data["PLAYLIST_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        return Playlist(
            id = data["PLAYLIST_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["TITLE"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, type, loaded),
            description = data["DESCRIPTION"]?.jsonPrimitive?.content.orEmpty(),
            subtitle = this["subtitle"]?.jsonPrimitive?.content.orEmpty(),
            isEditable = data["PARENT_USER_ID"]?.jsonPrimitive?.content == session.credentials?.userId,
            tracks = data["NB_SONG"]?.jsonPrimitive?.int ?: 0
        )
    }

    private fun JsonObject.toRadio(loaded: Boolean = false): Radio {
        val data = this["data"]?.jsonObject ?: this
        val imageObject = this["pictures"]?.jsonArray?.firstOrNull()?.jsonObject.orEmpty()
        val md5 = imageObject["md5"]?.jsonPrimitive?.content.orEmpty()
        val type = imageObject["type"]?.jsonPrimitive?.content.orEmpty()
        return Radio(
            id = data["id"]?.jsonPrimitive?.content.orEmpty(),
            title = data["title"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, type, loaded),
            extras = mapOf(
                "radio" to "flow"
            )
        )
    }

    private val quality: Int?
        get() = session.settings?.getInt("image_quality")

    private fun getCover(md5: String?, type: String?, loaded: Boolean = false): ImageHolder {
        val size = if (loaded) "${quality ?: 240}" else "264"
        val url = "https://e-cdns-images.dzcdn.net/images/$type/$md5/${size}x${size}-000000-80-0-0.jpg"
        return url.toImageHolder()
    }
}