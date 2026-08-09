package com.rachel.climbspike

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** ML Kit PoseLandmark type ids, redeclared so this file stays JVM-testable. */
object L {
    const val LSH = 11; const val RSH = 12
    const val LEL = 13; const val REL = 14
    const val LWR = 15; const val RWR = 16
    const val LHIP = 23; const val RHIP = 24
    const val LKNEE = 25; const val RKNEE = 26
    const val LANK = 27; const val RANK = 28
}

val BONES = listOf(
    L.LSH to L.RSH, L.LHIP to L.RHIP, L.LSH to L.LHIP, L.RSH to L.RHIP,
    L.LSH to L.LEL, L.LEL to L.LWR, L.RSH to L.REL, L.REL to L.RWR,
    L.LHIP to L.LKNEE, L.LKNEE to L.LANK, L.RHIP to L.RKNEE, L.RKNEE to L.RANK,
)

/** Normalised image coordinates, 0..1 of the frame. */
@Serializable
data class P(val x: Float, val y: Float)

/** MediaPipe world landmark: metres, origin at the hip centre, y pointing down. */
@Serializable
data class P3(val x: Float, val y: Float, val z: Float)

/**
 * [pts] is where the body is *in the frame* — the only thing that can show the climber
 * moving up the wall. [world] is the body's shape in metres, hip-centred, so it says
 * nothing about travel but gives limb positions that need no per-video normalisation.
 */
@Serializable
data class Frame(val tMs: Long, val pts: Map<Int, P>, val world: Map<Int, P3> = emptyMap())

@Serializable
data class Segment(val startSec: Float, val endSec: Float, val kind: String)

@Serializable
data class Attempt(
    val id: String,
    val route: String,
    val grade: String,
    val dateMs: Long,
    val durationSec: Float,
    val segments: List<Segment>,
    val techniques: List<String>,
    val hesitationSec: Float,
    val hesitationCount: Int,
    val cruxStartSec: Float,
    val cruxEndSec: Float,
    val heightGained: Float,
    val vw: Int,
    val vh: Int,
    // Tracking quality — is the pose model actually coping with this footage?
    val sampledFrames: Int = 0,
    val coreFrames: Int = 0,
    val meanConfidence: Float = 0f,
    val processSec: Float = 0f,
    // ponytail: frames are stored so the skeleton scrubber survives a reload and so
    // thresholds can be re-tuned without re-decoding video. ~100KB/attempt. Move to a
    // separate file per attempt (or Room) once the log passes a few dozen entries.
    val frames: List<Frame>,
)

// All speeds are in torso-lengths per second, so they compare across videos,
// camera distances and climbers. Tuned by eye on phone-held gym footage.
const val HESITATE_SPEED = 0.25f
const val HESITATE_MIN_SEC = 1.0f
const val DYNO_SPEED = 2.0f

// World-landmark thresholds are real distances in metres from the hip centre.
const val HIGH_STEP_M = 0.05f

private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

