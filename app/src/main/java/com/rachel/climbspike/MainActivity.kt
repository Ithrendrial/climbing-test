package com.rachel.climbspike

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MOVE = Color(0xFF90A4AE)
private val HESITATE = Color(0xFFF9A825)
private val CRUX = Color(0xFFD32F2F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val logFile = remember { File(ctx.filesDir, "attempts.json") }
    var log by remember { mutableStateOf(loadLog(logFile)) }
    var viewing by remember { mutableStateOf<Attempt?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> pending = uri }

    pending?.let { uri ->
        RouteDialog(onCancel = { pending = null }) { route, grade ->
            pending = null
            progress = 0f
            scope.launch {
                runCatching {
                    val ex = extractPoses(ctx, uri) { progress = it }
                    check(ex.frames.isNotEmpty()) { "No person detected in any frame of that video." }
                    analyse(
                        ex.frames, route, grade, System.currentTimeMillis(), ex.w, ex.h,
                        sampledFrames = ex.sampled,
                        meanConfidence = ex.meanConfidence,
                        processSec = ex.processSec,
                    )
                }.onSuccess {
                    log = log + it
                    saveLog(logFile, log)
                    viewing = it
                }.onFailure {
                    error = it.message ?: "Could not read that video."
                }
                progress = null
            }
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Analysis failed") },
            text = { Text(msg) },
            confirmButton = { TextButton({ error = null }) { Text("OK") } },
        )
    }

    when {
        progress != null -> Analyzing(progress!!)
        viewing != null -> ResultScreen(viewing!!, log) { viewing = null }
        else -> LogScreen(
            log = log,
            onPick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) },
            onOpen = { viewing = it },
        )
    }
}

@Composable
private fun RouteDialog(onCancel: () -> Unit, onGo: (String, String) -> Unit) {
    var route by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Log this attempt") },
        text = {
            Column {
                OutlinedTextField(route, { route = it }, label = { Text("Route") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(grade, { grade = it }, label = { Text("Grade") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ onGo(route.ifBlank { "Untitled" }, grade.ifBlank { "?" }) }) { Text("Analyse") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Analyzing(p: Float) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Text("Tracking climber…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${(p * 100).toInt()}%")
    }
}

@Composable
private fun LogScreen(log: List<Attempt>, onPick: () -> Unit, onOpen: (Attempt) -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Training log", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Button(onPick, Modifier.fillMaxWidth()) { Text("Upload climb video") }
        Spacer(Modifier.height(16.dp))
        if (log.isEmpty()) {
            Text("No attempts yet.", color = Color.Gray)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(log.sortedByDescending { it.dateMs }) { a ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(a) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${a.route}  ·  ${a.grade}", fontWeight = FontWeight.Bold)
                        Text(fmt.format(Date(a.dateMs)), color = Color.Gray)
                        Spacer(Modifier.height(6.dp))
                        Timeline(a)
                        Spacer(Modifier.height(6.dp))
                        Text("%.1fs  ·  %d hesitations  ·  %.1fs stalled".format(a.durationSec, a.hesitationCount, a.hesitationSec))
                    }
                }
            }
        }
    }
}

@Composable
private fun Timeline(a: Attempt) {
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val total = a.durationSec.coerceAtLeast(0.01f)
        drawRect(MOVE.copy(alpha = 0.3f), Offset.Zero, size)
        a.segments.forEach { s ->
            val x0 = s.startSec / total * size.width
            val x1 = s.endSec / total * size.width
            val c = when (s.kind) {
                "CRUX" -> CRUX
                "HESITATE" -> HESITATE
                else -> MOVE
            }
            drawRect(c, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height))
        }
    }
}

