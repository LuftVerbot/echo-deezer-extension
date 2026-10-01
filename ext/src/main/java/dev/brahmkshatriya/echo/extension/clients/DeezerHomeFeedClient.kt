package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerHomeFeedClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    fun loadHomeFeed(shelf: String): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val homePageResults = api.page("home")["results"]?.jsonObject ?: JsonObject(emptyMap())
        val homeSections = homePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())

        supervisorScope {
            homeSections.mapNotNull { section ->
                val obj = section.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.optString("module_id") ?: return@mapNotNull null
                val title = obj.optString("title") ?: return@mapNotNull null

                when (id) {
                    in ITEM_MODULE_IDS -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                section.toShelfItemsList(title)
                            }
                        }.getOrNull()
                    }
                    in CATEGORY_MODULE_ID -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                section.toShelfCategoryList(title, shelf) { target ->
                                    deezerExtension.channelFeed(target)
                                }
                            }
                        }.getOrNull()
                    }
                    else -> null
                }
            }.awaitAll().filterNotNull()
        }
    }.toFeed()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.optString(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private val dispatcher = Dispatchers.Default
        private val ITEM_MODULE_IDS = setOf(
            // Free Users
            "b2a95246-f86f-41aa-b700-3474c66d8ff7", "4c4770ef-e1cf-40d4-b4e6-8620776b2350",
            "9e6ea72c-618f-42a6-b17e-e85b8b9e88b9", "096efd8f-5662-45b6-92db-3195ce556746",
            "c75e0541-47fe-402d-9412-78fcbb5c88de", "4bad552e-c627-41e1-b6fa-a5df202bdb9f",
            "f3aee56a-bd9d-4817-8b32-dd402f8df308", "03903603-87a7-4db3-ac18-1b276b9f7d13",
            "f0ed258e-9980-4ecb-84d6-64ef4ff5cf8e",
            // Premium Users
            "90c445b6-8960-4d80-978c-faf8869cc0b1", "6b4c1fda-a7d9-436e-9b73-0edc92ec9bf7",
            "9d5188cc-effd-4f17-a1ff-1fbc0be255f3", "98436aef-3b26-4237-bc0b-e63c92eb3885",
            "5b739620-33bd-45ee-afbc-8d032abfb955", "5aca0bf5-4199-4881-bd1f-a51e1b7cdc7c",
            "984b158b-712c-46d7-b7db-17dc52f4236b", "a6466435-05df-4bef-88e1-5910f6bb8e43",
            "3a426bf8-7d1e-4cbd-8992-f408cb8be2c4", "c7006dad-b525-45a9-8818-54c83ad487c4",
            "4776acd2-8488-4653-a2f1-0605a2e4768c", "fa48e6cb-b99b-4eb9-8965-44b1e9c58acc"
        )

        private val CATEGORY_MODULE_ID = setOf(
            // Free Users
            "bb5c8bd7-e6fc-40ad-920e-8e1469c1bd38",
            // Premium Users
            "285f84ae-9366-4042-9131-a2986b4084b9"
        )
    }
}
