package io.github.proify.lyricon.miuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.AndroidUtils
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lrckit.LrcParser
import io.github.proify.qrckit.LyricResponse
import io.github.proify.qrckit.QrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米音乐（澎湃 OS 自带，com.miui.player）歌词源。
 *
 * 取词方式：小米音乐只暴露 MediaSession 的 mediaId（QQ 音乐风格 songmid），
 * 本 provider 用 songmid 走 QQ 音乐歌词接口，失败再用「歌名 + 歌手」搜索 musicid 兜底，
 * 属于聚合式取词（不依赖小米音乐自身是否缓存歌词）。
 */
class MiuiMusic(val tag: String = "MiuiMusicProvider") : YukiBaseHooker() {
    private var provider: LyriconProvider? = null
    private var currentMediaId: String? = null
    private var currentTitle: String? = null
    private var lastSetSong: Song? = null // 最近推送的当前歌（SystemUI 重启后重推）
    private var currentArtist: String? = null
    private var currentDuration: Long = 0
    private val downloadingIds = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onHook() {
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)
        YLog.debug(tag = tag, msg = "进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }
        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(true)
            register()
            MiuiBootReconnect.attach(context, this)
            // SystemUI 重启后重推当前歌
            MiuiBootReconnect.registerSongRepusher {
                lastSetSong?.let { s -> provider?.player?.setSong(s) }
            }
        }
        YLog.debug(tag = tag, msg = "Provider初始化完成")
    }

    private fun hookMediaSession() {
        // Hook setMetadata，获取mediaId并下载歌词
        XposedHelpers.findAndHookMethod(
            "android.media.session.MediaSession",
            appClassLoader,
            "setMetadata",
            MediaMetadata::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: return

                    if (mediaId.isBlank() || mediaId == currentMediaId) return

                    currentMediaId = mediaId
                    val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    // 小米音乐的TITLE是当前歌词，ARTIST是「歌名-歌手」格式
                    // 需要从ARTIST里解析出真正的歌名和歌手
                    var realTitle = rawTitle
                    var realArtist = rawArtist
                    if (!rawArtist.isNullOrBlank() && rawArtist.contains("-")) {
                        val parts = rawArtist.split("-", limit = 2)
                        if (parts.size == 2) {
                            realTitle = parts[0].trim()
                            realArtist = parts[1].trim()
                        }
                    }

                    currentTitle = realTitle
                    currentArtist = realArtist
                    currentDuration = duration
                    YLog.debug(tag = tag, msg = "切歌: 原始title=$rawTitle, 原始artist=$rawArtist, 解析后歌名=$realTitle, 歌手=$realArtist, mediaId=$mediaId")

                    // 先发占位符
                    provider?.player?.setSong(
                        Song(
                            id = mediaId,
                            name = realTitle,
                            artist = realArtist,
                            metadata = lyricMetadataOf("placeholder" to "true")
                        )
                    )

                    // 下载歌词
                    downloadLyrics(mediaId)
                }
            }
        )

        // Hook setPlaybackState
        XposedHelpers.findAndHookMethod(
            "android.media.session.MediaSession",
            appClassLoader,
            "setPlaybackState",
            PlaybackState::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = param.args[0] as? PlaybackState ?: return
                    MiuiBootReconnect.markPlaybackActive(
                        state.state == PlaybackState.STATE_PLAYING ||
                            state.state == PlaybackState.STATE_BUFFERING
                    )
                    provider?.player?.setPlaybackState(state)
                }
            }
        )
    }

    private fun downloadLyrics(id: String) {
        if (downloadingIds.contains(id)) return
        downloadingIds.add(id)

        scope.launch {
            try {
                // 小米音乐给的是songmid（字母数字混合），需要用songmid API下载歌词
                val result = downloadLyricsBySongmid(id)
                if (result == null) {
                    YLog.debug(tag = tag, msg = "songmid下载失败，尝试搜索获取musicid: $id")
                    // 用歌名+歌手搜索获取数字musicid
                    val musicId = searchMusicId(currentTitle, currentArtist)
                    if (musicId != null) {
                        YLog.debug(tag = tag, msg = "搜索到musicid: $musicId，开始下载歌词")
                        try {
                            val response = QrcDownloader.downloadLyrics(musicId)
                            val song = response.toLyriconSong()
                            YLog.debug(tag = tag, msg = "musicid下载完成: ${song.name}, ${song.lyrics?.size ?: 0}行")
                            // 直接发送，不需要判断ID（musicid和songmid格式不同）
                            lastSetSong = song
                            provider?.player?.setSong(song)
                        } catch (e: Exception) {
                            YLog.error(tag = tag, msg = "musicid下载异常: $musicId", e = e)
                        }
                    } else {
                        YLog.debug(tag = tag, msg = "搜索不到musicid")
                    }
                } else {
                    val (raw, transRaw, romaRaw) = result
                    // 用LrcParser分别解析原文、翻译、罗马音
                    val doc = LrcParser.parse(raw, currentDuration)
                    val transDoc = transRaw?.takeIf { it.isNotBlank() }?.let { LrcParser.parse(it, currentDuration) }
                    val romaDoc = romaRaw?.takeIf { it.isNotBlank() }?.let { LrcParser.parse(it, currentDuration) }

                    val transLines = transDoc?.lines ?: emptyList()
                    val romaLines = romaDoc?.lines ?: emptyList()

                    val lyrics = doc.lines.map { line ->
                        RichLyricLine(
                            begin = line.begin,
                            end = line.end,
                            duration = line.duration,
                            text = line.text,
                            translation = transLines.firstOrNull { Math.abs(it.begin - line.begin) < 500 }?.text,
                            roma = romaLines.firstOrNull { Math.abs(it.begin - line.begin) < 500 }?.text,
                            words = line.words
                        )
                    }
                    val song = Song(
                        id = id,
                        name = currentTitle,
                        artist = currentArtist,
                        duration = currentDuration,
                        lyrics = lyrics
                    )
                    if (id == currentMediaId) {
                        lastSetSong = song
                        provider?.player?.setSong(song)
                    }
                    YLog.debug(tag = tag, msg = "歌词下载成功(songmid): ${song.name}, ${lyrics.size}行, 翻译:${transLines.size}行, 罗马音:${romaLines.size}行")
                }
            } catch (e: Exception) {
                YLog.error(tag = tag, msg = "歌词下载失败: $id", e = e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }

    /** 用歌名+歌手搜索获取数字musicid */
    private fun searchMusicId(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        return try {
            val keyword = if (!artist.isNullOrBlank()) "$title $artist" else title
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = URI.create("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encodedKeyword&format=json&p=1&n=1").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Referer", "https://y.qq.com/")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                // 提取songid（数字）
                val songidMatch = Regex("\"songid\"\\s*:\\s*(\\d+)").find(json)
                if (songidMatch != null) {
                    val musicId = songidMatch.groupValues[1]
                    YLog.debug(tag = tag, msg = "搜索到musicid: $musicId, 关键词: $keyword")
                    musicId
                } else {
                    YLog.debug(tag = tag, msg = "搜索结果无songid: ${json.take(200)}")
                    null
                }
            } else {
                YLog.debug(tag = tag, msg = "搜索API HTTP失败: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "搜索musicid异常", e = e)
            null
        }
    }

    /**
     * 用QQ音乐songmid下载歌词
     * API: https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg
     * @return Triple(原文, 翻译, 罗马音)
     */
    private fun downloadLyricsBySongmid(songmid: String): Triple<String, String?, String?>? {
        return try {
            val params = mapOf(
                "songmid" to songmid,
                "g_tk" to "5381",
                "loginUin" to "0",
                "hostUin" to "0",
                "format" to "json",
                "inCharset" to "utf8",
                "outCharset" to "utf-8",
                "notice" to "0",
                "platform" to "yqq",
                "needNewCode" to "0"
            )
            val query = params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = URI.create("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?$query").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Referer", "https://y.qq.com/")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                // 解析JSON，提取lyric字段（base64编码）
                val lyricMatch = Regex("\"lyric\"\\s*:\\s*\"([^\"]+)\"").find(json)
                val transMatch = Regex("\"trans\"\\s*:\\s*\"([^\"]+)\"").find(json)
                val romaMatch = Regex("\"roma\"\\s*:\\s*\"([^\"]+)\"").find(json)

                if (lyricMatch != null) {
                    val base64Lyric = lyricMatch.groupValues[1]
                    val decoded = String(Base64.getDecoder().decode(base64Lyric), StandardCharsets.UTF_8)
                    val transDecoded = transMatch?.groupValues?.get(1)?.let {
                        runCatching { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }.getOrNull()
                    }
                    val romaDecoded = romaMatch?.groupValues?.get(1)?.let {
                        runCatching { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }.getOrNull()
                    }
                    YLog.debug(tag = tag, msg = "songmid下载到歌词: ${decoded.take(100)}, 翻译: ${transDecoded != null}, 罗马音: ${romaDecoded != null}")
                    // 返回三元组：原文、翻译、罗马音
                    Triple(decoded, transDecoded, romaDecoded)
                } else {
                    YLog.debug(tag = tag, msg = "songmid API返回无lyric字段: ${json.take(200)}")
                    null
                }
            } else {
                YLog.debug(tag = tag, msg = "songmid API HTTP失败: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "songmid下载异常", e = e)
            null
        }
    }

    private fun LyricResponse.toLyriconSong(): Song {
        val lyrics = parsedLyric.richLyricLines?.let { removeInvalidTranslation(it) }
        return Song(
            id = id,
            name = currentTitle,
            artist = currentArtist,
            duration = currentDuration,
            lyrics = lyrics
        )
    }

    private fun removeInvalidTranslation(lines: List<RichLyricLine>): List<RichLyricLine> {
        return lines.map {
            if (it.translation?.trim() == "//") it.copy(translation = null) else it
        }
    }
}
