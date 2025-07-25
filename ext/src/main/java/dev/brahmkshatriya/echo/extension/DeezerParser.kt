package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
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
import kotlinx.serialization.json.long
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeezerParser(private val session: DeezerSession) {

    fun JsonElement.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val itemsArray = jsonObject["items"]?.jsonArray ?: return null
        val list = itemsArray.mapObjects { it.toEchoMediaItem() }
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
        val list = mapObjects { it.toEchoMediaItem() }
        return if (list.isNotEmpty()) {
            Shelf.Lists.Items(
                title = name,
                list = list
            )
        } else {
            null
        }
    }

    inline fun JsonElement.toShelfCategoryList(
        name: String = "Unknown",
        shelf: String,
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Lists.Categories {
        val itemsArray = jsonObject["items"]?.jsonArray ?: return Shelf.Lists.Categories(name, emptyList())
        val listType = if (shelf.contains("grid")) Shelf.Lists.Type.Grid else Shelf.Lists.Type.Linear
        return Shelf.Lists.Categories(
            title = name,
            list = itemsArray.take(6).mapNotNull { it.jsonObject.toShelfCategory(block) },
            type = listType,
            more = PagedData.Single {
                itemsArray.mapNotNull { it.jsonObject.toShelfCategory(block) }
            }
        )
    }

    inline fun JsonObject.toShelfCategory(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category? {
        val data = this["data"]?.jsonObject ?: this
        val type = data["__TYPE__"]?.jsonPrimitive?.content ?: return null
        return when {
            "channel" in type -> toChannel(block)
            else -> null
        }
    }

    inline fun JsonObject.toChannel(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category {
        val data = this["data"]?.jsonObject ?: this
        val title = data["title"]?.jsonPrimitive?.content.orEmpty()
        val target = this["target"]?.jsonPrimitive?.content.orEmpty()
        return Shelf.Category(
            title = title,
            items = PagedData.Single { block(target) },
        )
    }

    fun JsonObject.toEchoMediaItem(): EchoMediaItem? {
        val data = this["data"]?.jsonObject ?: this
        return data["__TYPE__"]?.jsonPrimitive?.content?.let { type ->
            when {
                "playlist" in type -> EchoMediaItem.Lists.PlaylistItem(toPlaylist())
                "album" in type -> EchoMediaItem.Lists.AlbumItem(toAlbum())
                "song" in type -> EchoMediaItem.TrackItem(toTrack())
                "artist" in type -> EchoMediaItem.Profile.ArtistItem(toArtist(isShelfItem = true))
                "show" in type -> EchoMediaItem.Lists.AlbumItem(toShow())
                "episode" in type -> EchoMediaItem.TrackItem(toEpisode())
                "flow" in type -> EchoMediaItem.Lists.RadioItem(toRadio())
                else -> null
            }
        }
    }

    fun JsonObject.toShow(): Album = unwrapAnd { data ->
        val md5 = data["SHOW_ART_MD5"]?.jsonPrimitive?.content.orEmpty()
        return Album(
            id = data["SHOW_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["SHOW_NAME"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "talk", ),
            tracks = this["EPISODES"]?.jsonObject?.get("total")?.jsonPrimitive?.int,
            artists = listOf(Artist(id = "", name = "")),
            description = data["SHOW_DESCRIPTION"]?.jsonPrimitive?.content.orEmpty(),
            extras = mapOf("__TYPE__" to "show")
        )
    }

    fun JsonObject.toEpisode(): Track = unwrapAnd { data ->
        val md5 = data["SHOW_ART_MD5"]?.jsonPrimitive?.content.orEmpty()
        val title = data["EPISODE_TITLE"]?.jsonPrimitive?.content.orEmpty()
        return Track(
            id = data["EPISODE_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = title,
            cover = getCover(md5, "talk"),
            duration = data["DURATION"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            streamables = listOf(
                Streamable.server(
                    id = data["EPISODE_DIRECT_STREAM_URL"]?.jsonPrimitive?.content.orEmpty(),
                    title = title,
                    quality = 12
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

    fun JsonObject.toAlbum(): Album = unwrapAnd { data ->
        val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val artistArray = data["ARTISTS"]?.jsonArray.orEmpty()
        val tracks = this["SONGS"]?.jsonObject?.get("total")?.jsonPrimitive?.int
        val rd = data["ORIGINAL_RELEASE_DATE"]?.jsonPrimitive?.content?.toDate() ?: data["PHYSICAL_RELEASE_DATE"]?.jsonPrimitive?.content?.toDate()
        return Album(
            id = data["ALB_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["ALB_TITLE"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "cover"),
            tracks = tracks,
            artists = artistArray.map { artistObject ->
                Artist(
                    id = artistObject.jsonObject["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
                    name = artistObject.jsonObject["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
                    cover = getCover(artistObject.jsonObject["ART_PICTURE"]?.jsonPrimitive?.content.orEmpty(), "artist")
                )
            },
            releaseDate = rd,
            description = this["description"]?.jsonPrimitive?.content.orEmpty(),
            subtitle = this["subtitle"]?.jsonPrimitive?.content ?: if (tracks != null && rd != null) "$tracks Songs • $rd" else if(tracks != null) "$tracks Songs" else rd?.toString()
        )
    }

    fun JsonObject.toArtist(isFollowing: Boolean = false, isShelfItem: Boolean = false): Artist {
        val artistData = when {
            isShelfItem && this["data"]?.jsonObject == null -> this
            this["DATA"]?.jsonObject?.get("ART_BANNER") == null -> this["DATA"]?.jsonObject ?: this["data"]?.jsonObject ?: this
            else -> this["data"]?.jsonObject ?: this
        }
        val md5 = artistData["ART_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val description = if (this["BIO"] is JsonObject) {
            val bio = this["BIO"]!!.jsonObject
            (bio["BIO"]?.jsonPrimitive?.content.orEmpty().replace("<br />", "").replace("\\n", "")) +
                    (bio["RESUME"]?.jsonPrimitive?.content.orEmpty().replace("<p>", "").replace("</p>", ""))
        } else ""
        return Artist(
            id = artistData["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
            name = artistData["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, "artist"),
            followers = artistData["NB_FAN"]?.jsonPrimitive?.int,
            description = description,
            subtitle = this["subtitle"]?.jsonPrimitive?.content,
            isFollowing = isFollowing
        )
    }

    fun JsonObject.toTrack(): Track {
        val data = this["data"]?.jsonObject ?: this
        val md5 = data["ALB_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val artistArray = data["ARTISTS"]?.jsonArray.orEmpty()
        val version = data["VERSION"]?.jsonPrimitive?.content.orEmpty()
        val date = data["DATE_ADD"]?.jsonPrimitive?.content
        val releaseData = if (date?.contains("-") == true) {
            date.toDate()
        } else {
            date?.toLong()?.toDate()
        }
        return Track(
            id = data["SNG_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["SNG_TITLE"]?.jsonPrimitive?.content.orEmpty() + if(version.isNotEmpty()) { " $version" } else { "" } ,
            cover = getCover(md5, "cover"),
            duration = data["DURATION"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
            releaseDate = releaseData,
            artists = if (artistArray.isNotEmpty())
                artistArray.map { artistObject ->
                Artist(
                    id = artistObject.jsonObject["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
                    name = artistObject.jsonObject["ART_NAME"]?.jsonPrimitive?.content.orEmpty(),
                    cover = getCover(artistObject.jsonObject["ART_PICTURE"]?.jsonPrimitive?.content.orEmpty(), "artist")
                )
            } else listOf(
                Artist(
                    id = data.jsonObject["ART_ID"]?.jsonPrimitive?.content.orEmpty(),
                    name = data.jsonObject["ART_NAME"]?.jsonPrimitive?.content.orEmpty()
                )
            ),
            album = Album(
                id = data["ALB_ID"]?.jsonPrimitive?.content.orEmpty(),
                title = data["ALB_TITLE"]?.jsonPrimitive?.content.orEmpty(),
                cover = getCover(md5, "cover")
            ),
            albumNumber = data["TRACK_NUMBER"]?.jsonPrimitive?.content?.toLong(),
            albumDiscNumber = data["DISK_NUMBER"]?.jsonPrimitive?.content?.toLong(),
            irsc = data["ISRC"]?.jsonPrimitive?.content,
            isExplicit = data["EXPLICIT_LYRICS"]?.jsonPrimitive?.content?.equals("1") ?: false,
            extras = mapOf(
                "FALLBACK_ID" to data["FALLBACK"]?.jsonObject?.get("SNG_ID")?.jsonPrimitive?.content.orEmpty(),
                "TRACK_TOKEN" to data["TRACK_TOKEN"]?.jsonPrimitive?.content.orEmpty(),
                "FILESIZE_MP3_MISC" to (data["FILESIZE_MP3_MISC"]?.jsonPrimitive?.content ?: "0"),
                "MD5" to md5,
                "TYPE" to "cover"
            )
        )
    }

    fun JsonObject.toPlaylist(): Playlist = unwrapAnd { data ->
        val type = data["PICTURE_TYPE"]?.jsonPrimitive?.content.orEmpty()
        val md5 = data["PLAYLIST_PICTURE"]?.jsonPrimitive?.content.orEmpty()
        val userID = data["PARENT_USER_ID"]?.jsonPrimitive?.content
        val userSID = session.credentials.userId
        return Playlist(
            id = data["PLAYLIST_ID"]?.jsonPrimitive?.content.orEmpty(),
            title = data["TITLE"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, type),
            description = data["DESCRIPTION"]?.jsonPrimitive?.content.orEmpty(),
            subtitle = this["subtitle"]?.jsonPrimitive?.content,
            isEditable = userID?.contains(userSID) == true,
            tracks = data["NB_SONG"]?.jsonPrimitive?.int ?: 0,
            creationDate = data["DATE_ADD"]?.jsonPrimitive?.content?.toDate(),
        )
    }

    private fun String.toDate(): EchoDate = EchoDate(
        year = substringBefore("-").toInt(),
        month = substringAfter("-").substringBeforeLast("-").toInt(),
        day = substringAfterLast("-").substringBefore(" ").toInt()
    )

    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun Long.toDate(): EchoDate {
        val date = Date(this * 1000)
        return simpleDateFormat.format(date).toDate()
    }

    private fun JsonObject.toRadio(): Radio {
        val data = this["data"]?.jsonObject ?: this
        val imageObject = this["pictures"]?.jsonArray?.firstOrNull()?.jsonObject.orEmpty()
        val md5 = imageObject["md5"]?.jsonPrimitive?.content.orEmpty()
        val type = imageObject["type"]?.jsonPrimitive?.content.orEmpty()
        return Radio(
            id = data["id"]?.jsonPrimitive?.content.orEmpty(),
            title = data["title"]?.jsonPrimitive?.content.orEmpty(),
            cover = getCover(md5, type),
            extras = mapOf(
                "radio" to "flow"
            )
        )
    }

    private val quality: Int?
        get() = session.settings?.getInt("image_quality")

    private fun getCover(md5: String?, type: String?): ImageHolder {
        val size = quality ?: 240
        val url = "https://cdn-images.dzcdn.net/images/$type/$md5/${size}x${size}-000000-80-0-0.jpg"
        return url.toImageHolder()
    }

    private inline fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T?): List<T> =
        mapNotNull { it.jsonObject.let(transform) }

    private inline fun <R> JsonObject.unwrapAnd(transform: (JsonObject) -> R): R =
        (this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this).let(transform)
}