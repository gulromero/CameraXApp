package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.cameraxapp.ui.theme.CameraXAppTheme
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import java.nio.charset.Charset.isSupported


typealias LumaListener = (Double) -> Unit

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            CameraXAppTheme {
                val context = LocalContext.current
                var hasPermission by remember { mutableStateOf(false) }
                var lumaValue by remember { mutableStateOf(0.0) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val granted = permissions.entries.all {
                        it.key in REQUIRED_PERMISSIONS && it.value
                    }
                    hasPermission = granted

                    if (!granted) {
                        Toast.makeText(context, "Permission request denied", Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(true) {
                    if (allPermissionsGranted(context)) {
                        hasPermission = true
                    } else {
                        permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
                    if (hasPermission) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    startCamera(previewView, onLumaChanged = { lumaValue = it })
                                    previewView
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )

                            Text(
                                text = "🩷 Brightness: ${"%.2f".format(lumaValue)}",
                                modifier = Modifier.padding(16.dp)
                            )

                            Button(
                                onClick = { takePhoto() },
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth()
                            ) {
                                Text("Take Photo")
                            }
                        }
                    } else {
                        Text(
                            text = "Camera permission required",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding)
                        )
                    }
                }
            }
        }
    }

    private fun startCamera(previewView: PreviewView, onLumaChanged: (Double) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val extensionsManagerFuture =
                ExtensionsManager.getInstanceAsync(this, cameraProvider)

            extensionsManagerFuture.addListener({
                val extensionsManager = extensionsManagerFuture.get()
                val baseCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                val isSupported = extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.FACE_RETOUCH)
                Log.d("EXTENSION_CHECK", "Face Retouch supported: $isSupported")

                Log.d("EXTENSION_CHECK", "Face Retouch supported: $isSupported")




                val cameraSelector = if (
                    extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.FACE_RETOUCH)
                ) {
                    extensionsManager.getExtensionEnabledCameraSelector(baseCameraSelector, ExtensionMode.FACE_RETOUCH)
                } else {
                    Toast.makeText(this, "Face Retouch not supported on this device", Toast.LENGTH_SHORT).show()
                    baseCameraSelector
                }

                try {
                    cameraProvider.unbindAll()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder().build()

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                                onLumaChanged(luma)
                                Log.d(TAG, "Average luminosity: $luma")
                            })
                        }

                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(this))

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private fun allPermissionsGranted(context: android.content.Context) = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }
}
