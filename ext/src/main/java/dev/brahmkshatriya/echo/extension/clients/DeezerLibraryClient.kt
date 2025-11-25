package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerLibraryClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    private val tabs: List<Tab> = listOf(
        Tab(TabId.ALL.id, "All"),
        Tab(TabId.PLAYLISTS.id, "Playlists"),
        Tab(TabId.ALBUMS.id, "Albums"),
        Tab(TabId.TRACKS.id, "Tracks"),
        Tab(TabId.ARTISTS.id, "Artists"),
        Tab(TabId.SHOWS.id, "Podcasts")
    )

    private data class TabConfig(
        val id: TabId,
        val title: String,
        val request: suspend DeezerApi.() -> JsonObject,
        val extractor: (JsonObject) -> JsonArray?
    )

    private enum class TabId(val id: String) {
        ALL("all"),
        PLAYLISTS("playlists"),
        ALBUMS("albums"),
        TRACKS("tracks"),
        ARTISTS("artists"),
        SHOWS("shows")
    }

    private val configs: Map<String, TabConfig> = listOf(
        TabConfig(TabId.PLAYLISTS, "Playlists", { getPlaylists() }) { it.tabDataArray("playlists") },
        TabConfig(TabId.ALBUMS, "Albums", { getAlbums() }) { it.tabDataArray("albums") },
        TabConfig(TabId.TRACKS, "Tracks", { getTracks() }) { it.resultsDataArray() },
        TabConfig(TabId.ARTISTS, "Artists", { getArtists() }) { it.tabDataArray("artists") },
        TabConfig(TabId.SHOWS, "Podcasts", { getShows() }) { it.tabDataArray("shows") },
    ).associateBy { it.id.id }


    suspend fun loadLibraryFeed(): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        return Feed(tabs) { tab ->
            val id = tab?.id
            val data = when (id) {
                TabId.ALL.id -> loadAll() ?: emptyList()
                else -> loadSingle(id)
            }
            data.toFeedData(Feed.Buttons(showPlayAndShuffle = true))
        }
    }

    private suspend fun loadAll(): List<Shelf>? = supervisorScope {
        deezerExtension.handleArlExpiration()
        configs.values.map { cfg ->
            async(cpuDispatcher) {
                val json = cfg.request(api)
                val items = cfg.extractor(json) ?: return@async null
                parser.run { items.toShelfItemsList(cfg.title) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadSingle(id: String?): List<Shelf> {
        val cfg = configs[id] ?: return emptyList()
        deezerExtension.handleArlExpiration()
        val json = cfg.request(api)
        val arr = cfg.extractor(json) ?: return emptyList()
        return parser.run { arr.mapNotNull { it.jsonObject.toEchoMediaItem()?.toShelf() } }
    }

    private fun JsonObject.results(): JsonObject? = this["results"]?.jsonObject
    private fun JsonObject.resultsDataArray(): JsonArray? =
        results()?.get("data")?.jsonArray
    private fun JsonObject.tabDataArray(tabId: String): JsonArray? =
        results()?.get("TAB")?.jsonObject?.get(tabId)?.jsonObject?.get("data")?.jsonArray
}