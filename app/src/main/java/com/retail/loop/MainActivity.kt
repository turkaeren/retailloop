package com.retail.loop

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import android.view.ViewGroup
import android.text.Html
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class Node(val id: String, val name: String, val folder: Boolean, val uri: Uri?, val pdf: Boolean = false, val image: Boolean = false)

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    private val nodes = mutableStateListOf<Node>()
    private val stack = mutableStateListOf(Pair(Config.FOLDER_ID, Config.ROOT_TITLE))
    private val playlist = mutableStateListOf<Node>()
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var playerOpen by mutableStateOf(false)
    private var current by mutableIntStateOf(0)
    private var loopMode by mutableIntStateOf(1)
    private var overlay by mutableStateOf(false)
    private var playPosition by mutableLongStateOf(0L)
    private var playDuration by mutableLongStateOf(0L)
    private var fullscreen by mutableStateOf(false)
    private var playbackSpeed by mutableFloatStateOf(1f)
    private var playerError by mutableStateOf("")
    private var playerUiActivity by mutableIntStateOf(0)
    private var playerStatus by mutableStateOf("")
    private var downloadStatus by mutableStateOf("İndirme beklemede")
    private val offlineReady = mutableStateMapOf<String, Boolean>()
    private lateinit var simpleCache: SimpleCache
    private lateinit var cacheFactory: CacheDataSource.Factory
    @Volatile private var playbackActive = false
    @Volatile private var prefetchCancelled = false
    private var prefetchThread: Thread? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var retryPosition = 0L
    private var viewerNode by mutableStateOf<Node?>(null)
    private var viewerBitmap by mutableStateOf<Bitmap?>(null)
    private var viewerPage by mutableIntStateOf(0)
    private var viewerPageCount by mutableIntStateOf(1)
    private var viewerError by mutableStateOf("")
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var viewerLoadToken = 0
    private var kiosk by mutableStateOf(false)
    private var diagText by mutableStateOf("")
    private var lastErr by mutableStateOf("")
    @Volatile private var bypassCache = false
    private lateinit var playFactory: androidx.media3.datasource.DataSource.Factory
    @Volatile private var driveBlockedUntil = 0L
    private var pendingPlayId: String? = null
    private val warmHandler = Handler(Looper.getMainLooper())
    private val warmExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var warmWriter: CacheWriter? = null
    @Volatile private var warmGen = 0
    private var warmTask: Runnable? = null

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { window.decorView.postDelayed({ if (stack.last().first == "usb") load() }, 2500) }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (stack.last().first == "usb") load() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loopMode = prefs.getInt("loop", 1)
        kiosk = prefs.getBoolean("kiosk", false)
        // İlk sürümdeki sade ayar: tarayıcı kimliği/Referer yok, Drive'ı şüphelendirmesin.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Range" to "bytes=0-", "Referer" to "https://drive.google.com/", "Accept" to "*/*"))
        val upstream = DefaultDataSource.Factory(this, httpFactory)
        val freeBytes = filesDir.usableSpace.coerceAtLeast(256L * 1024 * 1024)
        val cacheLimit = minOf(24L * 1024 * 1024 * 1024, (freeBytes * 0.60).toLong()).coerceAtLeast(256L * 1024 * 1024)
        simpleCache = SimpleCache(File(filesDir, "retail_media_cache"), LeastRecentlyUsedCacheEvictor(cacheLimit), StandaloneDatabaseProvider(this))
        cacheFactory = CacheDataSource.Factory().setCache(simpleCache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec -> spec.uri.getQueryParameter("id") ?: spec.key ?: spec.uri.toString() }
        if (!prefs.getBoolean("wipe55", false)) {
            runCatching { simpleCache.keys.toList().forEach { simpleCache.removeResource(it) } }
            prefs.edit().putBoolean("wipe55", true).apply()
        }
        val memMb = (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        val bufMb = (memMb / 3).coerceIn(32, 96)
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 1000, 2500)
            .setTargetBufferBytes(bufMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true).forceEnableMediaCodecAsynchronousQueueing()
        playFactory = androidx.media3.datasource.DataSource.Factory { if (bypassCache) upstream.createDataSource() else cacheFactory.createDataSource() }
        val sourceFactory = DefaultMediaSourceFactory(playFactory)
        player = ExoPlayer.Builder(this, rf).setLoadControl(lc).setMediaSourceFactory(sourceFactory)
            .build().apply { playWhenReady = true; setSeekParameters(SeekParameters.CLOSEST_SYNC) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                current = player.currentMediaItemIndex
                playerError = ""
                playPosition = 0L
                playDuration = 0L
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) { playerError = ""; playerStatus = ""; retryCount = 0; diagText = ""; lastErr = ""; bypassCache = false }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) playerStatus = "Arabelleğe alınıyor…"
                if (state == Player.STATE_READY) { playerStatus = ""; retryCount = 0 }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    playlist.getOrNull(player.currentMediaItemIndex)?.uri?.let { u -> runCatching { simpleCache.removeResource(cacheKey(u)) } }
                }
                retryPosition = player.currentPosition.coerceAtLeast(0L)
                val ec = error.errorCode
                if (ec == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || ec == androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE || ec == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    bypassCache = true
                    playlist.getOrNull(player.currentMediaItemIndex)?.uri?.let { u -> runCatching { simpleCache.removeResource(cacheKey(u)) } }
                    stopPrefetch()
                    if (diagText.isBlank()) playlist.getOrNull(player.currentMediaItemIndex)?.let { diagnose(it) }
                }
                                if (retryCount < 6 && playerOpen) {
                    retryCount++
                    val waitMs = (1000L shl (retryCount - 1)).coerceAtMost(32000L)
                    playerStatus = "Bağlantı yenileniyor… (${retryCount}/6)"
                    lastErr = error.errorCodeName + " · " + (error.cause?.javaClass?.simpleName ?: "") + " " + (error.cause?.message ?: error.message ?: "").take(120)
                    playerError = ""
                    overlay = true
                    retryHandler.removeCallbacksAndMessages(null)
                    retryHandler.postDelayed({
                        if (playerOpen && player.mediaItemCount > 0) {
                            runCatching { player.seekTo(retryPosition); player.prepare(); player.play() }
                        }
                    }, waitMs)
                } else {
                    val reason = error.cause?.message ?: error.message ?: "Bilinmeyen oynatma hatası"
                    playerError = if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
                        "Dosya indirilemedi: Drive şu an bu videoya izin vermiyor olabilir (indirme sınırı) ya da dosya video değil. Birkaç dakika sonra tekrar dene."
                    else "Video açılamadı: ${error.errorCodeName}. $reason"
                    playerStatus = ""
                    playing = false
                    overlay = true
                }
            }
        })
        applyLoop()
        Thread {
            runCatching {
                simpleCache.keys.toList().forEach { k ->
                    val l = simpleCache.getContentMetadata(k).get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                    if (l in 0L..1_000_000L) simpleCache.removeResource(k)
                }
            }
        }.apply { isDaemon = true; start() }
        val mountFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mountReceiver, mountFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mountReceiver, mountFilter)
        }
        setContent { Ui() }
        perm.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        if (kiosk && prefs.getString("last_id", null) != null) resumeLast() else load()
        autoDownload = prefs.getBoolean("autodl", true)
        java.net.CookieHandler.setDefault(java.net.CookieManager())
        startSync()
    }

    // ---- TV deposuna indirme (kalıcı, çevrimdışı oynatma) ----
    private val dlProgress = mutableStateMapOf<String, Int>()
    private var autoDownload by mutableStateOf(true)
    @Volatile private var dlRunning = false

    private fun streamingNow(): Boolean {
        if (!playbackActive) return false
        val n = playlist.getOrNull(runCatching { player.currentMediaItemIndex }.getOrDefault(0)) ?: return false
        return !isOfflineReady(n)
    }

    private fun nap(ms: Long): Boolean {
        var left = ms
        while (left > 0) { if (!autoDownload) return false; Thread.sleep(3000); left -= 3000 }
        return autoDownload
    }

    private fun collectVideos(id: String, depth: Int, out: MutableList<Node>) {
        for (n in parse(fetchPublicFolder(id))) {
            if (n.folder) { if (depth < 4) collectVideos(n.id, depth + 1, out) }
            else if (!n.pdf && !n.image && n.uri != null) out += n
        }
    }

    private fun startSync() {
        if (dlRunning || !autoDownload) return
        dlRunning = true
        Thread {
            try {
                while (autoDownload) {
                    runOnUiThread { downloadStatus = "Klasör taranıyor…" }
                    val list = mutableListOf<Node>()
                    try { collectVideos(Config.FOLDER_ID, 0, list) }
                    catch (_: Exception) { runOnUiThread { downloadStatus = "Klasör listelenemedi, tekrar denenecek" }; if (!nap(5 * 60_000L)) break; continue }
                    val todo = list.filter { !isOfflineReady(it) }
                    if (todo.isEmpty()) {
                        runOnUiThread { downloadStatus = "Tüm videolar TV'de (${list.size}) ✓" }
                        if (!nap(20 * 60_000L)) break
                        continue
                    }
                    var wait = 60_000L
                    for ((i, n) in todo.withIndex()) {
                        if (!autoDownload) break
                        while (autoDownload && streamingNow()) Thread.sleep(3000)
                        when (downloadOne(n, i + 1, todo.size)) {
                            1 -> { runOnUiThread { downloadStatus = "Drive kota doldu, 30 dk sonra tekrar denenecek" }; wait = 30 * 60_000L; break }
                            3 -> { runOnUiThread { downloadStatus = "TV'de yer yok, indirme durdu" }; wait = 60 * 60_000L; break }
                            2 -> wait = 5 * 60_000L
                        }
                    }
                    if (!nap(wait)) break
                }
            } finally { dlRunning = false }
        }.apply { name = "RetailLoop-Sync"; isDaemon = true; start() }
    }

    // 0 = tamam, 1 = Drive kotası, 2 = başka hata, 3 = yer yok
    private fun downloadOne(n: Node, idx: Int, total: Int): Int {
        val dst = localFile(n.id)
        val part = File(videoDir, n.id + ".part")
        var url = n.uri.toString()
        val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36"
        try {
            for (attempt in 0..2) {
                val have = if (part.exists()) part.length() else 0L
                val c = URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Referer", "https://drive.google.com/")
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                val ct = c.contentType ?: ""
                if (code == 416 && have > 1_000_000L) { c.disconnect(); part.renameTo(dst); return 0 }
                if (ct.startsWith("text/html")) {
                    val html = c.inputStream.use { it.readNBytes(60000) }.toString(Charsets.UTF_8)
                    c.disconnect()
                    if (html.contains("Quota exceeded", true) || html.contains("Too many users", true)) return 1
                    val form = Regex("(?is)<form[^>]*id=\"download-form\"[^>]*action=\"([^\"]+)\"[^>]*>(.*?)</form>").find(html) ?: return 2
                    val action = Html.fromHtml(form.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
                    val q = Regex("(?i)<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").findAll(form.groupValues[2])
                        .joinToString("&") { URLEncoder.encode(it.groupValues[1], "UTF-8") + "=" + URLEncoder.encode(Html.fromHtml(it.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString(), "UTF-8") }
                    url = action + (if (action.contains("?")) "&" else "?") + q
                    continue
                }
                if (code !in 200..299) { c.disconnect(); return 2 }
                val append = code == 206 && have > 0
                val size = if (code == 206) (c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L) else c.contentLengthLong
                if (!append) part.delete()
                val already = if (append) have else 0L
                if (size > 0 && filesDir.usableSpace < (size - already) + 1536L * 1024 * 1024) { c.disconnect(); return 3 }
                var done = already
                var lastUi = 0L
                c.inputStream.use { ins ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (!autoDownload) { return 2 }
                            val r = ins.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 1000) {
                                lastUi = now
                                val pct = if (size > 0) (done * 100 / size).toInt() else 0
                                runOnUiThread { dlProgress[n.id] = pct; downloadStatus = "İndiriliyor $idx/$total · %$pct · ${n.name}" }
                            }
                        }
                    }
                }
                c.disconnect()
                if (size > 0 && done < size) return 2
                if (done < 1_000_000L) { part.delete(); return 2 }
                dst.delete(); part.renameTo(dst)
                runOnUiThread { dlProgress.remove(n.id); offlineReady[n.id] = true }
                return 0
            }
        } catch (_: Exception) { return 2 }
        return 2
    }

    // ---- Otomatik güncelleme (GitHub Releases) ----
    @Volatile private var updateApk: File? = null
    private var updateInfo by mutableStateOf<Pair<String, String>?>(null)
    @Volatile private var updateTitle = ""
    @Volatile private var updateNotes = ""
    @Volatile private var updateChecking = false
    private var lastUpdateCheck = 0L
    private var lastInstallPrompt = 0L

    private fun currentVersionCode(): Long = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    }.getOrDefault(0L)

    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (updateChecking || now - lastUpdateCheck < 30 * 60_000L) return
        updateApk?.let { if (it.exists()) { maybeInstall(); return } }
        updateChecking = true
        lastUpdateCheck = now
        Thread {
            try {
                val c = URL("https://api.github.com/repos/turkaeren/retailloop/releases/latest").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val remote = j.getString("tag_name").substringAfterLast('.').toLongOrNull() ?: return@Thread
                if (remote <= currentVersionCode()) return@Thread
                updateTitle = j.getString("tag_name")
                updateNotes = j.optString("body", "").trim().ifBlank { "Hata düzeltmeleri ve iyileştirmeler." }
                val assets = j.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
                }
                url ?: return@Thread
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val part = File(dir, "update.part")
                val out = File(dir, "update.apk")
                val d = URL(url).openConnection() as java.net.HttpURLConnection
                d.connectTimeout = 20000; d.readTimeout = 30000
                d.inputStream.use { ins -> part.outputStream().use { ins.copyTo(it) } }
                if (part.length() < 1_000_000L) { part.delete(); return@Thread }
                out.delete(); part.renameTo(out)
                updateApk = out
                runOnUiThread { maybeInstall() }
            } catch (_: Exception) {
            } finally { updateChecking = false }
        }.apply { name = "RetailLoop-Update"; isDaemon = true; start() }
    }

    // Güncelleme hazırsa bildirim penceresini göster (oynatma sırasında değil).
    private fun maybeInstall() {
        if (updateApk?.exists() != true || updateInfo != null) return
        if (playbackActive || System.currentTimeMillis() - lastInstallPrompt < 2 * 3600_000L) return
        updateInfo = Pair(updateTitle, updateNotes)
    }

    // "Yükle" düğmesine basılınca kurulum başlar.
    private fun installNow() {
        val f = updateApk?.takeIf { it.exists() } ?: return
        lastInstallPrompt = System.currentTimeMillis()
        updateInfo = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                lastInstallPrompt = 0L
                return
            }
            val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onResume() { super.onResume(); checkUpdate() }

    override fun onDestroy() {
        prefetchCancelled = true
        stopPrefetch()
        retryHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(mountReceiver) }
        closeDocument()
        player.release()
        if (::simpleCache.isInitialized) simpleCache.release()
        super.onDestroy()
    }

    private fun cacheKey(uri: Uri): String = uri.getQueryParameter("id") ?: uri.toString()
    private val videoDir by lazy { File(filesDir, "videos").apply { mkdirs() } }
    private fun localFile(id: String) = File(videoDir, "$id.vid")
    private fun isOfflineReady(n: Node): Boolean {
        if (n.uri?.scheme != "https") return false
        val f = localFile(n.id)
        return f.exists() && f.length() > 1_000_000L
    }
    private fun refreshOffline() {
        val list = nodes.filter { !it.folder && !it.pdf && !it.image && it.uri?.scheme == "https" }
        if (list.isEmpty()) return
        Thread {
            val r = list.map { it.id to isOfflineReady(it) }
            runOnUiThread { r.forEach { (k, v) -> offlineReady[k] = v } }
        }.apply { isDaemon = true; start() }
    }

    private fun resumeLast() {
        val vid = prefs.getString("last_id", null) ?: return
        val folder = prefs.getString("last_folder", Config.FOLDER_ID) ?: Config.FOLDER_ID
        val fname = prefs.getString("last_folder_name", Config.ROOT_TITLE) ?: Config.ROOT_TITLE
        stack.clear()
        stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE))
        if (folder != Config.FOLDER_ID) stack.add(Pair(folder, fname))
        pendingPlayId = vid
        load()
    }

    private fun tryPending() {
        val pid = pendingPlayId ?: return
        nodes.firstOrNull { it.id == pid }?.let { pendingPlayId = null; open(it) }
    }

    @Volatile private var prefetchGen = 0
    @Volatile private var prefetchWriter: CacheWriter? = null

    // Oynatma başlayınca arka plan indirmesini hemen kes: tüm bant genişliği oynayan videoya kalsın.
    private fun stopPrefetch() {
        prefetchGen++
        warmGen++
        runCatching { prefetchWriter?.cancel() }
        runCatching { warmWriter?.cancel() }
        prefetchWriter = null
    }

    private fun startFolderPrefetch() {
        if (!::simpleCache.isInitialized || playbackActive) return
        stopPrefetch()
        val gen = prefetchGen
        val candidates = nodes.filter { !it.folder && !it.pdf && !it.image && it.uri?.scheme == "https" }
        if (candidates.isEmpty()) { downloadStatus = "İndirilecek video yok"; return }
        Thread {
            var done = 0
            var fails = 0
            if (System.currentTimeMillis() < driveBlockedUntil) return@Thread
            runOnUiThread { downloadStatus = "Çevrimdışı hazırlık başlıyor…" }
            for (node in candidates) {
                if (gen != prefetchGen) return@Thread
                val uri = node.uri ?: continue
                val key = cacheKey(uri)
                if (isOfflineReady(node)) { done++; continue }
                try {
                    val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
                    val writer = CacheWriter(cacheFactory.createDataSource(), spec, null, null)
                    prefetchWriter = writer
                    writer.cache()
                    val pl = simpleCache.getContentMetadata(key).get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                    if (pl in 0L..1_000_000L) { simpleCache.removeResource(key); throw java.io.IOException("Geçersiz yanıt") }
                    done++
                    fails = 0
                    if (gen == prefetchGen) runOnUiThread {
                        offlineReady[node.id] = isOfflineReady(node)
                        downloadStatus = "Çevrimdışı hazırlanıyor: $done/${candidates.size}"
                    }
                } catch (_: Exception) {
                    if (gen != prefetchGen) return@Thread
                    fails++
                    if (fails >= 2) {
                        driveBlockedUntil = System.currentTimeMillis() + 10 * 60_000L
                        runOnUiThread { downloadStatus = "Drive indirmeye izin vermiyor, 10 dk sonra tekrar denenecek" }
                        return@Thread
                    }
                    runOnUiThread { downloadStatus = "İndirme durakladı: ${node.name}" }
                } finally {
                    prefetchWriter = null
                }
                val maxBytes = minOf(24L * 1024 * 1024 * 1024, (filesDir.usableSpace.coerceAtLeast(256L * 1024 * 1024) * 0.60).toLong())
                if (simpleCache.cacheSpace >= (maxBytes * 0.90).toLong()) break
            }
            if (gen == prefetchGen) runOnUiThread {
                offlineReady.clear()
                candidates.forEach { offlineReady[it.id] = isOfflineReady(it) }
                downloadStatus = "Çevrimdışı hazırlık tamamlandı"
            }
        }.apply { name = "RetailLoop-CachePrefetch"; isDaemon = true; start() }
    }

    private fun clean(n: String) = n.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')

    // Public Google Drive folders can be read from Drive's embedded folder view without an API key.
    private fun parse(html: String): List<Node> {
        val result = mutableListOf<Node>()
        val itemRegex = Regex("""(?is)<a[^>]+href="([^"]+)"[^>]*>.*?class="flip-entry-title"[^>]*>(.*?)</div>""")
        val idRegex = Regex("""/(?:file/d|drive/folders)/([A-Za-z0-9_-]+)|[?&]id=([A-Za-z0-9_-]+)""")
        for (m in itemRegex.findAll(html)) {
            val link = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            val name = Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val idMatch = idRegex.find(link) ?: continue
            val id = idMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            if (name.isBlank()) continue
            val folder = link.contains("/folders/")
            val lowerName = name.lowercase()
            val isPdf = lowerName.endsWith(".pdf")
            val isImage = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp").any { lowerName.endsWith(it) }
            val uri = if (folder) null else Uri.parse("https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t")
            if (result.none { it.id == id }) result += Node(id, clean(name), folder, uri, isPdf, isImage)
        }
        return result
    }

    private fun fetchPublicFolder(id: String): String {
        val connection = URL("https://drive.google.com/embeddedfolderview?id=" + id + "#list").openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        return connection.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun load() {
        val id = stack.last().first
        if (id == "usb") {
            val l = mutableListOf<Node>()
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME), null, null, "${MediaStore.Video.Media.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) l += Node(c.getLong(0).toString(), clean(c.getString(1)), false, Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
            }
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), null, null, "${MediaStore.Images.Media.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) l += Node("img_" + c.getLong(0), clean(c.getString(1)), false, Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()), image = true)
            }
            nodes.clear(); nodes.addAll(l); message = if (l.isEmpty()) "Medya bulunamadı. Flash belleği takın." else ""
            tryPending()
            return
        }
        fun show(txt: String) {
            nodes.clear()
            nodes.addAll(parse(txt))
            message = if (nodes.isEmpty()) "Klasör listelenemedi veya boş. Drive paylaşımını herkes görüntüleyebilir olarak kontrol edin." else ""
            if (nodes.isNotEmpty()) refreshOffline()
            tryPending()
        }
        prefs.getString("f_$id", null)?.let { runCatching { show(it) } }
        loading = true
        Thread {
            try {
                val txt = fetchPublicFolder(id)
                prefs.edit().putString("f_$id", txt).apply()
                runOnUiThread { if (stack.last().first == id) show(txt); loading = false }
            } catch (_: Exception) { runOnUiThread { loading = false; if (nodes.isEmpty()) message = "Bağlantı kurulamadı" } }
        }.start()
    }

    private fun mediaItemFor(node: Node): MediaItem {
        val lower = node.name.lowercase()
        val mime = when {
            lower.endsWith(".mkv") -> "application/x-matroska"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") || lower.endsWith(".m2ts") -> "video/mp2t"
            lower.endsWith(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            else -> null
        }
        val src = if (isOfflineReady(node)) Uri.fromFile(localFile(node.id)) else node.uri!!
        return MediaItem.Builder().setUri(src).apply { if (mime != null) setMimeType(mime) }.build()
    }

    private fun open(n: Node) {
        playerError = ""
        diagText = ""
        if (n.folder) { stack.add(Pair(n.id, n.name)); load(); return }
        if (n.pdf || n.image) { openDocument(n); return }
        val v = nodes.filter { !it.folder && !it.pdf && !it.image }
        playlist.clear(); playlist.addAll(v)
        val i = v.indexOf(n).coerceAtLeast(0)
        prefs.edit().putString("last_id", n.id).putString("last_folder", stack.last().first).putString("last_folder_name", stack.last().second).apply()
        val sourceFactory = DefaultMediaSourceFactory(playFactory)
        val localFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this))
        val mediaSources = v.map { node ->
            val item = mediaItemFor(node)
            val sch = item.localConfiguration?.uri?.scheme
            if (sch == "content" || sch == "file") localFactory.createMediaSource(item) else sourceFactory.createMediaSource(item)
        }
        player.setMediaSources(mediaSources, i, 0L)
        stopPrefetch()
        playbackActive = true
        prefetchCancelled = false
        retryCount = 0
        playerStatus = ""
        playerOpen = true
        overlay = true
        player.prepare()
        player.play()
        current = i
        playing = true
        playbackSpeed = 1f
        player.setPlaybackParameters(PlaybackParameters(1f))
    }
    private fun closeDocument() {
        viewerLoadToken++
        runCatching { pdfRenderer?.close() }
        runCatching { pdfDescriptor?.close() }
        pdfRenderer = null
        pdfDescriptor = null
        viewerBitmap?.recycle()
        viewerBitmap = null
        viewerNode = null
        viewerError = ""
    }

    private fun openDocument(node: Node) {
        closeDocument()
        viewerNode = node
        viewerPage = 0
        viewerPageCount = 1
        val token = ++viewerLoadToken
        Thread {
            try {
                val file = File(cacheDir, "viewer_" + node.id.replace(Regex("[^A-Za-z0-9_-]"), "_"))
                if (node.uri?.scheme == "content") {
                    contentResolver.openInputStream(node.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                } else {
                    val connection = URL(node.uri.toString()).openConnection()
                    connection.connectTimeout = 20000
                    connection.readTimeout = 45000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.setRequestProperty("Referer", "https://drive.google.com/")
                    connection.getInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                if (node.pdf) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(fd)
                    if (token != viewerLoadToken) { renderer.close(); fd.close(); return@Thread }
                    pdfDescriptor = fd
                    pdfRenderer = renderer
                    viewerPageCount = renderer.pageCount
                    renderPdfPage(0)
                } else {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Görsel dosyası okunamadı")
                    runOnUiThread { if (token == viewerLoadToken) viewerBitmap = bitmap else bitmap.recycle() }
                }
            } catch (e: Exception) {
                runOnUiThread { if (token == viewerLoadToken) viewerError = e.message ?: "Dosya açılamadı" }
            }
        }.start()
    }

    private fun renderPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        try {
            val page = renderer.openPage(index)
            val scale = minOf(2.0, 2200.0 / maxOf(page.width, page.height))
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            runOnUiThread {
                viewerBitmap?.recycle()
                viewerBitmap = bitmap
                viewerPage = index
            }
        } catch (e: Exception) {
            runOnUiThread { viewerError = e.message ?: "PDF sayfası görüntülenemedi" }
        }
    }

    private fun up() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); load() } }
    private fun stopPlay() {
        retryHandler.removeCallbacksAndMessages(null)
        player.stop(); player.clearMediaItems()
        playing = false; playbackActive = false; playerOpen = false; overlay = false
        playerError = ""; playerStatus = ""; retryCount = 0
        if (fullscreen) applyFullscreen(false)
        window.decorView.postDelayed({ maybeInstall() }, 1500)
    }
    private fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) else 0
    }
    private fun setSpeed(speed: Float) { playbackSpeed = speed; player.setPlaybackParameters(PlaybackParameters(speed)) }
    private fun play(i: Int) {
        if (i !in playlist.indices) return
        runCatching {
            player.seekTo(i, 0L)
            player.play()
            current = i
            playing = true
            playerError = ""
        }.onFailure { playerError = "Video değiştirilemedi." }
    }

    private fun seekToPosition(position: Long) {
        val duration = player.duration
        if (player.currentMediaItem == null || duration <= 0L) return
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        runCatching { player.seekTo(position.coerceIn(0L, duration)) }
            .onFailure { playerError = "İleri/geri sarma başarısız. Akış bağlantısını kontrol edin." }
    }

    private fun seekBy(ms: Long) {
        if (player.currentMediaItem == null) return
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        val duration = player.duration
        if (duration <= 0L) return
        seekToPosition((player.currentPosition + ms).coerceIn(0L, duration))
    }

    private fun openInExternalPlayer() {
        val node = playlist.getOrNull(current) ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(node.uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Videoyu hangi oynatıcıda açalım?"))
        } catch (_: Exception) {
            playerError = "Bu kapsayıcı Media3 tarafından desteklenmiyor. VLC gibi uyumlu bir TV oynatıcı yükleyip tekrar deneyin."
        }
    }

    private fun retryPlayback() {
        val index = player.currentMediaItemIndex.takeIf { it >= 0 } ?: current
        val position = player.currentPosition.coerceAtLeast(0L)
        playerError = ""
        runCatching {
            if (player.mediaItemCount > 0) {
                player.seekTo(index, position)
                player.prepare()
                player.play()
            } else if (playlist.getOrNull(index) != null) {
                player.setMediaItem(mediaItemFor(playlist[index]), position)
                player.prepare()
                player.play()
            }
        }.onFailure { playerError = "Tekrar deneme başarısız." }
    }

    private fun applyLoop() {
        player.repeatMode = when (loopMode) { 0 -> Player.REPEAT_MODE_ONE; 1 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
        prefs.edit().putInt("loop", loopMode).apply()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Controls hidden: down/OK shows them. When visible, Compose owns D-pad focus navigation.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && playerOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (current < playlist.lastIndex) play(current + 1); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (current > 0) play(current - 1); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10000); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!overlay) { seekBy(10000); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!overlay) { seekBy(-10000); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { stopPlay(); return true }
            }
            if (overlay) playerUiActivity++
            else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                overlay = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (!playerOpen || overlay) return super.onKeyDown(k, e)
        when (k) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); overlay = true; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); overlay = true; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                if (player.isPlaying) player.pause() else player.play()
                overlay = true
                return true
            }
        }
        return super.onKeyDown(k, e)
    }

    @Composable
    private fun Ui() {
        BackHandler(viewerNode != null) { closeDocument() }
        BackHandler(playerOpen && overlay) { overlay = false }
        BackHandler(playerOpen && !overlay) { stopPlay() }
        BackHandler(!playerOpen && stack.size > 1) { up() }
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C9CFF))) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0C14))) { if (viewerNode != null) ViewerScreen() else if (playerOpen) PlayerScreen() else Browser() }
            updateInfo?.let { (ver, notes) ->
                if (!playerOpen) AlertDialog(
                    onDismissRequest = { lastInstallPrompt = System.currentTimeMillis(); updateInfo = null },
                    title = { Text("Yeni güncelleme var · $ver") },
                    text = { Text(notes.take(600), fontSize = 15.sp) },
                    confirmButton = { Button(onClick = { installNow() }) { Text("Yükle") } },
                    dismissButton = { TextButton(onClick = { lastInstallPrompt = System.currentTimeMillis(); updateInfo = null }) { Text("Sonra") } }
                )
            }
        }
    }

    @Composable
    private fun BrandMark(dimension: androidx.compose.ui.unit.Dp) {
        Box(Modifier.size(dimension).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF5264), Color(0xFFB91F3B)))),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(dimension * 0.19f)) {
                val w = this.size.width
                val h = this.size.height
                drawRoundRect(Color.White.copy(alpha = 0.96f), topLeft = Offset(w * 0.05f, h * 0.12f),
                    size = Size(w * 0.9f, h * 0.68f), cornerRadius = CornerRadius(w * 0.12f))
                val p = Path().apply {
                    moveTo(w * 0.42f, h * 0.25f)
                    lineTo(w * 0.42f, h * 0.67f)
                    lineTo(w * 0.72f, h * 0.46f)
                    close()
                }
                drawPath(p, Color(0xFFD72E49))
                drawLine(Color.White, Offset(w * 0.32f, h * 0.03f), Offset(w * 0.2f, 0f), strokeWidth = w * 0.055f)
                drawLine(Color.White, Offset(w * 0.68f, h * 0.03f), Offset(w * 0.8f, 0f), strokeWidth = w * 0.055f)
            }
        }
    }

    @Composable
    private fun Browser() {
        var section by remember { mutableStateOf("Genel Bakış") }
        val firstNavFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstNavFocus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0D0F14))) {
            val compact = maxWidth < 760.dp
            val navWidth = if (compact) 176.dp else 236.dp
            val videos = nodes.filter { !it.folder && !it.pdf && !it.image }
            val folders = nodes.filter { it.folder }
            val pdfs = nodes.filter { it.pdf }
            val images = nodes.filter { it.image }
            val visible = when (section) {
                "Videolar" -> videos
                "Klasörler" -> folders
                "PDF Belgeleri" -> pdfs
                "Görseller" -> images
                else -> nodes
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(navWidth).fillMaxHeight()
                        .background(Color(0xFF151820))
                        .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(if (compact) 38.dp else 46.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("TCL Akademi", color = Color.White, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("MEDIA CENTER", color = Color(0xFF858B9A), fontSize = 8.sp, letterSpacing = 1.4.sp)
                        }
                    }
                    Spacer(Modifier.height(34.dp))
                    Text("KÜTÜPHANE", color = Color(0xFF747B8C), fontSize = 9.sp, letterSpacing = 1.7.sp,
                        modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                    SidebarItem("Genel Bakış", "⌂", section == "Genel Bakış", compact, firstNavFocus) {
                        section = "Genel Bakış"
                        if (stack.last().first != Config.FOLDER_ID) { stack.clear(); stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE)); load() }
                    }
                    SidebarItem("Videolar", "▶", section == "Videolar", compact) {
                        section = "Videolar"
                        if (stack.last().first != Config.FOLDER_ID) { stack.clear(); stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE)); load() }
                    }
                    SidebarItem("Klasörler", "▰", section == "Klasörler", compact) {
                        section = "Klasörler"
                        if (stack.last().first != Config.FOLDER_ID) { stack.clear(); stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE)); load() }
                    }
                    SidebarItem("PDF Belgeleri", "▤", section == "PDF Belgeleri", compact) {
                        section = "PDF Belgeleri"
                        if (stack.last().first != Config.FOLDER_ID) { stack.clear(); stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE)); load() }
                    }
                    SidebarItem("Görseller", "▧", section == "Görseller", compact) {
                        section = "Görseller"
                        if (stack.last().first != Config.FOLDER_ID) { stack.clear(); stack.add(Pair(Config.FOLDER_ID, Config.ROOT_TITLE)); load() }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("DEPOLAMA", color = Color(0xFF747B8C), fontSize = 9.sp, letterSpacing = 1.7.sp,
                        modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                    SidebarItem("USB / Depolama", "▣", stack.last().first == "usb", compact) {
                        section = "USB / Depolama"
                        stack.clear()
                        stack.add(Pair("usb", "USB / Depolama"))
                        load()
                    }
                    SidebarItem(if (kiosk) "Kiosk: açık" else "Kiosk: kapalı", "⟳", kiosk, compact) {
                        kiosk = !kiosk
                        prefs.edit().putBoolean("kiosk", kiosk).apply()
                    }
                    SidebarItem(if (autoDownload) "TV'ye indir: açık" else "TV'ye indir: kapalı", "⬇", autoDownload, compact) {
                        autoDownload = !autoDownload
                        prefs.edit().putBoolean("autodl", autoDownload).apply()
                        if (autoDownload) startSync()
                    }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                        if (autoDownload) Text(downloadStatus, color = Color(0xFF4DA3FF), fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
                        Text(if (loading) "İÇERİKLER YÜKLENİYOR" else "KÜTÜPHANE HAZIR",
                            color = Color(0xFF858B9A), fontSize = 9.sp, letterSpacing = 1.1.sp)
                        if (kiosk) Text("Açılışta son videodan devam eder", color = Color(0xFF747B8C), fontSize = 9.sp, modifier = Modifier.padding(bottom = 5.dp))
                        Text(videos.size.toString() + " video · " + pdfs.size + " PDF · " + images.size + " görsel",
                            color = Color(0xFFB7BBC5), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF252936)))
                Column(Modifier.weight(1f).fillMaxHeight().padding(
                    start = if (compact) 22.dp else 42.dp,
                    end = if (compact) 22.dp else 42.dp,
                    top = if (compact) 22.dp else 34.dp,
                    bottom = 20.dp
                )) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (stack.size > 1) stack.last().second else section,
                                color = Color.White, fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when (section) {
                                    "Videolar" -> "İzlemeye hazır videolar"
                                    "Klasörler" -> "Eğitim içeriklerinin klasörleri"
                                    "PDF Belgeleri" -> "Dokümanlar ve eğitim materyalleri"
                                    "Görseller" -> "JPEG, PNG ve diğer görseller"
                                    "USB / Depolama" -> "Bağlı harici depolama"
                                    else -> "İçeriklerini tek ekrandan yönet"
                                },
                                color = Color(0xFF9298A7), fontSize = if (compact) 11.sp else 13.sp,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (loading) "YÜKLENİYOR" else visible.size.toString() + " ÖĞE",
                                color = Color(0xFFB7BBC5), fontSize = 10.sp, letterSpacing = 1.1.sp)
                            if (stack.size > 1 && stack.last().first != "usb") {
                                PlayerChip("‹ Üst klasör", false) { up() }
                            }
                        }
                    }
                    Spacer(Modifier.height(if (compact) 20.dp else 28.dp))
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (loading) "İçerikler yükleniyor…" else "Burada henüz içerik yok",
                                    color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                if (message.isNotBlank()) Text(message, color = Color(0xFF9298A7), fontSize = 12.sp,
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).widthIn(max = 440.dp))
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (compact) 280.dp else 340.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 24.dp, end = 4.dp)
                        ) {
                            items(visible, key = { it.id }) { n -> Tile(n, compact) { open(n) } }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SidebarItem(label: String, symbol: String, selected: Boolean, compact: Boolean, requester: FocusRequester? = null, click: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .background(when { focused -> Color(0xFF343B4A); selected -> Color(0xFF292F3D); else -> Color.Transparent })
                .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (selected) Color(0xFF41495B) else Color.Transparent, shape)
                .focusable().clickable { click() }
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(symbol, color = if (selected || focused) Color(0xFFFF6576) else Color(0xFFA3A9B7),
                fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected || focused) Color.White else Color(0xFFB5BAC6),
                fontSize = if (compact) 11.sp else 13.sp, fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun Tile(n: Node, compact: Boolean, click: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; }
                .background(if (focused) Color(0xFF303239) else Color(0xFF1C1E23), shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF303239), shape)
                .clip(shape).focusable().clickable { click() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(if (compact) 92.dp else 116.dp, if (compact) 58.dp else 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (n.folder) Color(0xFF303A4A) else if (n.pdf) Color(0xFF493039) else Color(0xFF292D36)),
                contentAlignment = Alignment.Center) {
                Text(if (n.folder) "▰" else if (n.pdf) "PDF" else if (n.image) "JPG" else "▶",
                    color = if (n.pdf || n.image) Color(0xFFFF9AA7) else Color.White,
                    fontSize = if (n.pdf || n.image) 17.sp else 25.sp, fontWeight = FontWeight.Bold)
                if (offlineReady[n.id] == true) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF2F8BFF)), contentAlignment = Alignment.Center) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, color = Color.White, fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(if (n.folder) "KLASÖR" else if (n.pdf) "PDF BELGESİ" else if (n.image) "GÖRSEL" else "VİDEO",
                    color = Color(0xFF9B9EA7), fontSize = 9.sp, letterSpacing = 1.2.sp)
                if (offlineReady[n.id] == true) Text("TV\'DE KAYITLI", color = Color(0xFF4DA3FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                else dlProgress[n.id]?.let { Text("İNDİRİLİYOR %$it", color = Color(0xFFFFD28A), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            Text("›", color = if (focused) Color.White else Color(0xFF777B84), fontSize = 25.sp)
        }
    }

    private fun DrawScope.folderIcon() {
        drawRoundRect(Color(0xFF5B6CFF), Offset.Zero, Size(size.width * 0.42f, size.height * 0.3f), CornerRadius(10f))
        drawRoundRect(Color(0xFF7C9CFF), Offset(0f, size.height * 0.14f), Size(size.width, size.height * 0.86f), CornerRadius(14f))
    }
    private fun DrawScope.videoIcon() {
        drawRoundRect(Color(0xFF2E3558), Offset.Zero, size, CornerRadius(14f))
        val p = Path().apply { moveTo(size.width * 0.4f, size.height * 0.28f); lineTo(size.width * 0.4f, size.height * 0.72f); lineTo(size.width * 0.7f, size.height * 0.5f); close() }
        drawPath(p, Color.White)
    }

    @Composable
    private fun PlayerScreen() {
        var tick by remember { mutableIntStateOf(0) }
        var isSeeking by remember { mutableStateOf(false) }
        var settingsMenu by remember { mutableStateOf(false) }
        val playRequester = remember { FocusRequester() }
        LaunchedEffect(overlay) { if (overlay) { delay(80); runCatching { playRequester.requestFocus() } } }
        LaunchedEffect(playing) {
            while (playing) {
                if (!isSeeking) playPosition = player.currentPosition.coerceAtLeast(0L)
                playDuration = player.duration.takeIf { it > 0 } ?: 0L
                delay(300)
            }
        }
        LaunchedEffect(overlay, tick, playerUiActivity) {
            if (overlay) { delay(5000); overlay = false }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val compact = maxWidth < 600.dp
            val side = if (compact) 12.dp else 28.dp
            AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = this@MainActivity.player; isFocusable = false } }, modifier = Modifier.fillMaxSize())
            if (playerStatus.isNotBlank() && playerError.isBlank()) {
                Text(playerStatus, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 14.dp))
            }
            if (lastErr.isNotBlank() && (playerStatus.isNotBlank() || playerError.isNotBlank())) {
                Text("HATA: " + lastErr + (if (diagText.isNotBlank()) "\n" + diagText else ""), color = Color(0xFFFFD28A), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp, start = 24.dp, end = 24.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            if (playerError.isNotBlank()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bu video oynatılamadı", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(playerError, color = Color(0xFFFFAAB2), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    Text("Bağlantıyı yeniden kurmayı deneyebilirsin.", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    PlayerChip("Harici oynatıcıda aç", true) { openInExternalPlayer() }
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(overlay) {
                detectTapGestures(onTap = { overlay = !overlay; tick++ })
            })
            val slim = maxHeight < 520.dp
            AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD0090B11))))
                        .padding(start = side, end = side, top = if (slim) 14.dp else 22.dp, bottom = if (slim) 4.dp else 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(playlist.getOrNull(current)?.name ?: "", color = Color.White,
                            fontSize = if (slim) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${formatTime(playPosition)} / ${formatTime(playDuration)}",
                            color = Color(0xFFBFC5D4), fontSize = if (slim) 9.sp else 11.sp)
                    }
                    SeekBar(
                        pos = playPosition, dur = playDuration,
                        onSeek = { seekToPosition(it); playPosition = it; overlay = true; tick++ },
                        onStep = { seekBy(it); playPosition = player.currentPosition.coerceAtLeast(0L); overlay = true; tick++ }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (playerError.isNotBlank()) PlayerChip("↻", false) { retryPlayback(); overlay = true; tick++ }
                        PlayerChip("⏮", false) { if (current > 0) play(current - 1); overlay = true; tick++ }
                        PlayerChip("−10", false) { seekBy(-10000); overlay = true; tick++ }
                        PlayerChip(if (player.isPlaying) "Ⅱ" else "▶", true, playRequester) { if (player.isPlaying) player.pause() else player.play(); overlay = true; tick++ }
                        PlayerChip("+10", false) { seekBy(10000); overlay = true; tick++ }
                        PlayerChip("⏭", false) { if (current < playlist.lastIndex) play(current + 1); overlay = true; tick++ }
                        Box {
                            PlayerChip("⚙", false) { settingsMenu = true; tick++ }
                            DropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                                Text("OYNATMA HIZI", modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                    color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}x" + if (playbackSpeed == speed) "  ✓" else "") },
                                        onClick = { setSpeed(speed); settingsMenu = false; overlay = true; tick++ })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (loopMode == 0) "Tekrar: Video" else if (loopMode == 1) "Tekrar: Liste" else "Tekrar: Kapalı") },
                                    onClick = { loopMode = (loopMode + 1) % 3; applyLoop(); settingsMenu = false; overlay = true; tick++ }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (fullscreen) "Tam ekrandan çık" else "Tam ekran") },
                                    onClick = { applyFullscreen(!fullscreen); settingsMenu = false; overlay = true; tick++ }
                                )
                                DropdownMenuItem(text = { Text("Oynatmayı durdur") },
                                    onClick = { settingsMenu = false; stopPlay() })
                            }
                        }
                    }
                }
            }
        }
    }

    // Ince, TV kumandasıyla (◀ ▶) ve dokunmatikle çalışan ilerleme çubuğu.
    @Composable
    private fun SeekBar(pos: Long, dur: Long, onSeek: (Long) -> Unit, onStep: (Long) -> Unit) {
        var focused by remember { mutableStateOf(false) }
        var drag by remember { mutableStateOf(-1f) }
        val frac = if (drag >= 0f) drag else if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
        val trackH = if (focused) 6.dp else 3.dp
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(20.dp)
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && dur > 0L) {
                        when (e.key) {
                            Key.DirectionLeft -> { onStep(-10_000L); true }
                            Key.DirectionRight -> { onStep(10_000L); true }
                            else -> false
                        }
                    } else false
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .pointerInput(dur) { detectTapGestures { o -> if (dur > 0L) onSeek((o.x / size.width * dur).toLong()) } }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { if (dur > 0L && drag >= 0f) onSeek((drag * dur).toLong()); drag = -1f },
                        onDragCancel = { drag = -1f },
                        onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(trackH).clip(RoundedCornerShape(50)).background(Color(0x44FFFFFF)))
            Box(Modifier.width(w * frac).height(trackH).clip(RoundedCornerShape(50)).background(Color(0xFFFF5264)))
            Box(Modifier.offset(x = (w * frac) - 6.dp).size(if (focused) 14.dp else 11.dp).clip(RoundedCornerShape(50)).background(Color.White))
        }
    }

    @Composable
    private fun ViewerScreen() {
        val node = viewerNode
        Column(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF171B25)).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (node?.pdf == true) "PDF GÖRÜNTÜLEYİCİ" else "GÖRSEL GÖRÜNTÜLEYİCİ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(node?.name ?: "", color = Color(0xFFB8BECA), fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(20.dp))
                PlayerChip("Kapat", false) { closeDocument() }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bitmap = viewerBitmap
                if (bitmap != null) {
                    AndroidView(factory = { context -> ImageView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                    } }, update = { it.setImageBitmap(bitmap) }, modifier = Modifier.fillMaxSize())
                } else if (viewerError.isNotBlank()) {
                    Text("Dosya açılamadı: $viewerError", color = Color(0xFFFFAAB2), textAlign = TextAlign.Center, modifier = Modifier.padding(30.dp))
                } else {
                    CircularProgressIndicator(color = Color(0xFF7C9CFF))
                }
            }
            if (node?.pdf == true) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF171B25)).padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    PlayerChip("‹ Önceki", false) { if (viewerPage > 0) renderPdfPage(viewerPage - 1) }
                    Text("${viewerPage + 1} / ${viewerPageCount}", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
                    PlayerChip("Sonraki ›", false) { if (viewerPage + 1 < viewerPageCount) renderPdfPage(viewerPage + 1) }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    @Composable
    private fun PlayerChip(t: String, on: Boolean, requester: FocusRequester? = null, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { f = it.isFocused }.scale(if (f) 1.08f else 1f)
            .clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7C9CFF) else Color(0x33FFFFFF))
            .border(if (f) 3.dp else 0.dp, Color.White, RoundedCornerShape(50))
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusable().clickable { click() }.padding(horizontal = 9.dp, vertical = 5.dp)) {
            Text(t, color = if (on) Color(0xFF0A0C14) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun PlaylistCard(i: Int, d: Node, active: Boolean, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        val g = if (active) listOf(Color(0xFF4F6BFF), Color(0xFF9B5CFF)) else listOf(Color(0xFF2A2F45), Color(0xFF171A29))
        Box(Modifier.size(240.dp, 135.dp).onFocusChanged { f = it.isFocused }.scale(if (f) 1.1f else 1f)
            .clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(g))
            .border(if (f) 3.dp else 0.dp, Color.White, RoundedCornerShape(16.dp)).focusable().clickable { click() }.padding(16.dp)) {
            Text("%02d".format(i + 1), color = Color(0x55FFFFFF), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopStart))
            Text(d.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
            if (active) Text("▶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}
