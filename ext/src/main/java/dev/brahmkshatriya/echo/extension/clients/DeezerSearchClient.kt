package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils.asObject
import dev.brahmkshatriya.echo.extension.Utils.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class DeezerSearchClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val history: Boolean,
    private val parser: DeezerParser
) {

    @Volatile
    private var oldSearch: Triple<String, List<Shelf>, JsonObject?>? = null

    private fun JsonArray?.toQueryList(key: String, historyFlag: Boolean) =
        this?.mapNotNull { item ->
            item.asObject().str(key)?.let { QuickSearchItem.Query(it, historyFlag) }
        } ?: emptyList()

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        deezerExtension.handleArlExpiration()
        return if (query.isBlank()) {
            val jsonObject = api.getSearchHistory()
            val resultObject = jsonObject["results"]!!.asObject()
            val searchObject = resultObject["SEARCH_HISTORY"]?.asObject()
            val dataArray = searchObject?.get("data")?.jsonArray
            val trendingObject = resultObject["TRENDING_QUERIES"]?.asObject()
            val dataTrendingArray = trendingObject?.get("data")?.jsonArray
            dataArray.toQueryList("query", true) + dataTrendingArray.toQueryList("QUERY", false)
        } else {
            runCatching {
                val jsonObject = api.searchSuggestions(query)
                val resultObject = jsonObject["results"]?.asObject()
                val suggestionArray = resultObject?.get("SUGGESTION")?.jsonArray
                suggestionArray.toQueryList("QUERY", false)
            }.getOrElse {
                emptyList()
            }
        }
    }

    suspend fun loadSearchFeed(query: String, shelf: String): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        query.ifBlank {
            return Feed(PODCAST_TABS) { tab ->
                return@Feed when (tab!!.id) {
                    in "explore" -> browseFeed(shelf, "channels/explore/explore-tab").toFeedData()
                    in "podcasts" -> browseFeed(shelf, "channels/podcasts").toFeedData()
                    else -> { throw Exception() }
                }
            }
        }

        if (history) {
            api.setSearchHistory(query)
        }

        return Feed(loadSearchFeedTabs(query)) { tab ->
            if (tab?.id == "TOP_RESULT") return@Feed emptyList<Shelf>().toFeedData()

            val cached = oldSearch?.takeIf { it.first == query }

            if (tab?.id == "All") {
                return@Feed cached?.second?.toFeedData() ?: emptyList<Shelf>().toFeedData()
            }

            val resultObject = cached?.third
                ?: api.search(query)["results"]?.asObject()

            val dataArray = resultObject?.get(tab?.id ?: "")?.asObject()?.get("data")?.jsonArray

            return@Feed dataArray?.mapNotNull { item ->
                parser.run { item.asObject().toEchoMediaItem()?.toShelf() }
            }.orEmpty().toFeedData()
        }
    }

    private suspend fun browseFeed(shelf: String, page: String): List<Shelf> = supervisorScope {
        deezerExtension.handleArlExpiration()
        api.updateCountry()
        val browsePageResults = api.page(page)["results"]!!.asObject()
        val browseSections = browsePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())

        browseSections.mapNotNull { section ->
            val obj = section.asObject()
            val title = obj.str("title").orEmpty()
            when (obj.str("module_id")!!) {
                in EXPLORE_MODULE_ID -> async(dispatcher) {
                    parser.run {
                        section.toShelfCategoryList(title, shelf) { target ->
                            deezerExtension.channelFeed(target)
                        }
                    }
                }

                !in SKIP_MODULES_IDS -> async(dispatcher) {
                    parser.run {
                        section.toShelfItemsList(title)
                    }
                }

                else -> null
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun loadSearchFeedTabs(query: String): List<Tab> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]?.asObject()
        val orderObject = resultObject?.get("ORDER")?.jsonArray

        val tabs = orderObject?.mapNotNull { tab ->
            val tabId = tab.jsonPrimitive.content
            if (tabId !in SKIP_TAB_IDS) {
                Tab(
                    tabId,
                    tabId.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            } else {
                null
            }
        } ?: emptyList()

        val allShelves = tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.asObject()
            val dataArray = tabObject?.get("data")?.jsonArray
            parser.run {
                dataArray?.toShelfItemsList(
                    name.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            }
        }
        oldSearch = Triple(query, allShelves, resultObject)
        return listOf(Tab("All", "All")) + tabs
    }

    companion object {

        private val dispatcher = Dispatchers.Default

        private val PODCAST_TABS = mutableListOf(
            Tab("explore", "Explore"), Tab("podcasts", "Podcasts")
        )
        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")

        private val SKIP_MODULES_IDS =
            setOf(
                "6550abfd-15e4-47de-a5e8-a60e27fa152a",
                "c8b406d4-5293-4f59-a0f4-562eba496a0b",
                "8fd41184-8712-480f-b7c9-9d5f79a6ad63",
                "ad376a92-472b-426a-897f-72936dfaac47"
            )

        private val EXPLORE_MODULE_ID = setOf(
            "8b2c6465-874d-4752-a978-1637ca0227b5",
            "0491bd50-6fcf-4eb9-949d-7aa6c2ab8cc3",
            "921bf742-3c62-4002-952f-a9eff7fc9401"
        )
    }
}