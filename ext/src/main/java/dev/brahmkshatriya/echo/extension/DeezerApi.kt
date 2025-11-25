package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.DeezerSession.DeezerCredentials
import dev.brahmkshatriya.echo.extension.api.DeezerAlbum
import dev.brahmkshatriya.echo.extension.api.DeezerArtist
import dev.brahmkshatriya.echo.extension.api.DeezerMedia
import dev.brahmkshatriya.echo.extension.api.DeezerPlaylist
import dev.brahmkshatriya.echo.extension.api.DeezerRadio
import dev.brahmkshatriya.echo.extension.api.DeezerSearch
import dev.brahmkshatriya.echo.extension.api.DeezerShow
import dev.brahmkshatriya.echo.extension.api.DeezerTrack
import dev.brahmkshatriya.echo.extension.api.DeezerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DeezerApi(private val session: DeezerSession) {

    companion object {
        private val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            useArrayPolymorphism = true
        }

        private const val APP_API_KEY =
            "4VCYIJUCDLOUELGD1V8WBVYBNVDYOXEWSLLZDONGBBDFVXTZJRXPR29JRLQFO6ZE"
    }

    private val language: String
        get() = session.settings?.getString("lang") ?: Locale.getDefault().toLanguageTag()

    private val country: String
        get() = session.settings?.getString("country") ?: Locale.getDefault().country

    val langCode = language.substringBefore("-")

    private val credentials: DeezerCredentials
        get() = session.credentials

    private val arl: String
        get() = credentials.arl

    private val sid: String
        get() = credentials.sid

    private val token: String
        get() = credentials.token

    private val userId: String
        get() = credentials.userId

    private val licenseToken: String
        get() = credentials.licenseToken

    private val email: String
        get() = credentials.email

    private val pass: String
        get() = credentials.pass

    private fun createOkHttpClient(useProxy: Boolean, login: Boolean = false): OkHttpClient {
        val configuredProxy = session.settings
            ?.getString("proxy")
            .takeIf { !it.isNullOrEmpty() }
        return OkHttpClient.Builder().apply {
            if (useProxy && configuredProxy != null) {
                val proxy = if (login) "uk1.proxy.murglar.app" else configuredProxy
                sslSocketFactory(createTrustAllSslSocketFactory(), createTrustAllTrustManager())
                hostnameVerifier { _, _ -> true }
                proxy(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(proxy, 3128)
                    )
                )
            }
        }.build()
    }

    private fun createTrustAllSslSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(createTrustAllTrustManager())
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    @Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun createTrustAllTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    }

    private val client: OkHttpClient  by lazy { createOkHttpClient(useProxy = true) }
    private val clientLog: OkHttpClient by lazy { createOkHttpClient(useProxy = true , true) }
    private val clientNP: OkHttpClient by lazy { createOkHttpClient(useProxy = false) }

    private val staticHeaders: Headers by lazy {
        Headers.Builder().apply {
            add("Accept", "*/*")
            add("Accept-Language", "$language,*")
            add("Cache-Control", "max-age=0")
            add("Connection", "keep-alive")
            add("Content-Language", language)
            add("Content-Type", "application/json")
            add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36")
            add("x-deezer-user", userId)
            add("X-User-IP", "1.1.1.1")
            add("x-deezer-client-ip", "1.1.1.1")
        }.build()
    }

    private val staticAppHeaders: Headers by lazy {
        Headers.Builder().apply {
            add("Content-Type", "application/json")
            add("User-Agent", "Deezer/8.0.44.4 (Android; 12; Mobile; us) Google sdk_gphone64_x86_64")
        }.build()
    }

    private fun getHeaders(method: String? = ""): Headers {
        return staticHeaders.newBuilder().apply {
            if (method != "user.getArl") {
                add("Cookie", "arl=$arl; sid=$sid")
            } else {
                add("Cookie", "sid=$sid")
            }
        }.build()
    }

    suspend fun callApi(
        method: String,
        paramsBuilder: JsonObjectBuilder.() -> Unit = {},
        gatewayInput: String? = "",
        np: Boolean = false
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https").host("www.deezer.com")
            .addPathSegments("ajax/gw-light.php")
            .addQueryParameter("method", method)
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", token)
            .apply {
                if (!gatewayInput.isNullOrEmpty()) {
                    addQueryParameter("gateway_input", gatewayInput)
                }
            }
            .build()

        val requestBody =  encodeJson(paramsBuilder).toRequestBody()
        val request = Request.Builder()
            .url(url)
            .apply {
                if (method != "user.getArl") {
                    post(requestBody)
                } else {
                    get()
                }
                headers(getHeaders(method))
            }
            .build()

        val clientB = if (np) clientNP else client

        clientB.newCall(request).await().use { response ->
            val result = response.body.source().let {
                decodeJsonStream(it.inputStream())
            }
            if (!response.isSuccessful) throw Exception("API call failed with status ${response.code}: $result")

            if (method == "deezer.getUserData") {
                response.headers.forEach {
                    if (it.second.startsWith("sid=")) {
                        session.updateCredentials(sid = it.second.substringAfter("sid=").substringBefore(";"))
                    }
                }
            }

            when(result["error"]) {
                is JsonObject -> {
                    if (result["error"]?.jsonObject["VALID_TOKEN_REQUIRED"]?.jsonPrimitive?.content?.contains("Invalid CSRF token") == true) {
                        if (email.isEmpty() && pass.isEmpty()) {
                            session.isArlExpired(true)
                            throw Exception("Please re-login (Best use User + Pass method)")
                        } else {
                            session.isArlExpired(false)
                            val userList = DeezerExtension().onLogin("userPass", mapOf(Pair("email", email), Pair("pass", pass)))
                            DeezerExtension().setLoginUser(userList.first())
                            return@withContext callApi(method, paramsBuilder, gatewayInput)
                        }
                    }
                }
                else -> {
                    null
                }
            }
            result
        }
    }

    suspend fun callAppApi(
        method: String,
        paramsBuilder: JsonObjectBuilder.() -> Unit = {}
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https").host("api.deezer.com")
            .addPathSegments("1.0/gateway.php")
            .addQueryParameter("api_key", APP_API_KEY)
            .addQueryParameter("sid", sid)
            .addQueryParameter("method", method)
            .addQueryParameter("output", "3")
            .addQueryParameter("input", "3")
            .build()

        val requestBody =  encodeJson(paramsBuilder).toRequestBody()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .headers(staticAppHeaders)
            .build()

        clientNP.newCall(request).await().use { response ->
            response.body.source().let {
                decodeJsonStream(it.inputStream())
            }
        }
    }

    //<============= Login =============>

    suspend fun makeUser(email: String = "", pass: String = ""): List<User> {
        val userList = mutableListOf<User>()
        val jObject = callApi("deezer.getUserData")
        val userResults = jObject["results"]!!
        val userObject = userResults.jsonObject["USER"]!!
        val token = userResults.jsonObject["checkForm"]!!.jsonPrimitive.content
        val userId = userObject.jsonObject["USER_ID"]!!.jsonPrimitive.content
        val licenseToken = userObject.jsonObject["OPTIONS"]!!.jsonObject["license_token"]!!.jsonPrimitive.content
        val name = userObject.jsonObject["BLOG_NAME"]!!.jsonPrimitive.content
        val cover = userObject.jsonObject["USER_PICTURE"]!!.jsonPrimitive.content
        val user = User(
            id = userId,
            name = name,
            cover = "https://cdn-images.dzcdn.net/images/user/$cover/100x100-000000-80-0-0.jpg".toImageHolder(),
            extras = mapOf(
                "arl" to arl,
                "user_id" to userId,
                "sid" to sid,
                "token" to token,
                "license_token" to licenseToken,
                "email" to email,
                "pass" to pass
            )
        )
        userList.add(user)
        return userList
    }

    suspend fun getArlByEmail(mail: String, password: String, remainingAttempts: Int = 3) {
        try {
            // Get SID
            getSid()

            val clientId = "447462"
            val clientSecret = "a83bf7f38ad2f137e444727cfc3775cf"
            val md5Password = md5(password)

            val params = mapOf(
                "app_id" to clientId,
                "login" to mail,
                "password" to md5Password,
                "hash" to md5(clientId + mail + md5Password + clientSecret)
            )

            // Get access token
            val responseJson = getToken(params, sid)
            val apiResponse = decodeJson(responseJson)
            session.updateCredentials(token = apiResponse.jsonObject["access_token"]!!.jsonPrimitive.content)

            // Get ARL
            val arlObject = callApi("user.getArl")
            session.updateCredentials(arl = arlObject["results"]!!.jsonPrimitive.content)
        } catch (e: Exception) {
            if (remainingAttempts > 1) {
                getArlByEmail(mail, password, remainingAttempts - 1)
            } else {
                throw e
            }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    private suspend fun getToken(params: Map<String, String>, sid: String): String {
        val url = "https://connect.deezer.com/oauth/user_auth.php"
        val httpUrl = url.toHttpUrlOrNull()!!.newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .headers(
                Headers.headersOf(
                    "Cookie", "sid=$sid",
                    "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36"
                )
            )
            .build()

        clientLog.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.string()
        }
    }

    suspend fun getSid() {
        val url = "https://www.deezer.com/ajax/gw-light.php?method=user.getArl&input=3&api_version=1.0&api_token=null"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = clientLog.newCall(request).await()
        response.headers.forEach {
            if (it.second.startsWith("sid=")) {
                session.updateCredentials(sid = it.second.substringAfter("sid=").substringBefore(";"))
            }
        }
    }

    //<============= Media =============>

    private val deezerMedia by lazy { DeezerMedia(this, clientNP) }

    suspend fun getMP3MediaUrl(track: Track, is128: Boolean): JsonObject = deezerMedia.getMP3MediaUrl(track, arl, sid, licenseToken, is128)

    suspend fun getMediaUrl(track: Track, quality: String): JsonObject = deezerMedia.getMediaUrl(track, quality)

    //<============= Search =============>

    private val deezerSearch by lazy { DeezerSearch(this) }

    suspend fun search(query: String): JsonObject = deezerSearch.search(query)

    suspend fun searchSuggestions(query: String): JsonObject = deezerSearch.searchSuggestions(query)

    suspend fun setSearchHistory(query: String) = deezerSearch.setSearchHistory(query)

    suspend fun getSearchHistory(): JsonObject = deezerSearch.getSearchHistory()

    suspend fun deleteSearchHistory() = deezerSearch.deleteSearchHistory(userId)

    //<============= Tracks =============>

    private val deezerTrack by lazy { DeezerTrack(this) }

    suspend fun track(id: String): JsonObject = deezerTrack.track(id)

    suspend fun getTracks(): JsonObject = deezerTrack.getTracks(userId)

    suspend fun addFavoriteTrack(id: String) = deezerTrack.addFavoriteTrack(id)

    suspend fun removeFavoriteTrack(id: String) = deezerTrack.removeFavoriteTrack(id)

    //<============= Artists =============>

    private val deezerArtist by lazy { DeezerArtist(this) }

    suspend fun artist(id: String): JsonObject = deezerArtist.artist(id)

    suspend fun getArtists(): JsonObject = deezerArtist.getArtists(userId)

    suspend fun followArtist(id: String) = deezerArtist.followArtist(id)

    suspend fun unfollowArtist(id: String) = deezerArtist.unfollowArtist(id)

    //<============= Albums =============>

    private val deezerAlbum by lazy { DeezerAlbum(this) }

    suspend fun album(album: Album): JsonObject = deezerAlbum.album(album)

    suspend fun getAlbums(): JsonObject = deezerAlbum.getAlbums(userId)

    suspend fun addFavoriteAlbum(id: String) = deezerAlbum.addFavoriteAlbum(id)

    suspend fun removeFavoriteAlbum(id: String) = deezerAlbum.removeFavoriteAlbum(id)

    //<============= Shows =============>

    private val deezerShow by lazy { DeezerShow(this) }

    suspend fun show(album: Album): JsonObject = deezerShow.show(album, language, userId)

    suspend fun getShows(): JsonObject = deezerShow.getShows(userId)

    suspend fun addFavoriteShow(id: String) = deezerShow.addFavoriteShow(id)

    suspend fun removeFavoriteShow(id: String) = deezerShow.removeFavoriteShow(id)

    suspend fun getBookmarkedEpisodes() = deezerShow.getBookmarkedEpisodes(userId)

    suspend fun bookmarkEpisode(id: String, offset: Long, duration: Double) = deezerShow.bookmarkEpisode(id, offset, duration)

    //<============= Playlists =============>

    private val deezerPlaylist by lazy { DeezerPlaylist(this) }

    suspend fun playlist(playlist: Playlist): JsonObject = deezerPlaylist.playlist(playlist)

    suspend fun getPlaylists(): JsonObject = deezerPlaylist.getPlaylists(userId)

    suspend fun addFavoritePlaylist(id: String) = deezerPlaylist.addFavoritePlaylist(id)

    suspend fun removeFavoritePlaylist(id: String) = deezerPlaylist.removeFavoritePlaylist(id)

    suspend fun addToPlaylist(playlist: Playlist, tracks: List<Track>) = deezerPlaylist.addToPlaylist(playlist, tracks)

    suspend fun removeFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = deezerPlaylist.removeFromPlaylist(playlist, tracks, indexes)

    suspend fun createPlaylist(title: String, description: String? = ""): JsonObject = deezerPlaylist.createPlaylist(title,description)

    suspend fun deletePlaylist(id: String) = deezerPlaylist.deletePlaylist(id)

    suspend fun updatePlaylist(id: String, title: String, description: String? = "") = deezerPlaylist.updatePlaylist(id, title, description)

    suspend fun updatePlaylistOrder(playlistId: String, ids: MutableList<String>) = deezerPlaylist.updatePlaylistOrder(playlistId, ids)

    //<============= Radios =============>

    private val deezerRadio by lazy { DeezerRadio(this) }

    suspend fun mix(id: String): JsonObject = deezerRadio.mix(id)

    suspend fun mixArtist(id: String): JsonObject = deezerRadio.mixArtist(id)

    suspend fun radio(trackId: String, artistId: String): JsonObject = deezerRadio.radio(trackId, artistId)

    suspend fun flow(id: String): JsonObject = deezerRadio.flow(id, userId)

    //<============= Pages =============>

    suspend fun page(page: String): JsonObject {
        return callApi(
            method = "page.get",
            gatewayInput = """
                {"PAGE":"$page","VERSION":"2.5","SUPPORT":{"ads":[],"deeplink-list":["deeplink"],"event-card":["live-event"],"grid-preview-one":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid-preview-two":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-list":["track","song"],"item-highlight":["radio"],"large-card":["album","external-link","playlist","show","video-link"],"list":["episode"],"mini-banner":["external-link"],"slideshow":["album","artist","channel","external-link","flow","livestream","playlist","show","smarttracklist","user","video-link"],"small-horizontal-grid":["flow"],"long-card-horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"filterable-grid":["flow"]},"LANG":"$langCode","OPTIONS":["deeplink_newsandentertainment","deeplink_subscribeoffer"]}
            """.trimIndent()
        )
    }

    //<============= Lyrics =============>

    suspend fun lyrics(id: String): JsonObject {
        val request = Request.Builder()
            .url("https://auth.deezer.com/login/arl?jo=p&rto=c&i=c")
            .post(RequestBody.EMPTY)
            .headers(Headers.headersOf("Cookie", "arl=$arl; sid=$sid"))
            .build()
        val response = clientNP.newCall(request).await()
        val jsonObject = decodeJson(response.body.string())

        val jwt = jsonObject["jwt"]?.jsonPrimitive?.content
        val params = encodeJson {
            put("operationName", "SynchronizedTrackLyrics")
            put("query", "query SynchronizedTrackLyrics(\$trackId: String!) {\n  track(trackId: \$trackId) {\n    id\n    isExplicit\n    lyrics {\n      id\n      copyright\n      text\n      writers\n      synchronizedLines {\n        lrcTimestamp\n        line\n        milliseconds\n        duration\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}")
            putJsonObject("variables") {
                put("trackId", id)
            }
        }
        val pipeRequest = Request.Builder()
            .url("https://pipe.deezer.com/api")
            .post(params.toRequestBody())
            .headers(Headers.headersOf("Authorization", "Bearer $jwt", "Content-Type", "application/json"))
            .build()
        val pipeResponse = clientNP.newCall(pipeRequest).await()
        return decodeJson(pipeResponse.body.string())
    }

    //<============= Util =============>

    private val deezerUtil by lazy { DeezerUtil(this) }

    suspend fun updateCountry() = deezerUtil.updateCountry(country)

    suspend fun log(track: Track) = deezerUtil.log(track, userId)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(stream: InputStream) = withContext(Dispatchers.Default) {
        json.decodeFromStream<JsonObject>(stream)
    }

    suspend fun decodeJson(raw: String): JsonObject = withContext(Dispatchers.IO) {
        json.decodeFromString<JsonObject>(raw)
    }

    suspend fun encodeJson(raw: JsonObjectBuilder.() -> Unit = {}): String = withContext(Dispatchers.IO) {
        json.encodeToString(buildJsonObject(raw))
    }
}