private fun Frame.mid(a: Int, b: Int): P? {
    val pa = pts[a]
    val pb = pts[b]
    return when {
        pa != null && pb != null -> P((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
        else -> pa ?: pb
    }
}

fun Frame.com(): P? = mid(L.LHIP, L.RHIP)

/**
 * Shoulder-to-hip distance, the yardstick every speed is measured against.
 *
 * Landmarks are normalised to 0..1 of the frame, so a real torso is a few percent of
 * frame height — the guard only exists to reject a collapsed detection, and must stay
 * well below any plausible torso or [analyse] silently falls back to scale = 1 and reads
 * the whole climb as one long stall.
 */
fun Frame.torso(): Float? {
    val s = mid(L.LSH, L.RSH) ?: return null
    val h = mid(L.LHIP, L.RHIP) ?: return null
    return dist(s, h).takeIf { it > 0.01f }
}

private fun smooth(v: FloatArray, radius: Int): FloatArray {
    if (v.isEmpty()) return v
    return FloatArray(v.size) { i ->
        val lo = maxOf(0, i - radius)
        val hi = minOf(v.size - 1, i + radius)
        var sum = 0f
        for (j in lo..hi) sum += v[j]
        sum / (hi - lo + 1)
    }
}

/** Mean per-frame speed of wrists and ankles — high while searching for a hold. */
private fun limbActivity(frames: List<Frame>, scale: Float): FloatArray {
    val out = FloatArray(frames.size)
    val limbs = intArrayOf(L.LWR, L.RWR, L.LANK, L.RANK)
    for (i in 1 until frames.size) {
        val dt = (frames[i].tMs - frames[i - 1].tMs) / 1000f
        if (dt <= 0f) continue
        var sum = 0f
        var n = 0
        for (id in limbs) {
            val a = frames[i - 1].pts[id] ?: continue
            val b = frames[i].pts[id] ?: continue
            sum += dist(a, b) / scale / dt
            n++
        }
        out[i] = if (n > 0) sum / n else 0f
    }
    return out
}

/** Contiguous index runs where [flag] holds. */
private fun runsOf(n: Int, flag: (Int) -> Boolean): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    for (i in 0 until n) {
        if (flag(i)) {
            if (start < 0) start = i
        } else if (start >= 0) {
            out += start until i
            start = -1
        }
    }
    if (start >= 0) out += start until n
    return out
}

fun analyse(
    frames: List<Frame>,
    route: String,
    grade: String,
    now: Long,
    vw: Int,
    vh: Int,
    id: String = now.toString(),
    sampledFrames: Int = frames.size,
    meanConfidence: Float = 0f,
    processSec: Float = 0f,
): Attempt {
    val n = frames.size
    val durSec = if (n > 0) frames.last().tMs / 1000f else 0f
    val sec = { i: Int -> frames[i].tMs / 1000f }

    // Median torso length — robust to the odd garbage detection.
    val scale = frames.mapNotNull { it.torso() }.sorted()
        .let { if (it.isEmpty()) 1f else it[it.size / 2] }

    val coms = frames.map { it.com() }
    val raw = FloatArray(n)
    for (i in 1 until n) {
        val a = coms[i - 1]
        val b = coms[i]
        val dt = (frames[i].tMs - frames[i - 1].tMs) / 1000f
        raw[i] = if (a != null && b != null && dt > 0f) dist(a, b) / scale / dt else 0f
    }
    if (n > 1) raw[0] = raw[1] // frame 0 has no predecessor; don't read it as a stall
    val speed = smooth(raw, 1)
    val limb = limbActivity(frames, scale)

    val slow = runsOf(n) { speed[it] < HESITATE_SPEED && coms[it] != null }
        .filter { sec(it.last) - sec(it.first) >= HESITATE_MIN_SEC }

    // Crux = the pause where the climber is most stuck: long, and busy with the
    // limbs while the body itself is going nowhere (searching for the next hold).
    val crux = slow.maxByOrNull { r ->
        val dur = sec(r.last) - sec(r.first)
        val busy = (r.first..r.last).map { limb[it] }.average().toFloat()
        dur * (1f + busy)
    }

    val segments = buildList {
        var cursor = 0
        for (r in slow) {
            if (r.first > cursor) add(Segment(sec(cursor), sec(r.first), "MOVE"))
            add(Segment(sec(r.first), sec(r.last), if (r == crux) "CRUX" else "HESITATE"))
            cursor = r.last
        }
        if (n > 0 && cursor < n - 1) add(Segment(sec(cursor), sec(n - 1), "MOVE"))
    }

    val techniques = mutableListOf<String>()

    // A dyno is the whole body travelling, which only image space can see — world
    // landmarks are hip-centred, so a leaping climber looks stationary in them.
    if ((speed.maxOrNull() ?: 0f) > DYNO_SPEED) techniques += "DYNAMIC / DYNO"

    // Posture, on the other hand, is exactly what world landmarks are for: metres from
    // the hip centre, so these thresholds are physical and need no normalisation.
    val highStep = frames.any { f ->
        listOf(L.LKNEE, L.RKNEE).any { (f.world[it]?.y ?: 0f) < -HIGH_STEP_M }
    }
    if (highStep) techniques += "HIGH STEP"

    val mantle = frames.indices.any { i ->
        val pressing = listOf(L.LWR, L.RWR).any { (frames[i].world[it]?.y ?: -1f) > 0f }
        val rising = i > 2 && coms[i] != null && coms[i - 3] != null &&
            coms[i]!!.y < coms[i - 3]!!.y - 0.05f * scale
        pressing && rising
    }
    if (mantle) techniques += "MANTLE / PRESS"
    if (techniques.isEmpty()) techniques += "STATIC / CONTROLLED"

    val ys = coms.filterNotNull().map { it.y }
    val gained = if (ys.isEmpty()) 0f else abs(ys.first() - ys.min()) / scale

    return Attempt(
        id = id,
        route = route,
        grade = grade,
        dateMs = now,
        durationSec = durSec,
        segments = segments,
        techniques = techniques,
        hesitationSec = slow.sumOf { (sec(it.last) - sec(it.first)).toDouble() }.toFloat(),
        hesitationCount = slow.size,
        cruxStartSec = crux?.let { sec(it.first) } ?: 0f,
        cruxEndSec = crux?.let { sec(it.last) } ?: 0f,
        heightGained = gained,
        vw = vw,
        vh = vh,
        sampledFrames = sampledFrames,
        coreFrames = frames.count { it.torso() != null },
        meanConfidence = meanConfidence,
        processSec = processSec,
        frames = frames,
    )
}

private val json = Json { ignoreUnknownKeys = true }
private val logSerializer = ListSerializer(Attempt.serializer())

fun loadLog(f: File): List<Attempt> =
    if (!f.exists()) emptyList()
    else runCatching { json.decodeFromString(logSerializer, f.readText()) }.getOrDefault(emptyList())

fun saveLog(f: File, list: List<Attempt>) = f.writeText(json.encodeToString(logSerializer, list))
