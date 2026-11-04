package io.github.marioalr.tpvca.ui.link

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Pedir la cámara tarda: si la persona ya salió de la pantalla cuando llega, no se enciende. Y al salir se apaga. Todo corre en el hilo principal.
 */
class CameraBinding {
    private var left = false
    private var unbind: (() -> Unit)? = null

    fun bindWhenReady(unbind: () -> Unit, bind: () -> Unit) {
        if (left) return
        this.unbind = unbind
        bind()
    }

    fun leave() {
        left = true
        unbind?.invoke()
        unbind = null
    }
}

/**
 * Lee el QR con CameraX y ML Kit, con el modelo incluido en la app (plan §7.2). Pide el permiso de la cámara al entrar. Sin él o si falla, se
 * pegan a mano. Lo leído no se guarda, no se registra y solo llega a [onText]. ML Kit se comprueba con `QrDecodingDeviceTest`; la cámara, solo en un equipo.
 */
@Composable
fun CameraQrReader(onText: (String) -> Unit, onNoPermission: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onNoPermission()
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    if (granted) CameraPreview(onText)
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val future = ProcessCameraProvider.getInstance(context)
        val binding = CameraBinding()
        future.addListener(
            {
                val cameras = future.get()
                binding.bindWhenReady(unbind = cameras::unbindAll) {
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        // Un token de ~350 caracteres da un QR de ~100 módulos: a baja resolución no se distinguen
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor) { image -> analyze(image, scanner) { text -> currentOnText(text) } } }
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            binding.leave()
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView({ previewView }, Modifier.fillMaxWidth().aspectRatio(1f))
}

@OptIn(ExperimentalGetImage::class)
private fun analyze(image: ImageProxy, scanner: BarcodeScanner, onText: (String) -> Unit) {
    val media = image.image
    if (media == null) {
        image.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
        .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onText) }
        .addOnCompleteListener { image.close() }
}
