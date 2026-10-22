package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeezerParser(private val session: DeezerSession) {

    fun JsonElement.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val items = obj()["items"]?.jsonArray?.mapObjects { it.toEchoMediaItem() }.orEmpty()
        return items.takeIf { it.isNotEmpty() }?.let {
            Shelf.Lists.Items(id = name, title = name, list = it)
        }
    }

    fun JsonObject.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val item = toEchoMediaItem() ?: return null
        return Shelf.Lists.Items(id = name, title = name, list = listOf(item))
    }

    fun JsonArray.toShelfItemsList(name: String = "Unknown"): Shelf? {
        val items = mapObjects { it.toEchoMediaItem() }
        return items.takeIf { it.isNotEmpty() }?.let {
            Shelf.Lists.Items(id = name, title = name, list = it)
        }
    }

    inline fun JsonElement.toShelfCategoryList(
        name: String = "Unknown",
        shelf: String,
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Lists.Categories {
        val arr = obj()["items"]?.jsonArray ?: return Shelf.Lists.Categories(name, name, emptyList())
        val listType = if ("grid" in shelf) Shelf.Lists.Type.Grid else Shelf.Lists.Type.Linear
        val cats = arr.mapNotNull { it.jsonObject.toShelfCategory(block) }
        return Shelf.Lists.Categories(
            id = name,
            title = name,
            list = cats.take(6),
            type = listType,
            more = cats.toFeed()
        )
    }

    inline fun JsonObject.toShelfCategory(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category? {
        val data = unwrap()
        val type = data.str("__TYPE__") ?: return null
        return when {
            "channel" in type -> toChannel(block)
            else -> null
        }
    }

    inline fun JsonObject.toChannel(
        crossinline block: suspend (String) -> List<Shelf>
    ): Shelf.Category {
        val data = unwrap()
        val title = data.str("title").orEmpty()
        val target = str("target").orEmpty()
        return Shelf.Category(
            id = title,
            title = title,
            feed = Feed(emptyList()) { block(target).toFeedData() }
        )
    }

    fun JsonObject.toEchoMediaItem(): EchoMediaItem? {
        val data = unwrap()
        return when (val t = data.str("__TYPE__")) {
            null -> null
            else -> when {
                "playlist" in t -> toPlaylist()
                "album" in t -> toAlbum()
                "song" in t -> toTrack()
                "artist" in t -> toArtist(isShelfItem = true)
                "show" in t -> toShow()
                "episode" in t -> toEpisode()
                "flow" in t -> toRadio()
                else -> null
            }
        }
    }

    fun JsonObject.toShow(): Album = unwrap().let { data ->
        val md5 = data.str("SHOW_ART_MD5")
        Album(
            id = data.str("SHOW_ID").orEmpty(),
            type = Album.Type.Show,
            title = data.str("SHOW_NAME").orEmpty(),
            cover = getCover(md5, "talk"),
            trackCount = obj()["EPISODES"]?.jsonObject?.int("total")?.toLong(),
            artists = emptyList(),
            description = data.str("SHOW_DESCRIPTION").orEmpty(),
            extras = mapOf("__TYPE__" to "show")
        )
    }

    fun JsonObject.toEpisode(bookmark: Map<String?, Long?> = emptyMap()): Track = unwrap().let { data ->
        val md5 = data.str("SHOW_ART_MD5")
        val title = data.str("EPISODE_TITLE").orEmpty()
        val id = data.str("EPISODE_ID").orEmpty()
        Track(
            id = id,
            title = title,
            type = Track.Type.Podcast,
            cover = getCover(md5, "talk"),
            duration = data.long("DURATION")?.times(1000),
            playedDuration = bookmark[id]?.times(1000),
            streamables = listOf(
                Streamable.server(
                    id = data.str("EPISODE_DIRECT_STREAM_URL").orEmpty(),
                    title = title,
                    quality = 12
                )
            ),
            extras = mapOf(
                "TRACK_TOKEN" to data.str("TRACK_TOKEN").orEmpty(),
                "FILESIZE_MP3_MISC" to (data.str("FILESIZE_MP3_MISC") ?: "0"),
                "TYPE" to "talk",
                "__TYPE__" to "show"
            )
        )
    }

    fun JsonObject.toAlbum(): Album = unwrap().let { data ->
        val md5 = data.str("ALB_PICTURE")
        val artistsArr = data.arr("ARTISTS").orEmpty()
        val trackCount = obj()["SONGS"]?.jsonObject?.int("total")
        val rd = data.str("ORIGINAL_RELEASE_DATE")?.toDate()
            ?: data.str("PHYSICAL_RELEASE_DATE")?.toDate()
        Album(
            id = data.str("ALB_ID").orEmpty(),
            title = data.str("ALB_TITLE").orEmpty(),
            cover = getCover(md5, "cover"),
            trackCount = trackCount?.toLong(),
            artists = artistsArr.map { artist ->
                val obj = artist.jsonObject
                Artist(
                    id = obj.str("ART_ID").orEmpty(),
                    name = obj.str("ART_NAME").orEmpty(),
                    cover = getCover(obj.str("ART_PICTURE"), "artist")
                )
            },
            releaseDate = rd,
            description = str("description").orEmpty(),
            subtitle = str("subtitle")
                ?: when {
                    trackCount != null && rd != null -> "$trackCount Songs • $rd"
                    trackCount != null -> "$trackCount Songs"
                    else -> rd?.toString()
                }
        )
    }

    fun JsonObject.toArtist(isShelfItem: Boolean = false): Artist {
        val artistData = when {
            isShelfItem && this["data"] == null -> this
            this["DATA"]?.jsonObject?.get("ART_BANNER") == null -> this["DATA"]?.jsonObject ?: this["data"]?.jsonObject ?: this
            else -> this["data"]?.jsonObject ?: this
        }
        val md5 = artistData.str("ART_PICTURE")
        val bio = if (this["BIO"] is JsonObject) {
            val b = this["BIO"]!!.jsonObject
            val p1 = b.str("BIO").orEmpty().replace("<br />", "").replace("\\n", "")
            val p2 = b.str("RESUME").orEmpty().replace("<p>", "").replace("</p>", "")
            p1 + p2
        } else ""
        return Artist(
            id = artistData.str("ART_ID").orEmpty(),
            name = artistData.str("ART_NAME").orEmpty(),
            cover = getCover(md5, "artist"),
            bio = bio,
            subtitle = str("subtitle"),
            extras = mapOf("followers" to (artistData.int("NB_FAN")?.toString() ?: "0"))
        )
    }

    fun JsonObject.toTrack(): Track {
        val data = unwrap()
        val md5 = data.str("ALB_PICTURE")
        val artistsArr = data.arr("ARTISTS")
        val version = data.str("VERSION")
        return Track(
            id = data.str("SNG_ID").orEmpty(),
            title = buildString {
                append(data.str("SNG_TITLE").orEmpty())
                if (!version.isNullOrEmpty()) append(" ").append(version)
            },
            cover = getCover(md5, "cover"),
            duration = data.long("DURATION")?.times(1000),
            releaseDate = data.str("DATE_ADD")?.let { parseDate(it) },
            artists = parseArtists(artistsArr, data),
            album = Album(
                id = data.str("ALB_ID").orEmpty(),
                title = data.str("ALB_TITLE").orEmpty(),
                cover = getCover(md5, "cover")
            ),
            albumOrderNumber = data.long("TRACK_NUMBER"),
            albumDiscNumber = data.long("DISK_NUMBER"),
            isrc = data.str("ISRC"),
            isExplicit = data.str("EXPLICIT_LYRICS") == "1",
            extras = buildMap {
                put("FALLBACK_ID", data["FALLBACK"]?.jsonObject?.str("SNG_ID").orEmpty())
                put("TRACK_TOKEN", data.str("TRACK_TOKEN").orEmpty())
                put("FILESIZE_MP3_MISC", data.str("FILESIZE_MP3_MISC") ?: "0")
                put("TYPE", "cover")
            }
        )
    }

    fun JsonObject.toPlaylist(): Playlist = unwrap().let { data ->
        val type = data.str("PICTURE_TYPE").orEmpty()
        val md5 = data.str("PLAYLIST_PICTURE").orEmpty()
        val parentUser = data.str("PARENT_USER_ID")
        val tracks = when(this["SONGS"]) {
            is JsonArray -> {
                int("NB_SONG")
            }
            else -> this["SONGS"]?.jsonObject?.int("total")
        }
        val created = data.str("DATE_ADD")?.toDate()
        Playlist(
            id = data.str("PLAYLIST_ID").orEmpty(),
            title = data.str("TITLE").orEmpty(),
            cover = getCover(md5, type),
            description = data.str("DESCRIPTION").orEmpty(),
            subtitle = str("subtitle") ?: when {
                tracks != null && created != null -> "$tracks Songs • $created"
                tracks != null -> "$tracks Songs"
                else -> created?.toString()
            },
            isEditable = parentUser?.contains(session.credentials.userId) == true,
            trackCount = tracks?.toLong(),
            creationDate = created
        )
    }

    private fun JsonObject.toRadio(): Radio {
        val data = unwrap()
        val image = this["pictures"]?.jsonArray?.firstOrNull()?.jsonObject
        val md5 = image?.str("md5")
        val type = image?.str("type")
        return Radio(
            id = data.str("id").orEmpty(),
            title = data.str("title").orEmpty(),
            cover = getCover(md5, type),
            extras = mapOf("radio" to "flow")
        )
    }

    private fun getCover(md5: String?, type: String?): ImageHolder? {
        if (md5.isNullOrEmpty() || type.isNullOrEmpty()) return null
        val size = session.settings?.getInt("image_quality") ?: 240
        val url = "https://cdn-images.dzcdn.net/images/$type/$md5/${size}x${size}-000000-80-0-0.jpg"
        return url.toImageHolder()
    }

    private val sdf by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }

    private fun String.toDate(): EchoDate {
        // "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss"
        val y = substringBefore("-").toInt()
        val m = substringAfter("-").substringBeforeLast("-").toInt()
        val d = substringAfterLast("-").substringBefore(" ").toInt()
        return EchoDate(year = y, month = m, day = d)
    }

    private fun Long.toDate(): EchoDate {
        val date = Date(this * 1000)
        return sdf.format(date).toDate()
    }

    private fun parseDate(raw: String): EchoDate? =
        if ("-" in raw) raw.toDate() else raw.toLongOrNull()?.toDate()

    private fun parseArtists(arr: JsonArray?, data: JsonObject): List<Artist> {
        return if (!arr.isNullOrEmpty()) {
            arr.mapNotNull {
                val o = it.jsonObject
                Artist(
                    id = o.str("ART_ID").orEmpty(),
                    name = o.str("ART_NAME").orEmpty(),
                    cover = getCover(o.str("ART_PICTURE"), "artist")
                )
            }
        } else {
            listOf(
                Artist(
                    id = data.str("ART_ID").orEmpty(),
                    name = data.str("ART_NAME").orEmpty()
                )
            )
        }
    }

    fun JsonElement.obj(): JsonObject = jsonObject
    fun JsonObject.unwrap(): JsonObject = this["data"]?.jsonObject ?: this["DATA"]?.jsonObject ?: this

    fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull()
    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
    private fun JsonObject.arr(key: String): JsonArray? = this[key]?.jsonArray

    private fun JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()

    private inline fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T?): List<T> =
        mapNotNull { runCatching { transform(it.jsonObject) }.getOrNull() }
}