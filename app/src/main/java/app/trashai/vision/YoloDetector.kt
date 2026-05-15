package app.trashai.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * ONNX Runtime–backed Ultralytics YOLO detector.
 *
 * Required assets:
 *   - app/src/main/assets/yolo11n.onnx       (the model — pre-downloaded)
 *   - app/src/main/assets/labels.txt         (one label per line, in model class order)
 *
 * Output tensor formats supported (auto-detected at load):
 *   - YOLOv8 / v11 legacy : [1, 4 + numClasses, numAnchors]   → needs NMS
 *   - YOLO26 end2end       : [1, 300, 6]                      → no NMS
 */
class YoloDetector(context: Context) : AutoCloseable {

    var loadError: String? = null
        private set

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession? = try {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        Log.i(TAG, "Loading ONNX model: ${bytes.size / 1024} KB")
        val s = env.createSession(bytes, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        })
        Log.i(TAG, "ONNX session ready. inputs=${s.inputNames} outputs=${s.outputNames}")
        s
    } catch (t: Throwable) {
        Log.e(TAG, "ONNX model load failed (${MODEL_ASSET}): ${t.message}", t)
        loadError = "모델 파일이 없습니다: assets/$MODEL_ASSET (${t.javaClass.simpleName})"
        null
    }

    private val labels: List<String> = try {
        context.assets.open(LABELS_ASSET).bufferedReader().useLines { it.map(String::trim).filter(String::isNotEmpty).toList() }
    } catch (t: Throwable) {
        Log.w(TAG, "labels not found: $LABELS_ASSET")
        emptyList()
    }

    val isReady: Boolean get() = session != null

    // Cache shapes once at construction.
    private val inputName: String = session?.inputNames?.firstOrNull() ?: ""
    private val outputName: String = session?.outputNames?.firstOrNull() ?: ""
    private val inputShape: LongArray = session?.inputInfo?.values?.firstOrNull()?.info
        ?.let { (it as? ai.onnxruntime.TensorInfo)?.shape } ?: longArrayOf(1, 3, 640, 640)
    private val outputShape: LongArray = session?.outputInfo?.values?.firstOrNull()?.info
        ?.let { (it as? ai.onnxruntime.TensorInfo)?.shape } ?: longArrayOf(1, 0, 0)
    private val inputH: Int = inputShape.getOrNull(2)?.toInt() ?: 640
    private val inputW: Int = inputShape.getOrNull(3)?.toInt() ?: 640

    private enum class HeadKind { Legacy, End2End }
    /** End2end if shape == [1, 300, 6]; legacy YOLOv8/v11 otherwise. */
    private val headKind: HeadKind =
        if (outputShape.size == 3 && outputShape[2] == 6L) HeadKind.End2End else HeadKind.Legacy

    init {
        Log.i(TAG, "YoloDetector init: input=${inputShape.toList()} output=${outputShape.toList()} head=$headKind labels=${labels.size}")
    }

    private var firstFrameLogged = false
    private var zeroFrameCount = 0

    @Volatile var lastTopScore: Float = -1f
        private set
    @Volatile var lastRawCount: Int = 0
        private set
    @Volatile var lastOutDim1: Int = 0
        private set
    @Volatile var lastOutDim2: Int = 0
        private set

    @SuppressLint("UnsafeOptInUsageError")
    suspend fun analyze(image: ImageProxy): List<Detection> = withContext(Dispatchers.Default) {
        val sess = session ?: return@withContext emptyList()

        val rotation = image.imageInfo.rotationDegrees
        val raw = runCatching { image.toBitmap() }.getOrNull() ?: return@withContext emptyList()
        val rotated = if (rotation == 0) raw else rotateBitmap(raw, rotation)
        val srcW = rotated.width
        val srcH = rotated.height

        val (lb, scale, padX, padY) = letterbox(rotated, inputW, inputH)
        val inputBuf = bitmapToBchwFloatBuffer(lb, inputW, inputH)
        val inputTensor = OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))

        val outArr: Any? = try {
            sess.run(mapOf(inputName to inputTensor)).use { result ->
                result.get(0).value
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX run fail: ${t.message}")
            null
        } finally {
            inputTensor.close()
        }
        outArr ?: return@withContext emptyList()

        // outArr is Array<Array<FloatArray>>: [batch, dim1, dim2]
        @Suppress("UNCHECKED_CAST")
        val out3d = outArr as Array<Array<FloatArray>>
        val plane = out3d[0]

        // Record diagnostics: top class score across all anchors / output dims
        var maxSeen = 0f
        if (headKind == HeadKind.Legacy && plane.size > 4) {
            for (c in 4 until plane.size) {
                val row = plane[c]
                for (a in row.indices) if (row[a] > maxSeen) maxSeen = row[a]
            }
        } else if (headKind == HeadKind.End2End) {
            for (row in plane) if (row.size >= 5 && row[4] > maxSeen) maxSeen = row[4]
        }
        lastTopScore = maxSeen
        lastOutDim1 = plane.size
        lastOutDim2 = plane.getOrNull(0)?.size ?: 0
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(TAG, "First inference output: [${out3d.size}, ${plane.size}, ${plane.getOrNull(0)?.size}] topScore=$maxSeen")
        }

        val raws = when (headKind) {
            HeadKind.Legacy -> parseYoloLegacy(plane, scale, padX, padY, srcW, srcH, SCORE_THRESHOLD)
            HeadKind.End2End -> parseYoloEnd2End(plane, scale, padX, padY, srcW, srcH, SCORE_THRESHOLD)
        }
        lastRawCount = raws.size
        val kept = when (headKind) {
            HeadKind.Legacy -> nms(raws, IOU_THRESHOLD, MAX_DETECTIONS)
            HeadKind.End2End -> raws.take(MAX_DETECTIONS)
        }
        if (raws.isEmpty()) {
            // Avoid log spam: only log every ~30 frames (~1s) when zero detections
            // (Approximation — the analyzer drops frames so this isn't exact.)
            zeroFrameCount++
            if (zeroFrameCount % 30 == 0) Log.d(TAG, "no detections in last ~30 frames")
        } else {
            zeroFrameCount = 0
        }

        kept.map { r ->
            Detection(
                bbox = r.box,
                srcWidth = srcW,
                srcHeight = srcH,
                labels = listOf(LabelScore(labelOf(r.classIdx), r.score)),
            )
        }
    }

    private fun labelOf(classIdx: Int): String =
        labels.getOrNull(classIdx) ?: "class_$classIdx"

    override fun close() {
        session?.close()
    }

    private companion object {
        const val TAG = "YoloDetector"
        const val MODEL_ASSET = "yolo11n.onnx"
        const val LABELS_ASSET = "labels.txt"

        const val SCORE_THRESHOLD = 0.10f
        const val IOU_THRESHOLD = 0.45f
        const val MAX_DETECTIONS = 20
    }
}

