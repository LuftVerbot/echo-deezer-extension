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
    private var oldSearch: Triple<String, List<Shelf>, JsonObject?>? = null

    private fun JsonArray?.toQueryList(key: String, historyFlag: Boolean) =
        this?.mapNotNull { item ->
            item.jsonObject[key]?.jsonPrimitive?.content?.let { QuickSearchItem.Query(it, historyFlag) }
        } ?: emptyList()

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        deezerExtension.handleArlExpiration()
        return if (query.isBlank()) {
            val jsonObject = api.getSearchHistory()
            val resultObject = jsonObject["results"]!!.jsonObject
            val searchObject = resultObject["SEARCH_HISTORY"]?.jsonObject
            val dataArray = searchObject?.get("data")?.jsonArray
            val trendingObject = resultObject["TRENDING_QUERIES"]?.jsonObject
            val dataTrendingArray = trendingObject?.get("data")?.jsonArray
            dataArray.toQueryList("query", true) + dataTrendingArray.toQueryList("QUERY", false)
        } else {
            runCatching {
                val jsonObject = api.searchSuggestions(query)
                val resultObject = jsonObject["results"]?.jsonObject
                val suggestionArray = resultObject?.get("SUGGESTION")?.jsonArray
                suggestionArray.toQueryList("QUERY", false)
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

            val cached = oldSearch?.takeIf { it.first == query }

            if (tab?.id == "All") {
                return@Feed cached?.second?.toFeedData() ?: emptyList<Shelf>().toFeedData()
            }

            val resultObject = cached?.third
                ?: api.search(query)["results"]?.jsonObject

            val dataArray = resultObject?.get(tab?.id ?: "")?.jsonObject?.get("data")?.jsonArray

            return@Feed dataArray?.mapNotNull { item ->
                parser.run { item.jsonObject.toEchoMediaItem()?.toShelf() }
            }.orEmpty().toFeedData()
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

        val allShelves = tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.jsonObject
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
        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")

        private val SKIP_MODULES_IDS =
            setOf("6550abfd-15e4-47de-a5e8-a60e27fa152a", "c8b406d4-5293-4f59-a0f4-562eba496a0b")

        private const val EXPLORE_MODULE_ID = "8b2c6465-874d-4752-a978-1637ca0227b5"
    }
}