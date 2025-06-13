package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.cameraxapp.ui.theme.CameraXAppTheme
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.Arrays.stream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
                var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
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
                                onClick = {
                                    takePhotoInMemory(context) { bitmap ->
                                        capturedBitmap = bitmap
                                    }
                                },
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth()
                            ) {
                                Text("Take Photo")
                            }

                            capturedBitmap?.let {
                                Column {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Captured Photo",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .padding(16.dp)
                                    )
                                    Button(
                                        onClick = { capturedBitmap = null },
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Text("Go back")
                                    }
                                }
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
                Log.d(TAG, "Face Retouch supported: $isSupported")

                val cameraSelector = if (isSupported) {
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

    private fun takePhotoInMemory(context: Context, onPhotoCaptured: (Bitmap) -> Unit) {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap().rotate(270f)
                    image.close()
                    saveBitmapToGallery(context, bitmap)
                    onPhotoCaptured(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
        val filename = "CameraXApp_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraXApp")
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            val stream = resolver.openOutputStream(uri)

            if (stream != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                stream.flush()
                stream.close()
                Toast.makeText(context, "Saved to Gallery!!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to open stream", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
        }
    }


    private fun allPermissionsGranted(context: Context) =
        REQUIRED_PERMISSIONS.all {
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

// Extension to convert ImageProxy to Bitmap
private fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()

    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun Bitmap.rotate(degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
