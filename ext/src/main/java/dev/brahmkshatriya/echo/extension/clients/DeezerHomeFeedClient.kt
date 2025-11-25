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
            "b21892d3-7e9c-4b06-aff6-2c3be3266f68", "348128f5-bed6-4ccb-9a37-8e5f5ed08a62",
            "8d10a320-f130-4dcb-a610-38baf0c57896", "2a7e897f-9bcf-4563-8e11-b93a601766e1",
            "7a65f4ed-71e1-4b6e-97ba-4de792e4af62", "25f9200f-1ce0-45eb-abdc-02aecf7604b2",
            "c320c7ad-95f5-4021-8de1-cef16b053b6d", "b2e8249f-8541-479e-ab90-cf4cf5896cbc",
            "927121fd-ef7b-428e-8214-ae859435e51c",
            // Premium Users
            "b184d536-761e-40fb-b0cc-55d6c81b4d45", "19ebda68-51fd-4de6-af40-6da6a76c85ba",
            "8e59d00d-63a7-4aff-8d47-d19293f738b2", "9f425321-7757-4db4-9a2a-76892684fdc0",
            "33d3a14c-5716-4343-87e1-4ec3ac4fd49b", "f7577bb4-5406-4aef-9db7-ed7418eb2827"
        )

        private val CATEGORY_MODULE_ID = setOf(
            // Free Users
            "868606eb-4afc-4e1a-b4e4-75b30da34ac8",
            // Premium Users
            "4f6321c0-21f5-474f-8156-9f6dd6222d7c"
        )
    }
}