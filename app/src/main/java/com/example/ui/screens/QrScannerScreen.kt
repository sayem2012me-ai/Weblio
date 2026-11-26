package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.WeblioCyan
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object QrScannerDecoder {

    fun createQrReader(): MultiFormatReader {
        return MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true
                )
            )
        }
    }

    /**
     * Extracts tight width * height Y-plane bytes respecting device rowStride and pixelStride padding.
     * Without stride normalization, cameras that pad Y rows (rowStride > width) corrupt QR finder geometry.
     */
    fun extractTightLumaBytes(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ByteArray {
        val dup = yBuffer.duplicate()
        dup.rewind()
        val target = ByteArray(width * height)
        val capacity = dup.remaining()

        if (rowStride == width && pixelStride == 1 && capacity >= width * height) {
            dup.get(target, 0, width * height)
            return target
        }

        val rowBytes = ByteArray(rowStride.coerceAtLeast(width))
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            if (rowOffset >= capacity) break
            dup.position(rowOffset)
            if (pixelStride == 1) {
                val count = minOf(width, capacity - rowOffset)
                dup.get(target, row * width, count)
            } else {
                val maxRead = minOf(rowStride, capacity - rowOffset)
                dup.get(rowBytes, 0, maxRead)
                val baseDest = row * width
                for (col in 0 until width) {
                    val srcIdx = col * pixelStride
                    if (srcIdx < maxRead) {
                        target[baseDest + col] = rowBytes[srcIdx]
                    }
                }
            }
        }
        return target
    }

    fun decodeFromImageProxy(image: ImageProxy, reader: MultiFormatReader): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val yBytes = extractTightLumaBytes(
            yBuffer = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride
        )
        return decodeFromTightLumaBytes(
            tightYBytes = yBytes,
            width = image.width,
            height = image.height,
            reader = reader
        )
    }

    fun decodeFromYuvBytes(
        yBytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1,
        reader: MultiFormatReader = createQrReader()
    ): String? {
        if (width <= 0 || height <= 0 || yBytes.isEmpty()) return null
        val tight = extractTightLumaBytes(
            yBuffer = ByteBuffer.wrap(yBytes),
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        )
        return decodeFromTightLumaBytes(tight, width, height, reader)
    }

    fun decodeFromTightLumaBytes(
        tightYBytes: ByteArray,
        width: Int,
        height: Int,
        reader: MultiFormatReader = createQrReader()
    ): String? {
        if (width <= 0 || height <= 0 || tightYBytes.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            tightYBytes,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        return decodeLuminanceSource(source, reader)
    }

    fun decodeFromBitmap(bitmap: Bitmap, reader: MultiFormatReader = createQrReader()): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        return decodeLuminanceSource(source, reader)
    }

    fun decodeLuminanceSource(source: LuminanceSource, reader: MultiFormatReader): String? {
        val inverted = InvertedLuminanceSource(source)
        val attempts = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(inverted)),
            BinaryBitmap(GlobalHistogramBinarizer(inverted))
        )
        for (candidate in attempts) {
            try {
                val res = reader.decodeWithState(candidate)
                if (!res.text.isNullOrBlank()) {
                    return res.text
                }
            } catch (_: Throwable) {
                // Try next binarizer / polarity
            } finally {
                runCatching { reader.reset() }
            }
        }
        return null
    }
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onQrDecoded: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var statusText by remember { mutableStateOf("Point camera at a QR code — scans automatically") }
    val decodedOnce = remember { AtomicBoolean(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val galleryQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val decoded = runCatching {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, boundsOpts)
                    }
                    val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1600) {
                        sampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    } ?: return@runCatching null
                    try {
                        QrScannerDecoder.decodeFromBitmap(bmp)
                    } finally {
                        bmp.recycle()
                    }
                }.getOrNull()

                withContext(Dispatchers.Main) {
                    if (!decoded.isNullOrBlank()) {
                        if (decodedOnce.compareAndSet(false, true)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQrDecoded(decoded)
                        }
                    } else {
                        statusText = "No QR code found in selected image"
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(boundCamera, torchEnabled) {
        val cam = boundCamera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) {
            runCatching { cam.cameraControl.enableTorch(torchEnabled) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (hasFlashUnit) {
                        IconButton(
                            onClick = { torchEnabled = !torchEnabled },
                            modifier = Modifier.testTag("qr_torch_button")
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = if (torchEnabled) "Turn off flashlight" else "Turn on flashlight"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Camera permission is required to scan QR codes.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag("qr_grant_permission_button")
                    ) {
                        Text("Grant Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            galleryQrLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("qr_scan_gallery_button")
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("From Photo")
                    }
                }
            }
        } else {
            val executor = remember { Executors.newSingleThreadExecutor() }
            val qrReader = remember { QrScannerDecoder.createQrReader() }
            var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    runCatching {
                        if (boundCamera?.cameraInfo?.hasFlashUnit() == true) {
                            boundCamera?.cameraControl?.enableTorch(false)
                        }
                        boundCameraProvider?.unbindAll()
                    }
                    boundCamera = null
                    boundCameraProvider = null
                    runCatching { executor.shutdownNow() }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            if (executor.isShutdown) return@addListener
                            val provider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
                            boundCameraProvider = provider

                            val hdResolutionSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()

                            val preview = Preview.Builder()
                                .setResolutionSelector(hdResolutionSelector)
                                .build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }

                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(hdResolutionSelector)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { imgAnalysis ->
                                    imgAnalysis.setAnalyzer(executor) { imageProxy ->
                                        try {
                                            if (!decodedOnce.get()) {
                                                val result = QrScannerDecoder.decodeFromImageProxy(imageProxy, qrReader)
                                                if (!result.isNullOrBlank() && decodedOnce.compareAndSet(false, true)) {
                                                    previewView.post {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onQrDecoded(result)
                                                    }
                                                }
                                            }
                                        } finally {
                                            imageProxy.close()
                                        }
                                    }
                                }

                            runCatching {
                                provider.unbindAll()
                                val camera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                                boundCamera = camera
                                hasFlashUnit = camera.cameraInfo.hasFlashUnit()

                                // Trigger initial center focus lock so close QR codes snap sharp immediately
                                val centerFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                                val centerPoint = centerFactory.createPoint(0.5f, 0.5f)
                                val focusAction = FocusMeteringAction.Builder(
                                    centerPoint,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                )
                                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                                    .build()
                                camera.cameraControl.startFocusAndMetering(focusAction)

                                previewView.setOnTouchListener { view, event ->
                                    if (event.action == MotionEvent.ACTION_UP) {
                                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                                        val tapAction = FocusMeteringAction.Builder(
                                            point,
                                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                        )
                                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                            .build()
                                        camera.cameraControl.startFocusAndMetering(tapAction)
                                        view.performClick()
                                    }
                                    true
                                }
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    onRelease = {
                        runCatching { boundCameraProvider?.unbindAll() }
                    }
                )

                // Clean semi-transparent viewfinder reticle overlay
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                ) {
                    val boxSize = minOf(size.width, size.height) * 0.66f
                    val left = (size.width - boxSize) / 2f
                    val top = (size.height - boxSize) / 2f
                    val cornerRadiusPx = 22.dp.toPx()

                    drawRect(color = Color.Black.copy(alpha = 0.45f))
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        blendMode = BlendMode.Clear
                    )

                    val stroke = 4.dp.toPx()
                    val bracketLen = 28.dp.toPx()
                    val c = WeblioCyan
                    // Top-left
                    drawLine(c, Offset(left, top + bracketLen), Offset(left, top + 6f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(left + 6f, top), Offset(left + bracketLen, top), stroke, StrokeCap.Round)
                    // Top-right
                    drawLine(c, Offset(left + boxSize - bracketLen, top), Offset(left + boxSize - 6f, top), stroke, StrokeCap.Round)
                    drawLine(c, Offset(left + boxSize, top + 6f), Offset(left + boxSize, top + bracketLen), stroke, StrokeCap.Round)
                    // Bottom-left
                    drawLine(c, Offset(left, top + boxSize - bracketLen), Offset(left, top + boxSize - 6f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(left + 6f, top + boxSize), Offset(left + bracketLen, top + boxSize), stroke, StrokeCap.Round)
                    // Bottom-right
                    drawLine(c, Offset(left + boxSize - bracketLen, top + boxSize), Offset(left + boxSize - 6f, top + boxSize), stroke, StrokeCap.Round)
                    drawLine(c, Offset(left + boxSize, top + boxSize - 6f), Offset(left + boxSize, top + boxSize - bracketLen), stroke, StrokeCap.Round)
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            galleryQrLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("qr_scan_gallery_button")
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR from Photo")
                    }
                }
            }
        }
    }
}