// ----------------------- helpers (file-private) -------------------------------

private data class RawDetection(val box: RectF, val score: Float, val classIdx: Int)

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private data class Letterboxed(
    val bitmap: Bitmap,
    val scale: Float,
    val padX: Float,
    val padY: Float,
)

private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): Letterboxed {
    val scale = minOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
    val newW = (src.width * scale).toInt()
    val newH = (src.height * scale).toInt()
    val padX = (dstW - newW) / 2f
    val padY = (dstH - newH) / 2f
    val canvasBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(canvasBmp)
    canvas.drawColor(Color.rgb(114, 114, 114))
    val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
    canvas.drawBitmap(resized, padX, padY, null)
    if (resized !== src) resized.recycle()
    return Letterboxed(canvasBmp, scale, padX, padY)
}

/**
 * Build a planar BCHW float buffer (Channels = R, G, B in that order),
 * normalized to [0, 1]. ONNX Runtime expects [1, 3, H, W].
 */
private fun bitmapToBchwFloatBuffer(bmp: Bitmap, w: Int, h: Int): FloatBuffer {
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val buf = FloatBuffer.allocate(3 * h * w)
    val area = h * w
    // Channel R
    for (i in 0 until area) buf.put(((pixels[i] shr 16) and 0xFF) / 255f)
    // Channel G
    for (i in 0 until area) buf.put(((pixels[i] shr 8) and 0xFF) / 255f)
    // Channel B
    for (i in 0 until area) buf.put((pixels[i] and 0xFF) / 255f)
    buf.rewind()
    return buf
}

