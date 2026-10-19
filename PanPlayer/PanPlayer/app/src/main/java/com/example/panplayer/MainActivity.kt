@file:OptIn(ExperimentalFoundationApi::class)
package com.example.panplayer
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s); Store.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0E10)) { App() }
            }
        }
    }
}

@Composable fun App() {
    var token by remember { mutableStateOf(Store.token) }
    if (token.isBlank()) Login { token = it } else Home { Store.token = ""; token = "" }
}

@Composable fun Login(onToken: (String) -> Unit) {
    var key by remember { mutableStateOf(Store.appKey) }
    var tm by remember { mutableStateOf(Store.tmdb) }
    var web by remember { mutableStateOf(false) }
    if (web) {
        AndroidView(modifier = Modifier.fillMaxSize().systemBarsPadding(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    fun check(u: String?) {
                        if (u != null && u.contains("access_token=")) {
                            val t = Uri.parse(u.replace("#", "?")).getQueryParameter("access_token")
                            if (t != null) { Store.token = t; onToken(t) }
                        }
                    }
                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { check(u); super.onPageStarted(v, u, f) }
                    override fun doUpdateVisitedHistory(v: WebView?, u: String?, r: Boolean) { check(u); super.doUpdateVisitedHistory(v, u, r) }
                }
                loadUrl("https://openapi.baidu.com/oauth/2.0/authorize?response_type=token&client_id=${Store.appKey}&redirect_uri=oob&scope=basic,netdisk&display=mobile")
            }
        })
    } else {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("网盘播放器", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(key, { key = it }, label = { Text("百度网盘开放平台 AppKey") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(tm, { tm = it }, label = { Text("TMDB API Key（可选，用于海报墙）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button({ Store.appKey = key.trim(); Store.tmdb = tm.trim(); web = true }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("登录百度网盘") }
        }
    }
}

fun play(ctx: Context, list: List<Vid>, i: Int) {
    val v = list[i]
    Store.addRecent(Rec(v.id, v.path.substringBeforeLast('/').ifEmpty { "/" }, v.name))
    val l = list.drop(i).take(300)
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
        .putExtra("ids", l.map { it.id }.toLongArray()).putExtra("paths", l.map { it.path }.toTypedArray())
        .putExtra("names", l.map { it.name }.toTypedArray()).putExtra("index", 0))
}

@Composable fun Poster(title: String, modifier: Modifier = Modifier, folder: Boolean = false, star: Boolean = false,
                       frac: Float = 0f, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val poster by produceState<String?>(null, title) {
        if (!folder) value = withContext(Dispatchers.IO) { runCatching { Tmdb.byTitle(title) }.getOrNull() }
    }
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E24)), contentAlignment = Alignment.Center) {
            if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (folder) "📁" else "🎬", fontSize = 34.sp)
            if (star) Text("★", color = Color(0xFFFFC107), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (frac > 0.02f) LinearProgressIndicator(progress = { frac }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable fun Home(logout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ tab = 0 }) { Text("媒体库", fontSize = 18.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
            TextButton({ tab = 1 }) { Text("网盘", fontSize = 18.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
            Spacer(Modifier.weight(1f)); TextButton(logout) { Text("退出") }
        }
        if (tab == 0) LibraryTab() else FilesTab()
    }
}

@Composable fun LibraryTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var vids by remember { mutableStateOf(Lib.load(ctx)) }
    var status by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Group?>(null) }
    val groups = remember(vids) { Lib.group(vids) }
    val g = open
    if (g != null) { ShowView(g) { open = null }; return }
    val recent = remember { Store.recent() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, placeholder = { Text("搜索") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = Store.roots.isNotEmpty(), onClick = {
                scope.launch {
                    status = "扫描中…"
                    try { vids = withContext(Dispatchers.IO) { Lib.scan(ctx, Store.roots) { status = "扫描 $it" } }; status = "共 ${vids.size} 个视频" }
                    catch (e: Exception) { status = e.message ?: "扫描失败" }
                }
            }) { Text("扫描") }
        }
        Text(if (Store.roots.isEmpty()) "还没有媒体库目录：到「网盘」里长按文件夹加入，再回来点扫描" else status,
            fontSize = 12.sp, color = Color(0xFF9A9AA2), modifier = Modifier.padding(12.dp, 4.dp))
        LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (q.isBlank() && recent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("继续观看", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent) { r ->
                            val v = Vid(r.id, (if (r.dir == "/") "" else r.dir) + "/" + r.name, r.name)
                            Poster(Lib.title(v), Modifier.width(110.dp), frac = Store.frac(r.id)) { play(ctx, listOf(v), 0) }
                        }
                    }
                }
            }
            items(groups.filter { q.isBlank() || it.title.contains(q, true) }, key = { it.title }) { x ->
                Poster(x.title, frac = if (x.isShow) 0f else Store.frac(x.vids[0].id)) { if (x.isShow) open = x else play(ctx, x.vids, 0) }
            }
        }
    }
}

