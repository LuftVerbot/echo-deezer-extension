package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class DeezerSearchClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val history: Boolean, private val parser: DeezerParser) {

    @Volatile
    private var oldSearch: Pair<String, List<Shelf>>? = null

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        deezerExtension.handleArlExpiration()
        return if (query.isBlank()) {
            val queryList = mutableListOf<QuickSearchItem.Query>()
            val jsonObject = api.getSearchHistory()
            val resultObject = jsonObject["results"]!!.jsonObject
            val searchObject = resultObject["SEARCH_HISTORY"]?.jsonObject
            val dataArray = searchObject?.get("data")?.jsonArray
            val historyList = dataArray?.mapNotNull { item ->
                val queryItem = item.jsonObject["query"]?.jsonPrimitive?.content
                queryItem?.let { QuickSearchItem.Query(it, true) }
            } ?: emptyList()
            queryList.addAll(historyList)
            val trendingObject = resultObject["TRENDING_QUERIES"]?.jsonObject
            val dataTrendingArray = trendingObject?.get("data")?.jsonArray
            val trendingList = dataTrendingArray?.mapNotNull { item ->
                val queryItem = item.jsonObject["QUERY"]?.jsonPrimitive?.content
                queryItem?.let { QuickSearchItem.Query(it, false) }
            } ?: emptyList()
            queryList.addAll(trendingList)
            queryList
        } else {
            runCatching {
                val jsonObject = api.searchSuggestions(query)
                val resultObject = jsonObject["results"]?.jsonObject
                val suggestionArray = resultObject?.get("SUGGESTION")?.jsonArray
                suggestionArray?.mapNotNull { item ->
                    val queryItem = item.jsonObject["QUERY"]?.jsonPrimitive?.content
                    queryItem?.let { QuickSearchItem.Query(it, false) }
                } ?: emptyList()
            }.getOrElse {
                emptyList()
            }
        }
    }

    suspend fun loadSearchFeed(query: String, shelf: String): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return browseFeed(shelf).toFeed() }

        if (history) {
            api.setSearchHistory(query)
        }

        return Feed(loadSearchFeedTabs(query)) { tab ->
            if (tab?.id == "TOP_RESULT") return@Feed emptyList<Shelf>().toFeedData()

            if (tab?.id == "All") {
                oldSearch?.takeIf { it.first == query }?.second.let {
                    return@Feed it?.toFeedData()!!
                }
            }

            val jsonObject = api.search(query)
            val resultObject = jsonObject["results"]?.jsonObject

            val processSearchResults: (JsonObject) -> List<Shelf> = { resultObj ->
                val tabObject = resultObj[tab?.id ?: ""]?.jsonObject
                val dataArray = tabObject?.get("data")?.jsonArray

                dataArray?.mapNotNull { item ->
                    parser.run {
                        item.jsonObject.toEchoMediaItem()?.toShelf()
                    }
                } ?: emptyList()
            }

            return@Feed processSearchResults(resultObject ?: JsonObject(emptyMap())).toFeedData()
        }
    }

    private suspend fun browseFeed(shelf: String): List<Shelf> {
        deezerExtension.handleArlExpiration()
        api.updateCountry()
        val jsonObject = api.page("channels/explore/explore-tab")
        val browsePageResults = jsonObject["results"]!!.jsonObject
        val browseSections = browsePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())
        return browseSections.mapNotNull { section ->
            val id = section.jsonObject["module_id"]!!.jsonPrimitive.content
            when (id) {
                EXPLORE_MODULE_ID -> {
                    parser.run {
                        section.toShelfCategoryList(section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty(), shelf) { target ->
                           deezerExtension.channelFeed(target)
                        }
                    }
                }

                !in SKIP_MODULES_IDS -> {
                    parser.run {
                        val secShelf =
                            section.toShelfItemsList(section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()) as? Shelf.Lists.Items
                                ?: return@run null
                        val list = secShelf.list
                        Shelf.Lists.Items(
                            id = secShelf.id,
                            title = secShelf.title,
                            subtitle = secShelf.subtitle,
                            type = Shelf.Lists.Type.Linear,
                            more = PagedData.Single<Shelf> {
                                list.map {
                                    it.toShelf()
                                }
                            }.toFeed(),
                            list = list
                        )
                    }
                }

                else -> null
            }
        }
    }

    suspend fun loadSearchFeedTabs(query: String): List<Tab> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]?.jsonObject
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

        oldSearch = query to tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.jsonObject
            val dataArray = tabObject?.get("data")?.jsonArray
            parser.run {
                dataArray?.toShelfItemsList(
                    name.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            }
        }
        return listOf(Tab("All", "All")) + tabs
    }

    companion object {
        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")

        private val SKIP_MODULES_IDS =
            setOf("6550abfd-15e4-47de-a5e8-a60e27fa152a", "c8b406d4-5293-4f59-a0f4-562eba496a0b")

        private const val EXPLORE_MODULE_ID = "8b2c6465-874d-4752-a978-1637ca0227b5"
    }
}