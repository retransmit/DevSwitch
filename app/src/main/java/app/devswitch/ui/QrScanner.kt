// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.ui

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.devswitch.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** Parses an ADB pairing QR "WIFI:T:ADB;S:<name>;P:<password>;;" into (serviceName, password). */
fun parseAdbPairingQr(text: String): Pair<String, String>? {
    if (!text.startsWith("WIFI:")) return null
    var type: String? = null
    var service: String? = null
    var password: String? = null
    for (field in text.removePrefix("WIFI:").split(";")) {
        when {
            field.startsWith("T:") -> type = field.removePrefix("T:")
            field.startsWith("S:") -> service = field.removePrefix("S:")
            field.startsWith("P:") -> password = field.removePrefix("P:")
        }
    }
    if (type != "ADB" || service.isNullOrBlank() || password.isNullOrBlank()) return null
    return service to password
}

/**
 * Decodes the Y (luminance) plane of each frame. Only an Android pairing QR ends the scan; any
 * other code raises a throttled "wrong QR" signal and scanning continues, so pointing at a random
 * code by mistake does not close the scanner.
 */
private class QrAnalyzer(
    private val onPairingQr: (serviceName: String, password: String) -> Unit,
    private val onOtherQr: () -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var handled = false
    private var lastOtherAt = 0L

    override fun analyze(image: ImageProxy) {
        if (handled) {
            image.close()
            return
        }
        try {
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
            )
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            val parsed = parseAdbPairingQr(text)
            if (parsed != null) {
                handled = true
                onPairingQr(parsed.first, parsed.second)
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastOtherAt > OTHER_QR_THROTTLE_MS) {
                    lastOtherAt = now
                    onOtherQr()
                }
            }
        } catch (_: NotFoundException) {
            // No QR in this frame; keep scanning.
        } catch (_: Exception) {
            // Skip a bad frame rather than crash the analyzer.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private companion object {
        const val OTHER_QR_THROTTLE_MS = 1_500L
    }
}

/** Full-screen camera scanner. Calls [onResult] once with the pairing QR's contents; the caller closes it. */
@Composable
fun QrScannerOverlay(onResult: (serviceName: String, password: String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var wrongQr by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    KeepScreenOn(true)
    LaunchedEffect(wrongQr) {
        if (wrongQr) {
            delay(2_000)
            wrongQr = false
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        // Analyzer callbacks arrive on the analysis thread; hand them to the main thread.
                        val analyzer = QrAnalyzer(
                            onPairingQr = { name, password -> mainExecutor.execute { onResult(name, password) } },
                            onOtherQr = { mainExecutor.execute { wrongQr = true } },
                        )
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }
                    }, mainExecutor)
                    previewView
                },
            )
            Surface(
                color = Color(0xCC1A1B1F),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.scanner_instruction),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
            if (wrongQr) {
                Surface(
                    color = Color(0xCC7F1D1D),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.scanner_wrong_qr),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) { Text(stringResource(R.string.cancel)) }
        }
    }
}
