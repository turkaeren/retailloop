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

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { window.decorView.postDelayed({ if (stack.last().first == "usb") load() }, 2500) }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (stack.last().first == "usb") load() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loopMode = prefs.getInt("loop", 1)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(45000)
            .setDefaultRequestProperties(mapOf("Referer" to "https://drive.google.com/", "Accept" to "*/*"))
        val upstream = DefaultDataSource.Factory(this, httpFactory)
        val freeBytes = filesDir.usableSpace.coerceAtLeast(256L * 1024 * 1024)
        val cacheLimit = minOf(24L * 1024 * 1024 * 1024, (freeBytes * 0.60).toLong()).coerceAtLeast(256L * 1024 * 1024)
        simpleCache = SimpleCache(File(cacheDir, "retail_media_cache"), LeastRecentlyUsedCacheEvictor(cacheLimit), StandaloneDatabaseProvider(this))
        cacheFactory = CacheDataSource.Factory().setCache(simpleCache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec -> spec.uri.getQueryParameter("id") ?: spec.key ?: spec.uri.toString() }
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 120000, 6000, 15000)
            .setTargetBufferBytes(160 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val sourceFactory = DefaultMediaSourceFactory(cacheFactory)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(10))
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
                if (isPlaying) { playerError = ""; playerStatus = ""; retryCount = 0 }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) playerStatus = "Arabelleğe alınıyor…"
                if (state == Player.STATE_READY) { playerStatus = ""; retryCount = 0 }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                retryPosition = player.currentPosition.coerceAtLeast(0L)
                if (retryCount < 6 && playerOpen) {
                    retryCount++
                    val waitMs = (1000L shl (retryCount - 1)).coerceAtMost(32000L)
                    playerStatus = "Bağlantı yenileniyor… (${retryCount}/6)"
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
                    playerError = "Video açılamadı: ${error.errorCodeName}. $reason"
                    playerStatus = ""
                    playing = false
                    overlay = true
                }
            }
        })
        applyLoop()
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
        perm.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
        load()
    }

    override fun onDestroy() {
        prefetchCancelled = true
        retryHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(mountReceiver) }
        closeDocument()
        player.release()
        if (::simpleCache.isInitialized) simpleCache.release()
        super.onDestroy()
    }

    private fun cacheKey(uri: Uri): String = uri.getQueryParameter("id") ?: uri.toString()
    private fun isOfflineReady(n: Node): Boolean {
        if (n.uri == null || n.uri.scheme == "content" || !::simpleCache.isInitialized) return false
        val key = cacheKey(n.uri)
        val length = simpleCache.getContentMetadata(key).get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
        return length > 0L && simpleCache.isCached(key, 0L, length)
    }
    private fun startFolderPrefetch() {
        if (!::simpleCache.isInitialized) return
        prefetchCancelled = false
        prefetchThread?.interrupt()
        val candidates = nodes.filter { !it.folder && !it.pdf && !it.image && it.uri?.scheme == "https" }
        if (candidates.isEmpty()) { downloadStatus = "İndirilecek video yok"; return }
        prefetchThread = Thread {
            var done = 0
            runOnUiThread { downloadStatus = "Çevrimdışı hazırlık başlıyor…" }
            for (node in candidates) {
                if (prefetchCancelled || Thread.currentThread().isInterrupted) break
                while (playbackActive && !prefetchCancelled) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                }
                if (prefetchCancelled) break
                val uri = node.uri ?: continue
                val key = cacheKey(uri)
                if (isOfflineReady(node)) { done++; continue }
                try {
                    val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
                    CacheWriter(cacheFactory.createDataSource(), spec, null, null).cache()
                    done++
                    runOnUiThread {
                        offlineReady[node.id] = isOfflineReady(node)
                        downloadStatus = "Çevrimdışı hazırlanıyor: $done/${candidates.size}"
                    }
                } catch (_: Exception) {
                    runOnUiThread { downloadStatus = "İndirme durakladı: ${node.name}" }
                }
                val maxBytes = minOf(24L * 1024 * 1024 * 1024, (filesDir.usableSpace.coerceAtLeast(256L * 1024 * 1024) * 0.60).toLong())
                if (simpleCache.cacheSpace >= (maxBytes * 0.90).toLong()) break
            }
            runOnUiThread {
                offlineReady.clear()
                candidates.forEach { offlineReady[it.id] = isOfflineReady(it) }
                downloadStatus = if (prefetchCancelled) "İndirme durduruldu" else "Çevrimdışı hazırlık tamamlandı"
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
            return
        }
        fun show(txt: String) {
            nodes.clear()
            nodes.addAll(parse(txt))
            message = if (nodes.isEmpty()) "Klasör listelenemedi veya boş. Drive paylaşımını herkes görüntüleyebilir olarak kontrol edin." else ""
            if (nodes.isNotEmpty()) startFolderPrefetch()
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

    private fun open(n: Node) {
        playerError = ""
        if (n.folder) { stack.add(Pair(n.id, n.name)); load(); return }
        if (n.pdf || n.image) { openDocument(n); return }
        val v = nodes.filter { !it.folder && !it.pdf && !it.image }
        playlist.clear(); playlist.addAll(v)
        val i = v.indexOf(n).coerceAtLeast(0)
        val sourceFactory = DefaultMediaSourceFactory(cacheFactory)
        val localFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this))
        val mediaSources = v.map { node ->
            val item = MediaItem.fromUri(node.uri!!)
            if (node.uri.scheme == "content") localFactory.createMediaSource(item) else sourceFactory.createMediaSource(item)
        }
        player.setMediaSources(mediaSources, i, 0L)
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
                player.setMediaItem(MediaItem.fromUri(playlist[index].uri!!), position)
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
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10000); overlay = true; playerUiActivity++; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10000); overlay = true; playerUiActivity++; return true }
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
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(if (loading) "İÇERİKLER YÜKLENİYOR" else "KÜTÜPHANE HAZIR",
                            color = Color(0xFF858B9A), fontSize = 9.sp, letterSpacing = 1.1.sp)
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
                            items(visible) { n -> Tile(n, compact) { open(n) } }
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
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
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
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, color = Color.White, fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(if (n.folder) "KLASÖR" else if (n.pdf) "PDF BELGESİ" else if (n.image) "GÖRSEL" else "VİDEO",
                    color = Color(0xFF9B9EA7), fontSize = 9.sp, letterSpacing = 1.2.sp)
                if (isOfflineReady(n)) Text("ÇEVRİMDIŞI HAZIR", color = Color(0xFF7EE0B0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
        var speedMenu by remember { mutableStateOf(false) }
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
            val side = if (compact) 14.dp else 44.dp
            AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = this@MainActivity.player; isFocusable = false } }, modifier = Modifier.fillMaxSize())
            if (playerStatus.isNotBlank() && playerError.isBlank()) {
                Text(playerStatus, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 14.dp))
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
            // A tap anywhere on the video brings the YouTube-style controls back.
            Box(Modifier.fillMaxSize().pointerInput(overlay) {
                detectTapGestures(onTap = { overlay = !overlay; tick++ })
            })
            AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF20A0C14))))
                        .padding(start = side, end = side, top = if (compact) 44.dp else 72.dp, bottom = if (compact) 16.dp else 28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ŞİMDİ OYNATILIYOR", color = Color(0xFF9AA3C0), fontSize = if (compact) 10.sp else 13.sp)
                            Text(playlist.getOrNull(current)?.name ?: "", color = Color.White, fontSize = if (compact) 16.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                    }
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                    Slider(
                        value = if (playDuration > 0) playPosition.coerceIn(0L, playDuration).toFloat() else 0f,
                        onValueChange = { isSeeking = true; playPosition = it.toLong(); overlay = true },
                        onValueChangeFinished = { seekToPosition(playPosition.coerceIn(0L, playDuration)); isSeeking = false; tick++ },
                        valueRange = 0f..playDuration.coerceAtLeast(1L).toFloat(),
                        enabled = playDuration > 0L,
                        modifier = Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp)
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(playPosition), color = Color.White, fontSize = if (compact) 10.sp else 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(playDuration), color = Color.White, fontSize = if (compact) 10.sp else 12.sp)
                    }
                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    val st = rememberLazyListState()
                    LaunchedEffect(overlay) { if (overlay) st.scrollToItem(current) }
                    LazyRow(state = st, contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(playlist) { i, d -> PlaylistCard(i, d, i == current) { play(i); overlay = true; tick++ } }
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (playerError.isNotBlank()) PlayerChip("↻", false) { retryPlayback(); overlay = true; tick++ }
                        PlayerChip("⏮", false) { if (current > 0) play(current - 1); overlay = true; tick++ }
                        PlayerChip("↶ 10", false) { seekBy(-10000); overlay = true; tick++ }
                        PlayerChip(if (player.isPlaying) "Ⅱ" else "▶", true) { if (player.isPlaying) player.pause() else player.play(); overlay = true; tick++ }
                        PlayerChip("10 ↷", false) { seekBy(10000); overlay = true; tick++ }
                        PlayerChip("⏭", false) { if (current < playlist.lastIndex) play(current + 1); overlay = true; tick++ }
                        PlayerChip("■", false) { stopPlay() }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            PlayerChip("${playbackSpeed}x", false) { speedMenu = true; tick++ }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}x") }, onClick = { setSpeed(speed); speedMenu = false; overlay = true; tick++ })
                                }
                            }
                        }
                        PlayerChip(if (loopMode == 0) "Tekrar: Video" else if (loopMode == 1) "Tekrar: Liste" else "Tekrar: Kapalı", false) { loopMode = (loopMode + 1) % 3; applyLoop(); overlay = true; tick++ }
                        PlayerChip(if (fullscreen) "⤢" else "⛶", false) { applyFullscreen(!fullscreen); overlay = true; tick++ }
                    }
                }
            }

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
    private fun PlayerChip(t: String, on: Boolean, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { f = it.isFocused }.scale(if (f) 1.08f else 1f)
            .clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7C9CFF) else Color(0x33FFFFFF))
            .border(if (f) 3.dp else 0.dp, Color.White, RoundedCornerShape(50))
            .focusable().clickable { click() }.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(t, color = if (on) Color(0xFF0A0C14) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
