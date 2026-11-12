package com.ygosandbox.app.combos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.coroutineContext

/** A search result that looks like a combo video for the archetype. */
data class VideoHit(
    val url: String,
    val title: String,
    val uploader: String,
    /** yyyy-MM-dd, or "" when YouTube didn't say. Approximate ("2 weeks ago") for search results. */
    val date: String,
    val seconds: Long,
)

/** What the reader needs from one video: the downloaded file plus its text. */
class FetchedVideo(val file: File, val description: String, val captions: String, val date: String)

/**
 * YouTube search and download through NewPipe Extractor, the library behind the NewPipe app.
 * It runs on the phone's own connection, which YouTube doesn't block like cloud servers.
 * When YouTube changes its site, searches start failing until the library is updated.
 */
object VideoSource {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // Titles that are almost never a combo walkthrough.
    private val SKIP = listOf(
        "pack opening", "opening packs", "unboxing", "tier list", "banlist", "ban list", "reaction",
        "lore", "anime", "price", "market watch", "podcast", "livestream", "live stream", "#shorts",
    )

    /** What to look for: the search words, which titles rank first, and how long such videos run. */
    enum class Kind(val query: String, val good: List<String>, val skip: List<String>, val minSeconds: Long, val maxSeconds: Long) {
        COMBO("combo", listOf("combo", "combos", "line", "lines", "endboard", "end board", "tutorial", "guide", "how to play"),
            emptyList(), 60, 1800),
        // whole games: feature matches, tournament rounds, ranked replays
        REPLAY("replay", listOf("replay", "feature match", "vs", "round", "top 8", "top 16", "top 4", "finals", "duel", "ranked", "gameplay"),
            listOf("combo guide", "combos", "tutorial", "deck profile", "how to play"), 300, 5400),
    }

    @Volatile private var ready = false

    private fun init() {
        if (!ready) synchronized(this) {
            if (!ready) { NewPipe.init(UrlConnectionDownloader); ready = true }
        }
    }

    /**
     * Combo videos for [archetype], best titles first. Titles must name the archetype; videos
     * uploaded before [since] (yyyy-MM-dd) are left out, as are Shorts and very long streams.
     */
    suspend fun search(
        archetype: String,
        since: String?,
        limit: Int = 20,
        kind: Kind = Kind.COMBO,
    ): List<VideoHit> = withContext(Dispatchers.IO) {
        init()
        val yt = ServiceList.YouTube
        val query = yt.searchQHFactory.fromQuery("$archetype ${kind.query}", listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val extractor = yt.getSearchExtractor(query)
        extractor.fetchPage()
        val items = extractor.initialPage.items.toMutableList()
        val page = extractor.initialPage.nextPage
        // a second page, so the filters still leave enough to choose from
        if (page != null && items.size < 40) runCatching { items += extractor.getPage(page).items }

        val words = archetype.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val t = title.lowercase()
            if (SKIP.any { it in t } || words.any { it !in t }) return@mapNotNull null
            val seconds = item.duration
            if (seconds in 0 until kind.minSeconds || seconds > kind.maxSeconds) return@mapNotNull null
            val date = item.uploadDate?.offsetDateTime()?.toLocalDate()?.toString() ?: ""
            if (since != null && date.isNotEmpty() && date < since) return@mapNotNull null
            if (kind.skip.any { it in t }) return@mapNotNull null
            val score = 1 + kind.good.count { it in t }
            score to VideoHit(item.url, title, item.uploaderName ?: "", date, seconds)
        }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.url }.take(limit)
    }

    /**
     * Downloads the video-only stream (at most 720p, enough to read card names) into [dir],
     * plus the description and English captions when YouTube has them.
     */
    suspend fun fetch(url: String, dir: File, onProgress: (Float) -> Unit): FetchedVideo = withContext(Dispatchers.IO) {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val streams = (info.videoOnlyStreams + info.videoStreams)
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 240..720 }
        // H.264 in MP4 decodes everywhere; WebM (VP9) only when that's all there is
        val stream = streams.filter { it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height }
            ?: streams.maxByOrNull { it.height }
            ?: throw IllegalStateException("No downloadable video stream (YouTube may have changed; update the app)")

        dir.mkdirs()
        val file = File(dir, "video." + (stream.format?.getSuffix() ?: "mp4"))
        download(stream.content, file, onProgress)

        val captions = info.subtitles
            .filter { it.format == MediaFormat.VTT && it.languageTag.startsWith("en") }
            .sortedBy { it.isAutoGenerated } // made by the creator first
            .firstOrNull()
            ?.let { runCatching { Vtt.parse(httpText(it.content)) }.getOrDefault("") } ?: ""
        val date = info.uploadDate?.offsetDateTime()?.toLocalDate()?.toString() ?: ""
        FetchedVideo(file, info.description?.content ?: "", captions, date)
    }

    /** Downloads in 8 MB ranges: YouTube slows down single long requests a lot. */
    private suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val chunk = 8L * 1024 * 1024
        var total = -1L
        var done = 0L
        target.delete()
        RandomAccessFile(target, "rw").use { out ->
            while (total < 0 || done < total) {
                coroutineContext.ensureActive()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$done-${done + chunk - 1}")
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("Video download failed (HTTP $code)")
                    if (total < 0) {
                        total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: conn.contentLengthLong.takeIf { code == 200 } ?: -1L
                    }
                    val buf = ByteArray(64 * 1024)
                    conn.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                        }
                    }
                    if (total > 0) onProgress(done.toFloat() / total)
                    if (code == 200 || total < 0) break // the server ignored the range and sent everything
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    /** NewPipe's network layer on plain HttpURLConnection, so the app needs no HTTP library. */
    private object UrlConnectionDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = request.httpMethod()
            conn.setRequestProperty("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                values.forEachIndexed { i, v -> if (i == 0) conn.setRequestProperty(name, v) else conn.addRequestProperty(name, v) }
            }
            request.dataToSend()?.let { data ->
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
            }
            try {
                val code = conn.responseCode
                if (code == 429) throw ReCaptchaException("YouTube asked for a captcha; try again later", request.url())
                val body = (if (code in 200..399) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                val headers = conn.headerFields.filterKeys { it != null }
                return Response(code, conn.responseMessage, headers, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Today minus [days], for "since" filters. */
    fun daysAgo(days: Long): String = LocalDate.now(ZoneOffset.UTC).minusDays(days).toString()
}

/** WebVTT captions -> "[mm:ss] line" text, without the rolling repeats of auto-captions. */
object Vtt {
    private val CUE = Regex("""(?:(\d+):)?(\d\d):(\d\d)\.\d+\s+-->""")
    private val TAG = Regex("<[^>]+>")

    fun parse(text: String): String {
        val out = StringBuilder()
        var stamp = -1
        var last = ""
        for (raw in text.lineSequence()) {
            val m = CUE.find(raw)
            if (m != null) {
                val (h, mm, ss) = m.destructured
                stamp = (h.toIntOrNull() ?: 0) * 3600 + mm.toInt() * 60 + ss.toInt()
                continue
            }
            val line = raw.replace(TAG, "").trim()
            if (line.isEmpty() || stamp < 0 || line == last || line.startsWith("WEBVTT") ||
                line.startsWith("Kind:") || line.startsWith("Language:")) continue
            last = line
            out.append("[%02d:%02d] %s\n".format(stamp / 60, stamp % 60, line))
        }
        return out.toString()
    }
}
