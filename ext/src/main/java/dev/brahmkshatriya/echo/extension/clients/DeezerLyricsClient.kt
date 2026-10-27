package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.Utils.asObject
import dev.brahmkshatriya.echo.extension.Utils.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class DeezerLyricsClient(private val api: DeezerApi) {

    fun searchTrackLyrics(track: Track): Feed<Lyrics> = PagedData.Single {
        try {
            val jsonObject = api.lyrics(track.id)
            val dataObject = jsonObject["data"]!!.asObject()
            val trackObject = dataObject["track"]!!.asObject()
            val lyricsObject = trackObject["lyrics"]!!.asObject()
            val lyricsId = lyricsObject.str("id").orEmpty()
            val lyrics = if (lyricsObject["synchronizedLines"] != JsonNull) {
                val linesArray = lyricsObject["synchronizedLines"]!!.jsonArray
                val lyrics = linesArray.map { lineObj ->
                    val line = lineObj.asObject().str("line").orEmpty()
                    val start = lineObj.asObject()["milliseconds"]?.jsonPrimitive?.int ?: 0
                    val end = lineObj.asObject()["duration"]?.jsonPrimitive?.int ?: 0
                    Lyrics.Item(line, start.toLong(), start.toLong() + end.toLong())
                }
                Lyrics.Timed(lyrics)
            } else {
                val lyricsText = lyricsObject.str("text")!!
                Lyrics.Simple(lyricsText)
            }
            listOf(Lyrics(lyricsId, track.title, lyrics = lyrics))
        } catch (e: Exception) {
            emptyList()
        }
    }.toFeed()
}