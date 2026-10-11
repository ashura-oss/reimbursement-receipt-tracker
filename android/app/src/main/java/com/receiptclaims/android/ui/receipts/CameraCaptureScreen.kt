package com.receiptclaims.android.ui.receipts

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

@Composable
fun CameraCaptureScreen(onCaptured: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }

    fun bindCamera() {
        active = true
        cameraReady = false
        cameraError = null
        cameraProviderFuture.addListener({
            if (!active) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                cameraReady = true
            } catch (error: Throwable) {
                cameraError = error.message ?: "Camera is unavailable on this device. Choose a photo from the gallery instead."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(lifecycleOwner) {
        bindCamera()
    }

    DisposableEffect(cameraProviderFuture, imageCapture) {
        onDispose {
            active = false
            cameraReady = false
            if (cameraProviderFuture.isDone) runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }
    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        cameraError?.let { error ->
            Text(error, Modifier.padding(horizontal = 24.dp))
            OutlinedButton(onClick = ::bindCamera, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("Try camera again") }
        }
        Button(
            enabled = cameraReady && !capturing,
            onClick = {
                capturing = true
                val file = File(context.filesDir, "receipt-staging/receipt-${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        capturing = false
                        if (active) onCaptured(Uri.fromFile(file)) else file.delete()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        file.delete()
                        cameraError = exception.message ?: "Could not capture this receipt. Please retry or choose a gallery photo."
                    }
                })
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) { Text("Capture receipt") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("Cancel") }
    }
}