@Composable fun ShowView(g: Group, back: () -> Unit) {
    val ctx = LocalContext.current
    val seasons = g.vids.map { Lib.ep(it.name)?.s ?: 0 }.distinct().sorted()
    var s by remember { mutableIntStateOf(seasons.first()) }
    BackHandler(onBack = back)
    val list = g.vids.filter { (Lib.ep(it.name)?.s ?: 0) == s }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("‹ 返回") }
            Text(g.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons) { n -> FilterChip(s == n, { s = n }, label = { Text(if (n == 0) "其他" else "第${n}季") }) }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(list) { i, v ->
                val f = Store.frac(v.id)
                Column(Modifier.fillMaxWidth().clickable { play(ctx, list, i) }.padding(vertical = 10.dp)) {
                    Text(Lib.ep(v.name)?.let { "第${it.e}集" } ?: v.name, fontSize = 16.sp)
                    Text(v.name + if (f > 0.02f) "  · 已看 ${(f * 100).toInt()}%" else "", fontSize = 11.sp, color = Color(0xFF9A9AA2), maxLines = 1)
                }
            }
        }
    }
}

@Composable fun FilesTab() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("/") }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var season by remember { mutableStateOf<Int?>(null) }
    var roots by remember { mutableStateOf(Store.roots) }
    LaunchedEffect(path) {
        season = null; err = ""
        try {
            items = withContext(Dispatchers.IO) {
                Baidu.list(path).filter { it.isDir || Fx.VIDEO.matches(it.name) }
                    .sortedWith(compareByDescending<Item> { it.isDir }.thenBy { it.name })
            }
        } catch (e: Exception) { err = e.message ?: "加载失败" }
    }
    BackHandler(path != "/") { path = path.substringBeforeLast('/').ifEmpty { "/" } }
    val vids = items.filter { !it.isDir }.map { Vid(it.fsId, it.path, it.name) }
    val seasons = vids.mapNotNull { Lib.ep(it.name)?.s }.distinct().sorted()
    val playlist = vids.filter { season == null || Lib.ep(it.name)?.s == season }
    val shown = items.filter { it.isDir || season == null || Lib.ep(it.name)?.s == season }
    Column(Modifier.fillMaxSize()) {
        Text(if (path == "/") "我的网盘（长按文件夹 = 加入/移出媒体库）" else path, fontSize = 13.sp, color = Color(0xFF9A9AA2), modifier = Modifier.padding(12.dp, 2.dp))
        if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(season == null, { season = null }, label = { Text("全部") }) }
            items(seasons) { n -> FilterChip(season == n, { season = n }, label = { Text("第${n}季") }) }
        }
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.fsId }) { f ->
                val toggle: () -> Unit = {
                    roots = if (f.path in roots) roots - f.path else roots + f.path
                    Store.roots = roots
                    Toast.makeText(ctx, if (f.path in roots) "已加入媒体库" else "已移出媒体库", Toast.LENGTH_SHORT).show()
                }
                Poster(if (f.isDir) f.name else Tmdb.clean(f.name).ifEmpty { f.name }, folder = f.isDir, star = f.isDir && f.path in roots,
                    frac = if (f.isDir) 0f else Store.frac(f.fsId), onLong = if (f.isDir) toggle else null) {
                    if (f.isDir) path = f.path else play(ctx, playlist, playlist.indexOfFirst { it.id == f.fsId }.coerceAtLeast(0))
                }
            }
        }
    }
}