@Composable
private fun ResultScreen(a: Attempt, log: List<Attempt>, onBack: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onBack) { Text("← Log") }
        Text("${a.route}  ·  ${a.grade}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Text("Phases", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Timeline(a)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(MOVE, "moving"); Legend(HESITATE, "hesitation"); Legend(CRUX, "crux")
        }
        Spacer(Modifier.height(16.dp))

        Stat("Duration", "%.1f s".format(a.durationSec))
        Stat("Hesitations", "${a.hesitationCount}  (%.1f s total)".format(a.hesitationSec))
        Stat("Crux", "%.1f s → %.1f s".format(a.cruxStartSec, a.cruxEndSec))
        Stat("Height gained", "%.1f torso-lengths".format(a.heightGained))
        Spacer(Modifier.height(16.dp))

        Text("Techniques", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            a.techniques.forEach { AssistChip({}, { Text(it) }) }
        }
        Spacer(Modifier.height(16.dp))

        Text("Tracking quality", fontWeight = FontWeight.Bold)
        val sampled = a.sampledFrames.coerceAtLeast(1)
        Stat("Pose found", "%d / %d frames (%.0f%%)".format(a.frames.size, a.sampledFrames, 100f * a.frames.size / sampled))
        Stat("Torso resolvable", "%.0f%%".format(100f * a.coreFrames / sampled))
        Stat("Mean confidence", "%.2f".format(a.meanConfidence))
        Stat(
            "Speed",
            "%.0fs for %.0fs video (%.1f×)".format(
                a.processSec, a.durationSec, a.durationSec / a.processSec.coerceAtLeast(0.01f)
            ),
        )
        Spacer(Modifier.height(16.dp))

        Text("Tracked pose", fontWeight = FontWeight.Bold)
        Skeleton(a)
        Spacer(Modifier.height(16.dp))

        val prev = log.filter { it.route == a.route && it.id != a.id }.sortedByDescending { it.dateMs }
        Text("Previous attempts on this route", fontWeight = FontWeight.Bold)
        if (prev.isEmpty()) {
            Text("None yet — climb it again to compare.", color = Color.Gray)
        } else {
            prev.forEach { p ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
                    Text(fmt.format(Date(p.dateMs)))
                    Text("%.1fs".format(p.durationSec))
                    Text("%.1fs stalled".format(p.hesitationSec))
                    Delta(a.hesitationSec - p.hesitationSec)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Less stalling than the compared attempt is an improvement, so negative is green. */
@Composable
private fun Delta(diff: Float) = Text(
    if (diff <= 0) "▼ %.1fs".format(-diff) else "▲ %.1fs".format(diff),
    color = if (diff <= 0) Color(0xFF2E7D32) else CRUX,
    fontWeight = FontWeight.Bold,
)

@Composable
private fun Legend(c: Color, label: String) = Text("● $label", color = c)

@Composable
private fun Stat(label: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    Arrangement.SpaceBetween,
) {
    Text(label, color = Color.Gray)
    Text(value, fontWeight = FontWeight.Bold)
}

@Composable
private fun Skeleton(a: Attempt) {
    if (a.frames.isEmpty()) return
    var i by remember(a.id) { mutableStateOf(0) }
    val idx = i.coerceIn(0, a.frames.lastIndex)
    val f = a.frames[idx]
    Canvas(Modifier.fillMaxWidth().height(280.dp)) {
        // Landmarks are normalised 0..1 of the frame; letterbox them to keep the aspect ratio.
        val aspect = if (a.vh > 0) a.vw.toFloat() / a.vh else 1f
        val boxW = minOf(size.width, size.height * aspect)
        val boxH = if (aspect > 0f) boxW / aspect else size.height
        val ox = (size.width - boxW) / 2f
        val oy = (size.height - boxH) / 2f
        fun map(p: P) = Offset(ox + p.x * boxW, oy + p.y * boxH)
        BONES.forEach { (u, v) ->
            val p1 = f.pts[u] ?: return@forEach
            val p2 = f.pts[v] ?: return@forEach
            drawLine(Color(0xFF2E7D32), map(p1), map(p2), strokeWidth = 6f)
        }
        f.pts.values.forEach { drawCircle(Color(0xFF1B5E20), 7f, map(it)) }
    }
    val inCrux = f.tMs / 1000f in a.cruxStartSec..a.cruxEndSec
    Text(
        "t = %.1f s".format(f.tMs / 1000f) + if (inCrux) "   ← CRUX" else "",
        color = if (inCrux) CRUX else Color.Unspecified,
        fontWeight = if (inCrux) FontWeight.Bold else FontWeight.Normal,
    )
    Slider(
        idx.toFloat(),
        { i = it.toInt() },
        valueRange = 0f..a.frames.lastIndex.toFloat().coerceAtLeast(0.001f),
    )
}
