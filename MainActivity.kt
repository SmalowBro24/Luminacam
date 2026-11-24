package com.luminacam.pro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuminaCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0C)
                ) {
                    CameraPermissionWrapper()
                }
            }
        }
    }
}

@Composable
fun LuminaCamTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFFFFB800), // Leica / Pro Amber Accent
        onPrimary = Color.Black,
        surface = Color(0xFF121216),
        background = Color(0xFF0A0A0C),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermissionWrapper() {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        LuminaCameraScreen()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "LuminaCam Pro",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Для съёмки фото и видео в высоком разрешении предоставьте доступ к камере и микрофону.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionsState.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Разрешить доступ", color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

enum class CaptureMode(val title: String) {
    PHOTO("ФОТО"),
    VIDEO("ВИДЕО"),
    PRO("PRO")
}

@Composable
fun LuminaCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var showGrid by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableFloatStateOf(1.0f) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(-4..4) }

    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableIntStateOf(0) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }

    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var showFlashOverlay by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        VideoCapture.withOutput(recorder)
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSec = 0
            while (isRecording) {
                delay(1000L)
                recordingDurationSec++
            }
        }
    }

    LaunchedEffect(lensFacing, captureMode) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = if (captureMode == CaptureMode.VIDEO) {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                }
                camera?.cameraInfo?.exposureState?.let { state ->
                    if (state.isExposureCompensationSupported) {
                        exposureRange = state.exposureCompensationRange.lower..state.exposureCompensationRange.upper
                        exposureIndex = state.exposureCompensationIndex
                    }
                }
                zoomRatio = 1.0f
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = flashMode
        camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON && captureMode == CaptureMode.VIDEO)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1400L)
            focusPoint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
    ) {
        // Camera Viewport with Rounded Corners
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 140.dp)
                .clip(RoundedCornerShape(28.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8f
                        val newZoom = (zoomRatio * zoom).coerceIn(minZoom, maxZoom)
                        zoomRatio = newZoom
                        camera?.cameraControl?.setZoomRatio(newZoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                    }
                }
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Rule of Thirds Grid
            if (showGrid) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lineColor = Color.White.copy(alpha = 0.22f)
                    val stroke = 1.dp.toPx()
                    drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                    drawLine(lineColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), stroke)
                    drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                    drawLine(lineColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), stroke)
                }
            }

            // Tap-to-Focus Reticle
            focusPoint?.let { pt ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFFFFB800),
                        radius = 34.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFFFFB800).copy(alpha = 0.4f),
                        radius = 4.dp.toPx(),
                        center = pt
                    )
                }
            }

            // Shutter Flash Effect
            AnimatedVisibility(
                visible = showFlashOverlay,
                enter = fadeIn(tween(40)),
                exit = fadeOut(tween(180))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.85f))
                )
            }

            // Zoom Pill Overlay inside Viewport
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1.0f, 2.0f, 3.0f).forEach { level ->
                    val isSelected = kotlin.math.abs(zoomRatio - level) < 0.2f
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFFFFB800) else Color.Transparent)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                zoomRatio = level
                                camera?.cameraControl?.setZoomRatio(level)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSelected) String.format(Locale.US, "%.1fx", zoomRatio) else "${level.toInt()}x",
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Top Glassmorphic Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .background(Color(0xFF16161C).copy(alpha = 0.78f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Rounded.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Rounded.FlashAuto
                        else -> Icons.Rounded.FlashOff
                    },
                    contentDescription = "Flash",
                    tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFFB800) else Color.White
                )
            }

            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFFE53935).copy(alpha = 0.2f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", recordingDurationSec / 60, recordingDurationSec % 60),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            } else {
                Text(
                    text = "LUMINACAM PRO",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }

            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    showGrid = !showGrid
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (showGrid) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                    contentDescription = "Grid",
                    tint = if (showGrid) Color(0xFFFFB800) else Color.White
                )
            }
        }

        // Bottom Controls & Mode Selector
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFF0A0A0C), Color(0xFF0A0A0C))
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pro Exposure Slider when PRO mode is active
            AnimatedVisibility(visible = captureMode == CaptureMode.PRO) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrightnessMedium,
                        contentDescription = "EV",
                        tint = Color(0xFFFFB800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Slider(
                        value = exposureIndex.toFloat(),
                        onValueChange = {
                            exposureIndex = it.toInt()
                            camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
                        },
                        valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFB800),
                            activeTrackColor = Color(0xFFFFB800)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${if (exposureIndex > 0) "+" else ""}$exposureIndex EV",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Mode Tabs
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                CaptureMode.entries.forEach { mode ->
                    val active = captureMode == mode
                    Text(
                        text = mode.title,
                        color = if (active) Color(0xFFFFB800) else Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                if (!isRecording) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    captureMode = mode
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Main Shutter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C24))
                        .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (lastCapturedUri != null) {
                        AsyncImage(
                            model = lastCapturedUri,
                            contentDescription = "Last Capture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                // Shutter Button
                val shutterScale by animateFloatAsState(
                    targetValue = if (isRecording) 0.85f else 1.0f,
                    label = "shutterScale"
                )
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .scale(shutterScale)
                        .clip(CircleShape)
                        .border(3.5.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (captureMode == CaptureMode.VIDEO) {
                                if (isRecording) {
                                    activeRecording?.stop()
                                    activeRecording = null
                                    isRecording = false
                                } else {
                                    startVideoRecording(context, videoCapture) { recording, uri ->
                                        activeRecording = recording
                                        if (uri != null) {
                                            lastCapturedUri = uri
                                        }
                                    }
                                    isRecording = true
                                }
                            } else {
                                showFlashOverlay = true
                                takePhoto(context, imageCapture) { uri ->
                                    showFlashOverlay = false
                                    lastCapturedUri = uri
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(if (isRecording) RoundedCornerShape(14.dp) else CircleShape)
                            .background(
                                when {
                                    captureMode == CaptureMode.VIDEO -> Color(0xFFE53935)
                                    captureMode == CaptureMode.PRO -> Color(0xFFFFB800)
                                    else -> Color.White
                                }
                            )
                    )
                }

                // Switch Camera Button
                IconButton(
                    onClick = {
                        if (!isRecording) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color(0xFF1C1C24), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FlipCameraAndroid,
                        contentDescription = "Flip Camera",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (Uri?) -> Unit
) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Lumina_$name")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LuminaCam")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(output.savedUri)
                Toast.makeText(context, "Фото сохранено", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exc: ImageCaptureException) {
                onSaved(null)
                Toast.makeText(context, "Ошибка съёмки: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

private fun startVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onEvent: (Recording?, Uri?) -> Unit
) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Lumina_VID_$name")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LuminaCam")
        }
    }

    val mediaStoreOutput = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    val recording = videoCapture.output
        .prepareRecording(context, mediaStoreOutput)
        .withAudioEnabled()
        .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            if (recordEvent is VideoRecordEvent.Finalize) {
                if (!recordEvent.hasError()) {
                    Toast.makeText(context, "Видео сохранено", Toast.LENGTH_SHORT).show()
                    onEvent(null, recordEvent.outputResults.outputUri)
                } else {
                    onEvent(null, null)
                }
            }
        }

    onEvent(recording, null)
}
