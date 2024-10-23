package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Lyric
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerLyricsClient(private val api: DeezerApi) {

    fun searchTrackLyrics(track: Track): PagedData.Single<Lyrics> = PagedData.Single {
        try {
            val jsonObject = api.lyrics(track.id)
            val dataObject = jsonObject["data"]!!.jsonObject
            val trackObject = dataObject["track"]!!.jsonObject
            val lyricsObject = trackObject["lyrics"]!!.jsonObject
            val lyricsId = lyricsObject["id"]?.jsonPrimitive?.content ?: ""
            val lyrics = if (lyricsObject["synchronizedLines"] != JsonNull) {
                val linesArray = lyricsObject["synchronizedLines"]!!.jsonArray
                linesArray.map { lineObj ->
                    val line = lineObj.jsonObject["line"]?.jsonPrimitive?.content ?: ""
                    val start = lineObj.jsonObject["milliseconds"]?.jsonPrimitive?.int ?: 0
                    val end = lineObj.jsonObject["duration"]?.jsonPrimitive?.int ?: 0
                    Lyric(line, start.toLong(), start.toLong() + end.toLong())
                }
            } else {
                val lyricsText = lyricsObject["text"]!!.jsonPrimitive.content
                listOf(Lyric(lyricsText, 0, Long.MAX_VALUE))
            }
            listOf(Lyrics(lyricsId, track.title, lyrics = lyrics))
        } catch (e: Exception) {
            emptyList()
        }
    }
}