package com.example.panplayer
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.Request
import org.videolan.libvlc.*
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class PlayerActivity : ComponentActivity() {
    private lateinit var vlc: LibVLC
    private lateinit var mp: MediaPlayer
    private var ids = longArrayOf(); private var paths = arrayOf<String>(); private var names = arrayOf<String>()
    private val idx = mutableIntStateOf(0)
    private val pos = mutableLongStateOf(0L); private val len = mutableLongStateOf(0L)
    private val playing = mutableStateOf(false); private val rate = mutableFloatStateOf(1f)
    private val tip = mutableStateOf("")
    private val aud = mutableStateOf<List<Pair<Int, String>>>(emptyList())
    private val spu = mutableStateOf<List<Pair<Int, String>>>(emptyList())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this)
        ids = intent.getLongArrayExtra("ids") ?: return finish()
        paths = intent.getStringArrayExtra("paths") ?: return finish()
        names = intent.getStringArrayExtra("names") ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        vlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mp = MediaPlayer(vlc)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.TimeChanged -> { pos.longValue = e.timeChanged }
                MediaPlayer.Event.LengthChanged -> { len.longValue = e.lengthChanged }
                MediaPlayer.Event.Playing -> { playing.value = true; refresh() }
                MediaPlayer.Event.Paused -> { playing.value = false }
                MediaPlayer.Event.ESAdded -> { refresh() }
                MediaPlayer.Event.EndReached -> { save(true); if (idx.intValue + 1 < ids.size) start(idx.intValue + 1) else finish() }
                MediaPlayer.Event.EncounteredError -> { Toast.makeText(this, "播放出错", Toast.LENGTH_LONG).show() }
                else -> {}
            }
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) { PlayerUi() } } }
        start(intent.getIntExtra("index", 0))
        lifecycleScope.launch { while (true) { delay(5000); save(false) } }
    }

    private fun refresh() {
        aud.value = mp.audioTracks?.map { it.id to it.name } ?: emptyList()
        spu.value = mp.spuTracks?.map { it.id to it.name } ?: emptyList()
    }

    private fun save(done: Boolean) {
        val id = ids.getOrNull(idx.intValue) ?: return
        val l = len.longValue; if (l <= 0) return
        val f = pos.longValue.toFloat() / l
        val fin = done || f > 0.95f
        Store.setFrac(id, if (fin) 0f else f); Store.setPos(id, if (fin) 0 else pos.longValue)
    }

    private fun start(i: Int) {
        if (::mp.isInitialized && len.longValue > 0) save(false)
        idx.intValue = i; pos.longValue = 0; len.longValue = 0
        lifecycleScope.launch {
            try {
                val (url, subs) = withContext(Dispatchers.IO) {
                    val u = Baidu.dlinks(listOf(ids[i]))[ids[i]] ?: error("获取播放链接失败（令牌过期或被限制）")
                    val dir = paths[i].substringBeforeLast('/').ifEmpty { "/" }
                    val stem = names[i].substringBeforeLast('.')
                    val ss = Baidu.list(dir).filter { Fx.SUB.matches(it.name) && it.name.startsWith(stem) }
                    val dl = Baidu.dlinks(ss.map { it.fsId })
                    u to ss.mapNotNull { s -> dl[s.fsId]?.let { d -> runCatching {
                        val f = File(cacheDir, "sub_${s.fsId}.${s.name.substringAfterLast('.')}")
                        Baidu.client.newCall(Request.Builder().url(d).header("User-Agent", Baidu.UA).build()).execute().use { f.writeBytes(it.body!!.bytes()) }
                        f }.getOrNull() } }
                }
                val m = Media(vlc, Uri.parse(url))
                m.addOption(":http-user-agent=${Baidu.UA}"); m.addOption(":network-caching=4000"); m.addOption(":http-reconnect")
                val p = Store.pos(ids[i]); if (p > 5000) m.addOption(":start-time=${p / 1000.0}")
                mp.media = m; m.release(); mp.play(); mp.setRate(rate.floatValue)
                subs.forEachIndexed { k, f -> mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), k == 0) }
            } catch (e: Exception) { Toast.makeText(this@PlayerActivity, e.message ?: "加载失败", Toast.LENGTH_LONG).show() }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    @Composable private fun <T> Pick(label: String, opts: List<Pair<T, String>>, on: (T) -> Unit) {
        var o by remember { mutableStateOf(false) }
        Box {
            TextButton({ o = true }) { Text(label) }
            DropdownMenu(o, { o = false }) { opts.forEach { (k, n) -> DropdownMenuItem(text = { Text(n) }, onClick = { o = false; on(k) }) } }
        }
    }

    @Composable private fun PlayerUi() {
        var ui by remember { mutableStateOf(true) }
        var boost by remember { mutableStateOf(false) }
        var drag by remember { mutableStateOf<Float?>(null) }
        val am = remember { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        LaunchedEffect(tip.value) { if (tip.value.isNotEmpty()) { delay(900); tip.value = "" } }
        LaunchedEffect(ui, playing.value) { if (ui && playing.value) { delay(4000); ui = false } }
        Box(Modifier.fillMaxSize()) {
            AndroidView({ c -> VLCVideoLayout(c).also { mp.attachViews(it, null, true, false) } }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { ui = !ui },
                        onDoubleTap = { if (mp.isPlaying) mp.pause() else mp.play() },
                        onLongPress = { boost = true; mp.setRate(2f); tip.value = "2× 倍速" },
                        onPress = { tryAwaitRelease(); if (boost) { boost = false; mp.setRate(rate.floatValue) } })
                }
                .pointerInput(Unit) {
                    var mode = 0; var sx = 0f; var dx = 0f; var start = 0L; var tgt = 0L; var vf = 0f
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; sx = o.x; dx = 0f; start = mp.time; tgt = start; vf = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                        onDragEnd = { if (mode == 3) mp.setTime(tgt) },
                        onDrag = { ch, d ->
                            ch.consume()
                            if (mode == 0) mode = if (Math.abs(d.x) > Math.abs(d.y)) 3 else if (sx < size.width / 2) 1 else 2
                            when (mode) {
                                3 -> { dx += d.x; tgt = (start + dx / size.width * 120000).toLong().coerceIn(0L, len.longValue); tip.value = "${fmt(tgt)} / ${fmt(len.longValue)}" }
                                1 -> {
                                    val a = window.attributes; val cur = if (a.screenBrightness < 0) 0.5f else a.screenBrightness
                                    a.screenBrightness = (cur - d.y / size.height).coerceIn(0.02f, 1f); window.attributes = a
                                    tip.value = "亮度 ${(a.screenBrightness * 100).toInt()}%"
                                }
                                else -> {
                                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    vf = (vf - d.y / size.height * mx * 1.5f).coerceIn(0f, mx.toFloat())
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, vf.toInt(), 0); tip.value = "音量 ${vf.toInt() * 100 / mx}%"
                                }
                            }
                        })
                })
            if (tip.value.isNotEmpty()) Text(tip.value, Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp), fontSize = 20.sp)
            if (ui) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x66000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ finish() }) { Text("‹ 返回") }
                    Text(names.getOrElse(idx.intValue) { "" }, maxLines = 1, modifier = Modifier.weight(1f), fontSize = 14.sp)
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val mx = maxOf(1f, len.longValue.toFloat())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(drag?.toLong() ?: pos.longValue), fontSize = 12.sp)
                        Slider(value = (drag ?: pos.longValue.toFloat()).coerceIn(0f, mx), onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { mp.setTime(it.toLong()) }; drag = null },
                            valueRange = 0f..mx, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text(fmt(len.longValue), fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ if (idx.intValue > 0) start(idx.intValue - 1) }) { Text("⏮") }
                        TextButton({ if (mp.isPlaying) mp.pause() else mp.play() }) { Text(if (playing.value) "⏸" else "▶") }
                        TextButton({ if (idx.intValue + 1 < ids.size) start(idx.intValue + 1) }) { Text("⏭") }
                        Spacer(Modifier.weight(1f))
                        Pick("倍速 ${rate.floatValue}×", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).map { it to "${it}×" }) { rate.floatValue = it; mp.setRate(it) }
                        Pick("音轨", aud.value) { mp.setAudioTrack(it) }
                        Pick("字幕", spu.value) { mp.setSpuTrack(it) }
                    }
                }
            }
        }
    }

    override fun onPause() { if (::mp.isInitialized) { save(false); mp.pause() }; super.onPause() }
    override fun onDestroy() {
        if (::mp.isInitialized) { mp.stop(); mp.detachViews(); mp.release(); vlc.release() }
        super.onDestroy()
    }
}
