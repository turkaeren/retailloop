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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
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

data class Node(val id: String, val name: String, val folder: Boolean, val uri: Uri?)

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
            val uri = if (folder) null else Uri.parse("https://drive.usercontent.google.com/download?id=" + id + "&export=download&authuser=0")
            if (result.none { it.id == id }) result += Node(id, clean(name), folder, uri)
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
        val v = nodes.filter { !it.folder }
        playlist.clear(); playlist.addAll(v)
        val i = v.indexOf(n).coerceAtLeast(0)
        player.setMediaItems(v.map { MediaItem.fromUri(it.uri!!) }, i, 0L)
        player.prepare(); player.play(); current = i; playing = true; overlay = false; prefetch(i + 1)
    }
    private fun up() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); load() } }
    private fun stopPlay() { player.stop(); player.clearMediaItems(); playing = false; overlay = false }
    private fun play(i: Int) { if (i in playlist.indices) { player.seekTo(i, 0); player.play(); current = i } }

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
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            Text(stack.joinToString("  ›  ") { it.second }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (loading) "Yükleniyor…" else message, color = Color(0xFF9AA3C0), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(nodes) { n -> Tile(n) { open(n) } }
            }
        }
    }

    @Composable
    private fun Tile(n: Node, click: () -> Unit) {
        var f by remember { mutableStateOf(false) }
        val sh = RoundedCornerShape(16.dp)
        Column(Modifier.onFocusChanged { f = it.isFocused }.scale(if (f) 1.07f else 1f).clip(sh)
            .background(if (f) Color(0xFF232842) else Color(0xFF151929))
            .border(if (f) 3.dp else 0.dp, Color.White, sh).clickable { click() }.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(96.dp, 80.dp)) { if (n.folder) folderIcon() else videoIcon() }
            Spacer(Modifier.height(10.dp))
            Text(n.name, color = Color.White, fontSize = 14.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
        LaunchedEffect(overlay, tick) { if (overlay) { delay(7000); overlay = false } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = this@MainActivity.player; isFocusable = false } }, modifier = Modifier.fillMaxSize())
            AnimatedVisibility(overlay, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A0C14)))).padding(top = 80.dp, bottom = 36.dp)) {
                    Row(Modifier.padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Şimdi oynatılıyor", color = Color(0xFF9AA3C0), fontSize = 14.sp)
                            Text(playlist.getOrNull(current)?.name ?: "", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        listOf("Tek video döngü", "Tümünü döngü", "Döngü kapalı").forEachIndexed { i, t ->
                            Chip(t, loopMode == i) { loopMode = i; applyLoop(); tick++ }
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    val st = rememberLazyListState()
                    LaunchedEffect(overlay) { if (overlay) st.scrollToItem(current) }
                    LazyRow(state = st, contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(playlist) { i, d -> Card(i, d, i == current) { play(i); tick++ } }
                    }
                }
            }
        }
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
