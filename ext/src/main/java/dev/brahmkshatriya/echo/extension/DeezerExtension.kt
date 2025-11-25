package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultCountryIndex
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultLanguageIndex
import dev.brahmkshatriya.echo.extension.clients.DeezerAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerHomeFeedClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerRadioClient
import dev.brahmkshatriya.echo.extension.clients.DeezerSearchClient
import dev.brahmkshatriya.echo.extension.clients.DeezerTrackClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerExtension : HomeFeedClient, TrackClient, LikeClient, RadioClient,
    SearchFeedClient, QuickSearchClient,AlbumClient, ArtistClient, FollowClient, PlaylistClient, LyricsClient, ShareClient,
    TrackerClient, LoginClient.WebView, LoginClient.CustomInput,
    LibraryFeedClient, PlaylistEditClient, SaveClient {

    private val session by lazy { DeezerSession.getInstance() }
    private val api by lazy { DeezerApi(session) }
    private val parser by lazy { DeezerParser(session) }

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                "Use Proxy",
                "proxy",
                "Use proxy to prevent GEO-Blocking",
                mutableListOf("No Proxy", "UK 1", "UK 2", "RU 1", "RU 2", "MD"),
                mutableListOf("", "uk1.proxy.murglar.app", "uk2.proxy.murglar.app", "ru1.proxy.murglar.app", "ru2.proxy.murglar.app", "md.proxy.murglar.app"),
                0
            ),
            SettingSwitch(
                "Enable Logging",
                "log",
                "Enables logging to deezer",
                false
            ),
            SettingSwitch(
                "Enable Search History",
                "history",
                "Enables the search history",
                true
            ),
            SettingCategory(
                "Quality",
                "quality",
                mutableListOf(
                    SettingSlider(
                        "Image Quality",
                        "image_quality",
                        "Choose your preferred image quality (Can impact loading times)",
                        240,
                        120,
                        1920,
                        120
                    )
                )
            ),
            SettingCategory(
                "Language & Country",
                "langcount",
                mutableListOf(
                    SettingList(
                        "Language",
                        "lang",
                        "Choose your preferred language for loaded stuff",
                        DeezerCountries.languages.map { it.name },
                        DeezerCountries.languages.map { it.code },
                        getDefaultLanguageIndex(session.settings)
                    ),
                    SettingList(
                        "Country",
                        "country",
                        "Choose your preferred country for browse recommendations",
                        DeezerCountries.countries.map { it.name },
                        DeezerCountries.countries.map { it.code },
                        getDefaultCountryIndex(session.settings)
                    )
                )
            ),
            SettingCategory(
                "Appearance",
                "appearance",
                mutableListOf(
                    SettingList(
                        "Shelf Type",
                        "shelf",
                        "Choose your preferred shelf type",
                        mutableListOf("Grid", "Linear"),
                        mutableListOf("grid", "linear"),
                        0
                    )
                )
            )
        )
    }

    override fun setSettings(settings: Settings) {
        session.settings = settings
    }

    override suspend fun onExtensionSelected() {
        session.settings?.let { setSettings(it) }
    }

    //<============= HomeTab =============>

    private val deezerHomeFeedClient by lazy { DeezerHomeFeedClient(this, api, parser) }

    override suspend fun loadHomeFeed(): Feed<Shelf> = deezerHomeFeedClient.loadHomeFeed(shelf)

    //<============= Library =============>

    private val deezerLibraryClient by lazy { DeezerLibraryClient(this, api, parser) }

    override suspend fun loadLibraryFeed(): Feed<Shelf> = deezerLibraryClient.loadLibraryFeed()

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>
    ) {
        handleArlExpiration()
        api.addToPlaylist(playlist, new)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) {
        handleArlExpiration()
        api.removeFromPlaylist(playlist, tracks, indexes)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        handleArlExpiration()
        val jsonObject = api.createPlaylist(title, description)
        val id = jsonObject["results"]?.jsonPrimitive?.content.orEmpty()
        val playlist = Playlist(
            id = id,
            title = title,
            description = description,
            isEditable = true
        )
        return playlist
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        handleArlExpiration()
        api.deletePlaylist(playlist.id)
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?
    ) {
        handleArlExpiration()
        api.updatePlaylist(playlist.id, title, description)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        handleArlExpiration()
        when(item) {
            is Track -> {
                if (shouldLike) {
                    api.addFavoriteTrack(item.id)
                } else {
                    api.removeFavoriteTrack(item.id)
                }
            }
            else -> {}
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        when(item) {
            is Track -> {
                val dataArray = api.getTracks()["results"]?.jsonObject
                    ?.get("data")?.jsonArray ?: return false

                val trackIds = dataArray.mapNotNull { it.jsonObject["SNG_ID"]?.jsonPrimitive?.content }.toSet()
                return item.id in trackIds
            }

            else -> {
                return false
            }
        }
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        handleArlExpiration()
        val playlistList = mutableListOf<Pair<Playlist, Boolean>>()
        val jsonObject = api.getPlaylists()
        val resultObject = jsonObject["results"]!!.jsonObject
        val tabObject = resultObject["TAB"]!!.jsonObject
        val playlistObject = tabObject["playlists"]!!.jsonObject
        val dataArray = playlistObject["data"]!!.jsonArray
        dataArray.map {
            val playlist = parser.run { it.jsonObject.toPlaylist() }
            if (playlist.isEditable) {
                playlistList.add(Pair(playlist, false))
            }

        }
        return playlistList
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ) {
        handleArlExpiration()
        val idArray = tracks.map { it.id }.toMutableList()
        idArray.add(toIndex, idArray.removeAt(fromIndex))
        api.updatePlaylistOrder(playlist.id, idArray)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        return when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    getIsItemSaved(api::getShows, "SHOW_ID", item.id)
                } else {
                    getIsItemSaved(api::getAlbums, "ALB_ID", item.id)
                }
            }

            is Playlist -> {
                getIsItemSaved(api::getPlaylists, "PLAYLIST_ID", item.id)
            }

            is Track -> {
                getIsItemSaved(api::getTracks, "SNG_ID", item.id)
            }

            else -> false
        }
    }

    private suspend fun getIsItemSaved(
        getItems: suspend () -> JsonObject,
        idKey: String,
        itemId: String
    ): Boolean {
        val dataObject = getItems()["results"]?.jsonObject
        val dataArray = if (idKey == "SNG_ID") {
            dataObject?.get("data")?.jsonArray ?: return false

        } else {
            dataObject?.get("TAB")?.jsonObject
                ?.values?.firstOrNull()?.jsonObject
                ?.get("data")?.jsonArray ?: return false
        }
        return dataArray.any { item ->
            val id = item.jsonObject[idKey]?.jsonPrimitive?.content
            id == itemId
        }
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    if (shouldSave) api.addFavoriteShow(item.id) else api.removeFavoriteShow(
                        item.id
                    )
                } else {
                    if (shouldSave) api.addFavoriteAlbum(item.id) else api.removeFavoriteAlbum(
                        item.id
                    )
                }

            }

            is Playlist -> {
                if (shouldSave) api.addFavoritePlaylist(item.id) else api.removeFavoritePlaylist(item.id)
            }

            is Track -> {
                if (shouldSave) api.addFavoriteTrack(item.id) else api.removeFavoriteTrack(item.id)
            }

            else -> {}
        }
    }

    //<============= Search =============>

    private val deezerSearchClient by lazy { DeezerSearchClient(this, api, history, parser) }

    override suspend fun quickSearch(query: String): List<QuickSearchItem.Query> = deezerSearchClient.quickSearch(query)

    override suspend fun deleteQuickSearch(item: QuickSearchItem) = api.deleteSearchHistory()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = deezerSearchClient.loadSearchFeed(query, shelf)

    suspend fun channelFeed(target: String): List<Shelf> {
        val jsonObject = api.page(target.substringAfter("/"))
        val channelPageResults = jsonObject["results"]!!.jsonObject
        val channelSections = channelPageResults["sections"]!!.jsonArray
        return supervisorScope {
            channelSections.map { section ->
                async(Dispatchers.Default) {
                    parser.run {
                        section.jsonObject["title"]?.jsonPrimitive?.content
                            ?.let { section.toShelfItemsList(it) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    //<============= Play =============>

    private val deezerTrackClient by lazy { DeezerTrackClient(this, api, parser) }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = deezerTrackClient.loadStreamableMedia(streamable)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = deezerTrackClient.loadTrack(track)

    override suspend fun loadFeed(track: Track): Feed<Shelf> = loadFeed(track.artists.first())

    //<============= Radio =============>

    private val deezerRadioClient by lazy { DeezerRadioClient(api, parser) }

    override suspend fun loadTracks(radio: Radio): Feed<Track> = deezerRadioClient.loadTracks(radio)

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = deezerRadioClient.radio(item, context)

    override suspend fun loadRadio(radio: Radio): Radio  = radio

    //<============= Lyrics =============>

    private val deezerLyricsClient by lazy { DeezerLyricsClient(api) }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> = deezerLyricsClient.searchTrackLyrics(track)

    //<============= Album =============>

    private val deezerAlbumClient by lazy { DeezerAlbumClient(this, api, parser) }

    override suspend fun loadFeed(album: Album): Feed<Shelf> = loadFeed(album.artists.first())

    override suspend fun loadAlbum(album: Album): Album = deezerAlbumClient.loadAlbum(album)

    override suspend fun loadTracks(album: Album): Feed<Track> = deezerAlbumClient.loadTracks(album)

    //<============= Playlist =============>

    private val deezerPlaylistClient by lazy { DeezerPlaylistClient(this, api, parser) }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf> = deezerPlaylistClient.getShelves(playlist)

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = deezerPlaylistClient.loadPlaylist(playlist)

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = deezerPlaylistClient.loadTracks(playlist)

    //<============= Artist =============>

    private val deezerArtistClient by lazy { DeezerArtistClient(this, api, parser) }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = deezerArtistClient.getShelves(artist)

    override suspend fun loadArtist(artist: Artist): Artist = deezerArtistClient.loadArtist(artist)

    override suspend fun isFollowing(item: EchoMediaItem): Boolean = deezerArtistClient.isFollowing(item)

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = deezerArtistClient.getFollowersCount(item)

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        if (shouldFollow) api.followArtist(item.id) else api.unfollowArtist(item.id)
    }

    //<============= Login =============>

    override suspend fun getCurrentUser(): User {
        val userList = api.makeUser()
        return userList.first()
    }

    override val webViewRequest = object : WebViewRequest.Headers<List<User>> {
        override suspend fun onStop(requests: List<NetworkRequest>): List<User> {
            val request = requests.first()
            val data = request.headers
            val arl = extractCookieValue(data, "arl")
            val sid = extractCookieValue(data, "sid")
            if (arl != null && sid != null) {
                session.updateCredentials(arl = arl, sid = sid)
                val credJObj = api.decodeJson(request.body?.decodeToString()!!)
                val mail = credJObj["MAIL"]?.jsonPrimitive?.content!!
                val pass = credJObj["PASSWORD"]?.jsonPrimitive?.content!!
                session.updateCredentials(
                    email = mail,
                    pass = pass
                )
                return api.makeUser(mail, pass)
            } else if (data.isEmpty()) {
                throw Exception("Ignore this")
            } else {
                throw Exception("Failed to retrieve ARL and SID from cookies")
            }
        }

        override val initialUrl = "https://www.deezer.com/login?redirect_type=page&redirect_link=%2Faccount%2F".toGetRequest(
            mapOf(
                Pair(
                    "user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                )
            )
        )

        override val interceptUrlRegex = "https://www\\.deezer\\.com/ajax/gw-light\\.php\\?method=deezer_userAuth.*".toRegex()

        override val stopUrlRegex = "https://www\\.deezer\\.com/account/.*".toRegex()

        private fun extractCookieValue(data: Map<String,String>, key: String): String? {
            return data["cookie"]?.substringAfter("$key=")?.substringBefore(";").takeIf { it?.isNotEmpty() == true }
        }
    }

    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "userPass",
            label = "E-Mail and Password",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = "E-Mail",
                    isRequired = true,
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "pass",
                    label = "Password",
                    isRequired = true
                )
            )
        ),
        LoginClient.Form(
            key = "manual",
            label = "ARL",
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Misc,
                    key = "arl",
                    label = "ARL",
                    isRequired = false,
                )

            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        if(data["email"] != null && data["pass"] != null) {
            val email = data["email"]!!
            val password = data["pass"]!!

            session.updateCredentials(email = email, pass = password)

            api.getArlByEmail(email, password, 3)
            val userList = api.makeUser(email, password)
            return userList
        } else {
            session.updateCredentials(arl = data["arl"] ?: "")
            api.getSid()
            val userList = api.makeUser()
            return userList
        }
    }

    override fun setLoginUser(user: User?) {
        if (user != null) {
            session.updateCredentials(
                arl = user.extras["arl"] ?: "",
                sid = user.extras["sid"] ?: "",
                token = user.extras["token"] ?: "",
                userId = user.extras["user_id"] ?: "",
                licenseToken = user.extras["license_token"] ?: "",
                email = user.extras["email"] ?: "",
                pass = user.extras["pass"] ?: ""
            )
        } else {
            session.updateCredentials(
                arl = "",
                sid = "",
                token = "",
                userId = "",
                licenseToken = "",
                email = "",
                pass = ""
            )
        }
    }

    //<============= Share =============>

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Track -> "https://www.deezer.com/track/${item.id}"
            is Artist -> "https://www.deezer.com/artist/${item.id}"
            //is EchoMediaItem.Profile.UserItem -> "https://www.deezer.com/profile/${item.id}"
            is Album -> "https://www.deezer.com/album/${item.id}"
            is Playlist -> "https://www.deezer.com/playlist/${item.id}"
            is Radio -> TODO("Does not exist")
        }
    }

    //<============= Tracking =============>

    override suspend fun onTrackChanged(details: TrackDetails?) {
        if (details != null) {
            if (log) {
                api.log(details.track)
            }
        }
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        val track = details?.track
        if (track?.type == Track.Type.Podcast && !isPlaying) {
            api.bookmarkEpisode(
                track.id,
                details.currentPosition.div(1000),
                details.totalDuration?.div(1000)?.toDouble() ?: 0.0
            )
        }
    }

    //<============= Utils =============>

    suspend fun handleArlExpiration() {
        if (session.credentials.arl.isEmpty() || session.arlExpired) {
            if (session.credentials.email.isEmpty() || session.credentials.pass.isEmpty()) {
                throw ClientException.LoginRequired()
            } else {
                api.makeUser()
            }
        }
    }

    private val shelf: String get() = session.settings?.getString("shelf") ?: DEFAULT_TYPE
    private val log: Boolean get() = session.settings?.getBoolean("log") == true
    private val history: Boolean get() = session.settings?.getBoolean("history") != false

    companion object {
        private const val DEFAULT_TYPE = "grid"
    }
}