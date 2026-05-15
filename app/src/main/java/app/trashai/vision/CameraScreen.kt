package app.trashai.vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Live camera with auto-detected boxes (ML Kit, generic — no labels) plus a
 * drag-to-draw fallback. Tapping a box, dragging a custom region, or pressing
 * "전체 화면 분석" all funnel into [onCaptureBytes] which the parent sends
 * to Gemini.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onCaptureBytes: (ByteArray) -> Unit,
    capturedJpeg: ByteArray? = null,
) {
    val permState = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!permState.status.isGranted) permState.launchPermissionRequest()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (capturedJpeg != null) {
            // Result mode — show the still image the user captured/tapped.
            CapturedImageView(capturedJpeg)
        } else if (permState.status.isGranted) {
            CameraWithLens(onCaptureBytes = onCaptureBytes)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color(0xCC000000)) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("카메라 권한이 필요합니다", color = Color.White)
                        Button(
                            onClick = { permState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
                        ) { Text("권한 허용") }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraWithLens(onCaptureBytes: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { MlKitDetector() }
    var latestResult by remember { mutableStateOf<DetectionResult?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            detector.close()
            analyzerExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(previewView, lifecycleOwner) {
        bindCamera(
            context = context,
            previewView = previewView,
            analyzerExecutor = analyzerExecutor,
            detector = detector,
            onDetectionResult = { result -> latestResult = result },
            scopeLaunch = { block -> scope.launch { block() } },
            lifecycleOwner = lifecycleOwner,
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        // Single overlay handling box-tap AND drag-to-draw in one gesture loop
        GestureOverlay(
            detections = latestResult?.detections.orEmpty(),
            onBoxTap = { detection ->
                val src = latestResult?.sourceBitmap ?: return@GestureOverlay
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        cropToJpeg(src, detection.bbox)
                    }
                    if (bytes != null) onCaptureBytes(bytes)
                }
            },
            onRegionConfirmed = { rect, canvasSize ->
                val src = latestResult?.sourceBitmap ?: return@GestureOverlay
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        cropFromScreenRect(src, rect, canvasSize)
                    }
                    if (bytes != null) onCaptureBytes(bytes)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Hint pill
        val detCount = latestResult?.detections?.size ?: 0
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (detCount > 0)
                    "박스를 탭하면 분석합니다 ($detCount 개) · 빈 공간은 드래그로 직접 영역 지정"
                else
                    "사물을 비추거나 손가락으로 영역을 드래그하세요",
                color = Color.White,
                fontSize = 11.sp,
            )
        }

        // Full-frame analyze fallback
        Button(
            onClick = {
                val src = latestResult?.sourceBitmap ?: return@Button
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) { bitmapToJpeg(src) }
                    if (bytes != null) onCaptureBytes(bytes)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D5A27)),
        ) {
            Text("전체 화면 분석")
        }
    }
}

@Composable
private fun CapturedImageView(jpeg: ByteArray) {
    val bmp = remember(jpeg) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "분석 대상 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } else {
            Text("이미지 표시 실패", color = Color.White)
        }
    }
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    analyzerExecutor: java.util.concurrent.Executor,
    detector: MlKitDetector,
    onDetectionResult: (DetectionResult) -> Unit,
    scopeLaunch: (suspend () -> Unit) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val provider = providerFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analyzerExecutor) { proxy ->
                    scopeLaunch {
                        try {
                            val result = withContext(Dispatchers.Default) {
                                detector.analyze(proxy)
                            }
                            if (result != null) onDetectionResult(result)
                        } catch (t: Throwable) {
                            Log.w("CameraScreen", "analyze fail: ${t.message}")
                        } finally {
                            proxy.close()
                        }
                    }
                }
            }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        } catch (t: Throwable) {
            Log.e("CameraScreen", "bind fail", t)
        }
    }, ContextCompat.getMainExecutor(context))
}

// --- crop helpers (run on Default/IO) ---------------------------------------

private fun cropToJpeg(src: Bitmap, bbox: android.graphics.RectF): ByteArray? {
    val left = bbox.left.toInt().coerceIn(0, src.width - 1)
    val top = bbox.top.toInt().coerceIn(0, src.height - 1)
    val right = bbox.right.toInt().coerceIn(left + 1, src.width)
    val bottom = bbox.bottom.toInt().coerceIn(top + 1, src.height)
    val w = right - left; val h = bottom - top
    if (w < 16 || h < 16) return bitmapToJpeg(src)
    val cropped = Bitmap.createBitmap(src, left, top, w, h)
    return bitmapToJpeg(cropped)
}

private fun cropFromScreenRect(src: Bitmap, screenRect: Rect, canvasSize: IntSize): ByteArray? {
    val imgW = src.width
    val imgH = src.height
    val scrW = canvasSize.width.toFloat()
    val scrH = canvasSize.height.toFloat()
    val scale = maxOf(scrW / imgW, scrH / imgH)
    val offX = (scrW - imgW * scale) / 2f
    val offY = (scrH - imgH * scale) / 2f
    val left = ((screenRect.left - offX) / scale).toInt().coerceIn(0, imgW - 1)
    val top = ((screenRect.top - offY) / scale).toInt().coerceIn(0, imgH - 1)
    val right = ((screenRect.right - offX) / scale).toInt().coerceIn(left + 1, imgW)
    val bottom = ((screenRect.bottom - offY) / scale).toInt().coerceIn(top + 1, imgH)
    val w = right - left; val h = bottom - top
    if (w < 16 || h < 16) return bitmapToJpeg(src)
    val cropped = Bitmap.createBitmap(src, left, top, w, h)
    return bitmapToJpeg(cropped)
}

private fun bitmapToJpeg(src: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    val scaled = if (src.width > 800 || src.height > 800) {
        val scale = 800f / maxOf(src.width, src.height)
        Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    } else src
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}
