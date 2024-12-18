package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class DeezerSearchClient(private val api: DeezerApi, private val history: Boolean, private val parser: DeezerParser) {

    @Volatile
    private var oldSearch: Pair<String, List<Shelf>>? = null

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        DeezerExtension().handleArlExpiration()
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

    fun searchFeed(query: String, tab: Tab?): PagedData.Single<Shelf> = PagedData.Single {
        DeezerExtension().handleArlExpiration()
        query.ifBlank { return@Single browseFeed() }

        if (history) {
            api.setSearchHistory(query)
        }
        oldSearch?.takeIf { it.first == query && (tab == null || tab.id == "All") }?.second?.let {
            return@Single it
        }

        if (tab?.id == "TOP_RESULT") return@Single emptyList()

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

        processSearchResults(resultObject ?: JsonObject(emptyMap()))
    }

    private suspend fun browseFeed(): List<Shelf> {
        DeezerExtension().handleArlExpiration()
        api.updateCountry()
        val jsonObject = api.page("channels/explore/explore-tab")
        val browsePageResults = jsonObject["results"]!!.jsonObject
        val browseSections = browsePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())
        return browseSections.mapNotNull { section ->
            val id = section.jsonObject["module_id"]!!.jsonPrimitive.content
            when (id) {
                "67aa1c1b-7873-488d-88a0-55b6596cf4d6", "486313b7-e3c7-453d-ba79-27dc6bea20ce",
                "1d8dfed4-582f-40e1-b29c-760b44c0301e", "ecb89e7c-1c07-4922-aa50-d29745576636",
                "64ac680b-7c84-49a3-9077-38e9b653332e" -> {
                    parser.run {
                        section.toShelfItemsList(section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty())
                    }
                }

                "8b2c6465-874d-4752-a978-1637ca0227b5" -> {
                    parser.run {
                        section.toShelfCategoryList(section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()) { target ->
                            DeezerExtension().channelFeed(target)
                        }
                    }
                }

                else -> null
            }
        }
    }

    suspend fun searchTabs(query: String): List<Tab> {
        DeezerExtension().handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]?.jsonObject
        val orderObject = resultObject?.get("ORDER")?.jsonArray

        val tabs = coroutineScope {
            orderObject?.mapNotNull {
                async {
                    val tabId = it.jsonPrimitive.content
                    if (tabId != "TOP_RESULT" && tabId != "FLOW_CONFIG") {
                        Tab(tabId, tabId.lowercase().capitalize(Locale.ROOT))
                    } else {
                        null
                    }
                }
            }?.awaitAll()?.filterNotNull() ?: emptyList()
        }

        oldSearch = query to tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.jsonObject
            val dataArray = tabObject?.get("data")?.jsonArray
            parser.run {
                dataArray?.toShelfItemsList(name.lowercase().capitalize(Locale.ROOT))
            }
        }
        return listOf(Tab("All", "All")) + tabs
    }
}