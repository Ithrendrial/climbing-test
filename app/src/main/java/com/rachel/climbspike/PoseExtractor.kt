package com.rachel.climbspike

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val SAMPLE_MS = 200L // 5 fps — enough to see a hesitation, cheap enough to stay interactive
private const val LONG_EDGE = 640
private const val MODEL = "pose_landmarker_full.task" // swap to _heavy for accuracy, _lite for speed

class Extraction(
    val frames: List<Frame>,
    val w: Int,
    val h: Int,
    val sampled: Int,
    val meanConfidence: Float,
    val processSec: Float,
)

suspend fun extractPoses(
    ctx: Context,
    uri: Uri,
    onProgress: (Float) -> Unit,
): Extraction = withContext(Dispatchers.Default) {
    val started = System.currentTimeMillis()
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(ctx, uri)

    fun meta(key: Int) = mmr.extractMetadata(key)?.toIntOrNull() ?: 0
    val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
    // WIDTH/HEIGHT are pre-rotation; getFrameAtTime hands back an upright bitmap.
    val upright = rot == 90 || rot == 270
    val srcW = if (upright) meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) else meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    val srcH = if (upright) meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) else meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    val k = if (maxOf(srcW, srcH) > 0) LONG_EDGE.toFloat() / maxOf(srcW, srcH) else 1f
    val dw = maxOf(1, (srcW * k).toInt())
    val dh = maxOf(1, (srcH * k).toInt())

    // VIDEO mode keeps tracking state between frames, which matters on climbing footage
    // where the climber is often turned away from the camera.
    val landmarker = PoseLandmarker.createFromOptions(
        ctx,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    )

    val frames = mutableListOf<Frame>()
    var sampled = 0
    var confSum = 0f
    var confN = 0
    try {
        var t = 0L
        while (t < durMs) {
            // ponytail: one seek+decode per sampled frame. Simple, but it's the slow part.
            // Swap for MediaCodec decoding to a Surface if long videos become painful.
            val raw = mmr.getScaledFrameAtTime(
                t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, dw, dh
            )
            if (raw != null) {
                sampled++
                val bmp = if (raw.config == Bitmap.Config.ARGB_8888) raw
                          else raw.copy(Bitmap.Config.ARGB_8888, false)
                val res = landmarker.detectForVideo(BitmapImageBuilder(bmp).build(), t)

                val lm = res.landmarks().firstOrNull()
                val wl = res.worldLandmarks().firstOrNull()
                if (lm != null) {
                    lm.forEach { confSum += it.visibility().orElse(0f); confN++ }
                    val visible = lm.indices.filter { lm[it].visibility().orElse(0f) > 0.5f }
                    val pts = visible.associateWith { P(lm[it].x(), lm[it].y()) }
                    val world = wl?.let { w ->
                        visible.filter { it < w.size }.associateWith { P3(w[it].x(), w[it].y(), w[it].z()) }
                    } ?: emptyMap()
                    if (pts.isNotEmpty()) frames += Frame(t, pts, world)
                }
                if (bmp !== raw) bmp.recycle()
                raw.recycle()
            }
            onProgress(if (durMs > 0) t.toFloat() / durMs else 1f)
            t += SAMPLE_MS
        }
    } finally {
        landmarker.close()
        mmr.release()
    }
    Extraction(
        frames = frames,
        w = dw,
        h = dh,
        sampled = sampled,
        meanConfidence = if (confN > 0) confSum / confN else 0f,
        processSec = (System.currentTimeMillis() - started) / 1000f,
    )
}