/** Legacy YOLOv8/v11 head: [4 + numClasses, numAnchors]. Needs NMS. */
private fun parseYoloLegacy(
    output: Array<FloatArray>,
    scale: Float, padX: Float, padY: Float,
    srcW: Int, srcH: Int, scoreThreshold: Float,
): List<RawDetection> {
    if (output.isEmpty()) return emptyList()
    val numChannels = output.size
    val numAnchors = output[0].size
    val numClasses = numChannels - 4
    if (numClasses <= 0) return emptyList()

    val out = ArrayList<RawDetection>(64)
    for (i in 0 until numAnchors) {
        var maxClass = -1
        var maxScore = 0f
        for (c in 0 until numClasses) {
            val s = output[4 + c][i]
            if (s > maxScore) { maxScore = s; maxClass = c }
        }
        if (maxScore < scoreThreshold || maxClass < 0) continue

        val cx = output[0][i]
        val cy = output[1][i]
        val w = output[2][i]
        val h = output[3][i]
        val x1 = ((cx - w / 2f) - padX) / scale
        val y1 = ((cy - h / 2f) - padY) / scale
        val x2 = ((cx + w / 2f) - padX) / scale
        val y2 = ((cy + h / 2f) - padY) / scale
        val box = RectF(
            x1.coerceIn(0f, srcW.toFloat()),
            y1.coerceIn(0f, srcH.toFloat()),
            x2.coerceIn(0f, srcW.toFloat()),
            y2.coerceIn(0f, srcH.toFloat()),
        )
        if (box.width() < 4f || box.height() < 4f) continue
        out.add(RawDetection(box, maxScore, maxClass))
    }
    return out
}

/** YOLO26 end2end head: [300, 6] per row [x1, y1, x2, y2, score, class_idx]. No NMS needed. */
private fun parseYoloEnd2End(
    output: Array<FloatArray>,
    scale: Float, padX: Float, padY: Float,
    srcW: Int, srcH: Int, scoreThreshold: Float,
): List<RawDetection> {
    val out = ArrayList<RawDetection>()
    for (row in output) {
        if (row.size < 6) continue
        val score = row[4]
        if (score < scoreThreshold) continue
        val classIdx = row[5].toInt()
        val x1 = (row[0] - padX) / scale
        val y1 = (row[1] - padY) / scale
        val x2 = (row[2] - padX) / scale
        val y2 = (row[3] - padY) / scale
        val box = RectF(
            x1.coerceIn(0f, srcW.toFloat()),
            y1.coerceIn(0f, srcH.toFloat()),
            x2.coerceIn(0f, srcW.toFloat()),
            y2.coerceIn(0f, srcH.toFloat()),
        )
        if (box.width() < 4f || box.height() < 4f) continue
        out.add(RawDetection(box, score, classIdx))
    }
    out.sortByDescending { it.score }
    return out
}

private fun nms(raws: List<RawDetection>, iouThreshold: Float, maxDetections: Int): List<RawDetection> {
    if (raws.isEmpty()) return emptyList()
    val sorted = raws.sortedByDescending { it.score }.toMutableList()
    val kept = ArrayList<RawDetection>()
    while (sorted.isNotEmpty() && kept.size < maxDetections) {
        val best = sorted.removeAt(0)
        kept.add(best)
        val iter = sorted.iterator()
        while (iter.hasNext()) {
            val cand = iter.next()
            if (iou(best.box, cand.box) > iouThreshold) iter.remove()
        }
    }
    return kept
}

private fun iou(a: RectF, b: RectF): Float {
    val ix1 = maxOf(a.left, b.left)
    val iy1 = maxOf(a.top, b.top)
    val ix2 = minOf(a.right, b.right)
    val iy2 = minOf(a.bottom, b.bottom)
    val iw = (ix2 - ix1).coerceAtLeast(0f)
    val ih = (iy2 - iy1).coerceAtLeast(0f)
    val inter = iw * ih
    val ua = a.width() * a.height() + b.width() * b.height() - inter
    return if (ua <= 0f) 0f else inter / ua
}
