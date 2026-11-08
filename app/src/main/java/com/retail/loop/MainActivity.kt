package com.retail.loop

import android.Manifest
import android.content.*
import android.text.Html
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder

data class Node(val id: String, val name: String, val folder: Boolean, val uri: Uri?, val pdf: Boolean = false)

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var cache: SimpleCache
    private lateinit var dsFactory: CacheDataSource.Factory
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    private val nodes = mutableStateListOf<Node>()
    private val stack = mutableStateListOf(Pair(Config.FOLDER_ID, Config.ROOT_TITLE))
    private val playlist = mutableStateListOf<Node>()
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var current by mutableIntStateOf(0)
    private var loopMode by mutableIntStateOf(1)
    private var overlay by mutableStateOf(false)
    private var playPosition by mutableLongStateOf(0L)
    private var playDuration by mutableLongStateOf(0L)
    private var fullscreen by mutableStateOf(false)
    private var playbackSpeed by mutableFloatStateOf(1f)

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { window.decorView.postDelayed({ if (stack.last().first == "usb") load() }, 2500) }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (stack.last().first == "usb") load() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loopMode = prefs.getInt("loop", 1)
        cache = SimpleCache(File(filesDir, "vid"), LeastRecentlyUsedCacheEvictor(Config.CACHE_GB * 1024L * 1024L * 1024L), StandaloneDatabaseProvider(this))
        dsFactory = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(20000)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(20000, 60000, 2000, 4000)
            .setTargetBufferBytes(200 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).setLoadControl(lc).setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory)).build().apply { playWhenReady = true }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { current = player.currentMediaItemIndex; prefetch(current + 1) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        })
        applyLoop()
        registerReceiver(mountReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file")
        })
        setContent { Ui() }
        perm.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
        load()
    }

    override fun onDestroy() { unregisterReceiver(mountReceiver); player.release(); cache.release(); super.onDestroy() }

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
            val isPdf = name.lowercase().endsWith(".pdf")
            val uri = if (folder) null else Uri.parse("https://drive.google.com/uc?export=download&id=" + id + "&confirm=t")
            if (result.none { it.id == id }) result += Node(id, clean(name), folder, uri, isPdf)
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
            nodes.clear(); nodes.addAll(l); message = if (l.isEmpty()) "Video bulunamadı. Flash belleği takın." else ""
            return
        }
        fun show(txt: String) {
            nodes.clear()
            if (id == Config.FOLDER_ID) nodes.add(Node("usb", "USB / Depolama", true, null))
            nodes.addAll(parse(txt)); message = if (nodes.isEmpty()) "Klasör listelenemedi veya boş. Drive paylaşımını herkes görüntüleyebilir olarak kontrol edin." else ""
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
        if (n.folder) { stack.add(Pair(n.id, n.name)); load(); return }
        if (n.pdf) {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(n.uri, "application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { message = "PDF açmak için cihazda PDF görüntüleyici bulunamadı." }
            return
        }
        val v = nodes.filter { !it.folder && !it.pdf }
        playlist.clear(); playlist.addAll(v)
        val i = v.indexOf(n).coerceAtLeast(0)
        player.setMediaItems(v.map { MediaItem.fromUri(it.uri!!) }, i, 0L)
        player.prepare(); player.play(); current = i; playing = true; overlay = true; playbackSpeed = 1f; player.setPlaybackParameters(PlaybackParameters(1f)); prefetch(i + 1)
    }
    private fun up() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); load() } }
    private fun stopPlay() { player.stop(); player.clearMediaItems(); playing = false; overlay = false; if (fullscreen) applyFullscreen(false) }
    private fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) else 0
    }
    private fun setSpeed(speed: Float) { playbackSpeed = speed; player.setPlaybackParameters(PlaybackParameters(speed)) }
    private fun play(i: Int) { if (i in playlist.indices) { player.seekTo(i, 0); player.play(); current = i; playing = true } }
    private fun seekBy(ms: Long) { val d = player.duration.takeIf { it > 0 } ?: return; player.seekTo((player.currentPosition + ms).coerceIn(0L, d)) }

    private fun prefetch(i: Int) {
        if (playlist.isEmpty()) return
        val d = playlist[i % playlist.size]
        if (d.uri?.scheme != "https") return
        Thread { try { CacheWriter(dsFactory.createDataSource(), DataSpec.Builder().setUri(d.uri!!).setLength(24L * 1024 * 1024).build(), null, null).cache() } catch (_: Exception) {} }.start()
    }

    private fun applyLoop() {
        player.repeatMode = when (loopMode) { 0 -> Player.REPEAT_MODE_ONE; 1 -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
        prefs.edit().putInt("loop", loopMode).apply()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (!playing || overlay) return super.onKeyDown(k, e)
        val n = playlist.size.coerceAtLeast(1)
        when (k) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { play((current + 1) % n); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { play((current - 1 + n) % n); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { overlay = true; return true }
        }
        return super.onKeyDown(k, e)
    }

    @Composable
    private fun Ui() {
        BackHandler(playing && overlay) { overlay = false }
        BackHandler(playing && !overlay) { stopPlay() }
        BackHandler(!playing && stack.size > 1) { up() }
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C9CFF))) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0C14))) { if (playing) PlayerScreen() else Browser() }
        }
    }

    @Composable
    private fun Browser() {
        var category by remember { mutableStateOf("Tümü") }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101114))) {
            val compact = maxWidth < 760.dp
            val horizontal = if (compact) 18.dp else 42.dp
            val videos = nodes.filter { !it.folder && !it.pdf }
            val folders = nodes.filter { it.folder }
            val pdfs = nodes.filter { it.pdf }
            val visible = when (category) {
                "Videolar" -> videos
                "Klasörler" -> folders
                "PDF'ler" -> pdfs
                else -> nodes
            }
            Column(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = if (compact) 14.dp else 26.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", color = Color(0xFFE5394F), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TCL Akademi", color = Color.White, fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Bold)
                        Text("MEDYA KÜTÜPHANESİ", color = Color(0xFF92959D), fontSize = 9.sp, letterSpacing = 2.sp)
                    }
                    Text(if (loading) "Yükleniyor…" else videos.size.toString() + " video", color = Color(0xFFB8BBC3), fontSize = 12.sp)
                }
                if (stack.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("‹ Geri", false) { up() }
                        Spacer(Modifier.width(12.dp))
                        Text(stack.joinToString("  /  ") { it.second }, color = Color(0xFF9A9DA5), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(if (compact) 18.dp else 26.dp))
                Text(if (stack.size == 1) "Kütüphane" else stack.last().second,
                    color = Color.White, fontSize = if (compact) 25.sp else 31.sp, fontWeight = FontWeight.Bold)
                Text("İzlemek istediğin içeriği seç.", color = Color(0xFF92959D), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tümü", "Videolar", "Klasörler", "PDF'ler").forEach { item ->
                        Box(Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (category == item) Color(0xFFE5394F) else Color(0xFF24262B))
                            .clickable { category = item }
                            .padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(item, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (loading) "İçerikler yükleniyor…" else "İçerik bulunamadı",
                                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (message.isNotBlank()) Text(message, color = Color(0xFF92959D), fontSize = 12.sp,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (compact) 320.dp else 390.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(visible) { n -> Tile(n, compact) { open(n) } }
                    }
                }
            }
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
                .clip(shape).clickable { click() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(if (compact) 92.dp else 116.dp, if (compact) 58.dp else 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (n.folder) Color(0xFF303A4A) else if (n.pdf) Color(0xFF493039) else Color(0xFF292D36)),
                contentAlignment = Alignment.Center) {
                Text(if (n.folder) "▰" else if (n.pdf) "PDF" else "▶",
                    color = if (n.pdf) Color(0xFFFF9AA7) else Color.White,
                    fontSize = if (n.pdf) 17.sp else 25.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, color = Color.White, fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(if (n.folder) "KLASÖR" else if (n.pdf) "PDF BELGESİ" else "VİDEO",
                    color = Color(0xFF9B9EA7), fontSize = 9.sp, letterSpacing = 1.2.sp)
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
        LaunchedEffect(overlay, tick) {
            if (overlay) { delay(5000); overlay = false }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val compact = maxWidth < 600.dp
            val side = if (compact) 14.dp else 44.dp
            AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = this@MainActivity.player; isFocusable = false } }, modifier = Modifier.fillMaxSize())
            // A tap anywhere on the video brings the YouTube-style controls back.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { overlay = true; tick++ })
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
                        Box {
                            Chip("${playbackSpeed}x", false) { speedMenu = true; tick++ }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { Text(if (speed == 1f) "Normal  (1.0x)" else "${speed}x") },
                                        onClick = { setSpeed(speed); speedMenu = false; overlay = true; tick++ }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Chip(if (fullscreen) "⤢ Küçült" else "⛶ Tam ekran", false) { applyFullscreen(!fullscreen); overlay = true; tick++ }
                    }
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                    Slider(
                        value = if (playDuration > 0) playPosition.coerceIn(0L, playDuration).toFloat() else 0f,
                        onValueChange = { isSeeking = true; playPosition = it.toLong(); overlay = true },
                        onValueChangeFinished = { player.seekTo(playPosition.coerceIn(0L, playDuration)); isSeeking = false; tick++ },
                        valueRange = 0f..playDuration.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp)
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(playPosition), color = Color.White, fontSize = if (compact) 10.sp else 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(playDuration), color = Color.White, fontSize = if (compact) 10.sp else 12.sp)
                    }
                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Chip("⏮", false) { if (current > 0) play(current - 1); overlay = true; tick++ }
                        Chip("↶ 10 sn", false) { seekBy(-10000); overlay = true; tick++ }
                        Chip(if (player.isPlaying) "Ⅱ" else "▶", true) { if (player.isPlaying) player.pause() else player.play(); overlay = true; tick++ }
                        Chip("10 sn ↷", false) { seekBy(10000); overlay = true; tick++ }
                        Chip("⏭", false) { if (current < playlist.lastIndex) play(current + 1); overlay = true; tick++ }
                        Chip("■", false) { stopPlay() }
                        Chip(if (loopMode == 0) "Tekrar: Video" else if (loopMode == 1) "Tekrar: Liste" else "Tekrar: Kapalı", false) { loopMode = (loopMode + 1) % 3; applyLoop(); overlay = true; tick++ }
                    }
                    Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                    val st = rememberLazyListState()
                    LaunchedEffect(overlay) { if (overlay) st.scrollToItem(current) }
                    LazyRow(state = st, contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(playlist) { i, d -> Card(i, d, i == current) { play(i); overlay = true; tick++ } }
                    }
                }
            }
            if (!overlay) {
                Text("Ekrana dokun: kontroller", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    @Composable
    private fun Chip(t: String, on: Boolean, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { f = it.isFocused }.scale(if (f) 1.08f else 1f)
            .clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7C9CFF) else Color(0x33FFFFFF))
            .border(if (f) 3.dp else 0.dp, Color.White, RoundedCornerShape(50))
            .clickable { click() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(t, color = if (on) Color(0xFF0A0C14) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun Card(i: Int, d: Node, active: Boolean, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        val g = if (active) listOf(Color(0xFF4F6BFF), Color(0xFF9B5CFF)) else listOf(Color(0xFF2A2F45), Color(0xFF171A29))
        Box(Modifier.size(240.dp, 135.dp).onFocusChanged { f = it.isFocused }.scale(if (f) 1.1f else 1f)
            .clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(g))
            .border(if (f) 3.dp else 0.dp, Color.White, RoundedCornerShape(16.dp)).clickable { click() }.padding(16.dp)) {
            Text("%02d".format(i + 1), color = Color(0x55FFFFFF), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopStart))
            Text(d.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
            if (active) Text("▶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}
