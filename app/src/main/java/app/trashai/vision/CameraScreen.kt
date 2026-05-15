package app.trashai.vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    detections: List<Detection>,
    onDetections: (List<Detection>) -> Unit,
    onCaptureBytes: (ByteArray) -> Unit,
    onTapDetection: (Detection) -> Unit,
) {
    val permState = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!permState.status.isGranted) permState.launchPermissionRequest()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permState.status.isGranted) {
            CameraPreviewWithAnalyzer(
                onDetections = onDetections,
                onCaptureBytes = onCaptureBytes,
            )
            DetectionOverlay(detections, Modifier.fillMaxSize(), onTap = onTapDetection)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color(0xCC000000)) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "카메라 권한이 필요합니다",
                            color = Color.White,
                        )
                        Button(
                            onClick = { permState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2D5A27),
                            ),
                        ) { Text("권한 허용") }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreviewWithAnalyzer(
    onDetections: (List<Detection>) -> Unit,
    onCaptureBytes: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    var detector by remember { mutableStateOf<YoloDetector?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var framesAnalyzed by remember { mutableStateOf(0) }
    var lastDetCount by remember { mutableStateOf(0) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) { YoloDetector(context.applicationContext) }
        detector = d
        loadError = if (!d.isReady) d.loadError else null
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
            analyzerExecutor.shutdown()
            captureExecutor.shutdown()
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
            detectorProvider = { detector },
            onDetections = { dets ->
                framesAnalyzed += 1
                lastDetCount = dets.size
                onDetections(dets)
            },
            onAnalyzerError = { msg -> lastError = msg },
            onImageCaptureReady = { imageCapture = it },
            scopeLaunch = { block -> scope.launch { block() } },
            lifecycleOwner = lifecycleOwner,
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        loadError?.let { msg ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                    .background(
                        Color(0xCC8B0000),
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "ONNX 모델 누락\n$msg\n(촬영 버튼은 정상 동작)",
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }

        // Debug HUD — visible diagnostic without needing Logcat
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 16.dp)
                .background(
                    Color(0xCC000000),
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            val modelStatus = when {
                detector == null && loadError == null -> "loading"
                detector != null && loadError == null -> "OK"
                else -> "FAIL"
            }
            val det = detector
            Text(
                buildString {
                    append("model=$modelStatus\n")
                    append("frames=$framesAnalyzed\n")
                    append("dets=$lastDetCount\n")
                    if (det != null) {
                        append("out=[${det.lastOutDim1}x${det.lastOutDim2}]\n")
                        append("top=${"%.3f".format(det.lastTopScore)}\n")
                        append("raw=${det.lastRawCount}")
                    }
                    lastError?.let { append("\nerr=$it") }
                },
                color = Color.White,
                fontSize = 10.sp,
            )
        }

        FloatingActionButton(
            onClick = {
                val ic = imageCapture ?: return@FloatingActionButton
                ic.takePicture(
                    captureExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bytes = image.toJpegBytes()
                                onCaptureBytes(bytes)
                            } finally {
                                image.close()
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraScreen", "capture failed", exception)
                        }
                    },
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(72.dp),
            shape = CircleShape,
            containerColor = Color(0xFF2D5A27),
            contentColor = Color.White,
        ) {
            Text("📷", color = Color.White)
        }
    }
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    analyzerExecutor: java.util.concurrent.Executor,
    detectorProvider: () -> YoloDetector?,
    onDetections: (List<Detection>) -> Unit,
    onAnalyzerError: (String) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit,
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
                            val det = detectorProvider()
                            if (det == null) {
                                onDetections(emptyList())
                            } else {
                                val results = withContext(Dispatchers.Default) {
                                    det.analyze(proxy)
                                }
                                onDetections(results)
                            }
                        } catch (t: Throwable) {
                            val m = "analyze fail: ${t.javaClass.simpleName}: ${t.message}"
                            Log.w("CameraScreen", m)
                            onAnalyzerError(m)
                        } finally {
                            proxy.close()
                        }
                    }
                }
            }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                capture,
            )
            onImageCaptureReady(capture)
        } catch (t: Throwable) {
            Log.e("CameraScreen", "bind fail", t)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun ImageProxy.toJpegBytes(): ByteArray {
    // ImageCapture in JPEG format (default) gives a single-plane buffer that
    // already holds compressed JPEG bytes. We just copy them out.
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

