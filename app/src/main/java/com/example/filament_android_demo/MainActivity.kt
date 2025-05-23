package com.example.filament_android_demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.os.Build
import android.view.Surface
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import com.example.filament_android_demo.drawFaceLandmarksToCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.core.content.ContextCompat
import com.example.filament_android_demo.ModelRender.headMeshName
import com.example.filament_android_demo.ui.theme.Filament_android_demoTheme
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/*
* TODO
* 将所有Mediapipe相关的操作封装到一个类中，类名为MediaPipeProcessor
* 暴露如下方法：
* 1. init，加载模型，与camera连接,启动检查，总之完成一切需要的准备工作，后续就要出结果了
* 2. setOnResult，设置回调，当拿到FaceLandmarkerResult时调用，传入Consumer<FaceLandmarkerResult>
* 3. setOnError，设置错误回调,可以为空，默认打一个日志
* 4. release,释放资源
* 注意，其余的部分都封装在其内部，不暴露出来，同时修改这个类，适配新的类
* 注意，虽然代码重构，但是功能不变
* */


class MainActivity : ComponentActivity() {
    private lateinit var modelRender: ModelRender
    private var isRendererInitialized by mutableStateOf(false)

    // MediaPipeProcessor 封装
    private lateinit var mediaPipeProcessor: MediaPipeProcessor

    // Compose State for Landmark Results
    private val _landmarkResult = mutableStateOf<FaceLandmarkerResult?>(null)
    val landmarkResult: State<FaceLandmarkerResult?> = _landmarkResult

    // Compose State for Camera Bitmap
    private val _cameraBitmap = mutableStateOf<Bitmap?>(null)
    val cameraBitmap: State<Bitmap?> = _cameraBitmap

    // State for overlay switch
    private val _overlayEnabled = mutableStateOf(false)

    private val _imageWidth = mutableStateOf(1)
    val imageWidth: State<Int> = _imageWidth

    private val _imageHeight = mutableStateOf(1)
    val imageHeight: State<Int> = _imageHeight

    // Permission Launcher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                showToast("Camera permission granted")
                // 权限通过后初始化 MediaPipeProcessor
                mediaPipeProcessor.init(this)
            } else {
                showToast("Camera permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelRender = ModelRender()

        // 异步初始化 ModelRender，使用 CompletableFuture
        modelRender.init(this).handle { _, throwable ->
            runOnUiThread {
                if (throwable != null) {
                    isRendererInitialized = false
                    showToast("HeadlessRenderer 初始化失败: ${throwable.message}")
                    Log.e("MainActivity", "HeadlessRenderer initialization failed", throwable)
                } else {
                    isRendererInitialized = true
                    showToast("HeadlessRenderer 初始化成功")
                    Log.i("MainActivity", "HeadlessRenderer initialization successful.")
                }
            }
        }

        // 初始化 MediaPipeProcessor
        mediaPipeProcessor = MediaPipeProcessor(this)
        mediaPipeProcessor.setOnResult { resultBundle ->
            runOnUiThread {
                _landmarkResult.value = resultBundle.result
                _imageWidth.value = resultBundle.inputImageWidth
                _imageHeight.value = resultBundle.inputImageHeight
                _cameraBitmap.value = resultBundle.cameraImage
                Log.d(
                    "MainActivity",
                    "onResults from MediaPipeProcessor: Timestamp ${resultBundle.result.timestampMs()}"
                )
            }
        }
        mediaPipeProcessor.setOnErrorListener { error, errorCode ->
            runOnUiThread {
                showToast("FaceLandmarker Error: $error")
                Log.e("MainActivity", "FaceLandmarker Error ($errorCode) from MediaPipeProcessor: $error")
                _landmarkResult.value = null
            }
        }
        mediaPipeProcessor.setOnEmptyListener {
            runOnUiThread {
                _landmarkResult.value = null
            }
        }

        enableEdgeToEdge()
        setContent {
            Filament_android_demoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        renderer = modelRender,
                        isRendererReady = isRendererInitialized,
                        mediaPipeProcessor = mediaPipeProcessor,
                        onCheckCameraPermission = { checkCameraPermission() },
                        landmarkResult = landmarkResult.value,
                        imageWidth = imageWidth.value,
                        imageHeight = imageHeight.value,
                        overlayEnabled = _overlayEnabled, // Pass the state
                        cameraBitmap = cameraBitmap.value
                    )
                }
            }
        }
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) -> {
                mediaPipeProcessor.init(this)
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MainActivity", "onDestroy called, cleaning up resources.")
        if (::mediaPipeProcessor.isInitialized) {
            mediaPipeProcessor.release()
        }
        if (::modelRender.isInitialized) {
            modelRender.release().handle { _, throwable ->
                if (throwable != null) {
                    Log.e("MainActivity", "Error during ModelRender release", throwable)
                } else {
                    Log.i("MainActivity", "ModelRender released successfully.")
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

// --- Composables ---

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    renderer: ModelRender,
    isRendererReady: Boolean,
    mediaPipeProcessor: MediaPipeProcessor,
    onCheckCameraPermission: () -> Unit,
    landmarkResult: FaceLandmarkerResult?,
    imageWidth: Int,
    imageHeight: Int,
    overlayEnabled: State<Boolean>,
    cameraBitmap: Bitmap?
) {
    val context = LocalContext.current
    var showDebugImagesDialog by remember { mutableStateOf(false) }
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isOverlayLoading by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }

    // Debug image states
    var image1DebugBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var image2DebugBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var image3DebugBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val currentOverlayEnabled by overlayEnabled

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Handle real-time overlay rendering
    LaunchedEffect(landmarkResult, currentOverlayEnabled, isRendererReady) {
        if (currentOverlayEnabled && isRendererReady) {
            if (landmarkResult != null) {
                if (isOverlayLoading) return@LaunchedEffect
                isOverlayLoading = true

                renderer.applyLandmarkResultAndRender(landmarkResult)
                    .handle { bitmap, throwable ->
                        (context as? ComponentActivity)?.runOnUiThread {
                            isOverlayLoading = false
                            if (throwable != null) {
                                val cause =
                                    if (throwable is CompletionException) throwable.cause ?: throwable else throwable
                                Log.e("MainScreen", "Overlay: Rendering failed", cause)
                                overlayBitmap = null
                            } else if (bitmap != null) {
                                Log.d("MainScreen", "Overlay: Rendering successful.")
                                overlayBitmap = bitmap
                            } else {
                                Log.e("MainScreen", "Overlay: Rendering completed but bitmap was null.")
                                overlayBitmap = null
                            }
                        }
                    }
            } else {
                overlayBitmap = null
                isOverlayLoading = false
                Log.d("MainScreen", "Overlay: No landmark result, clearing overlay bitmap.")
            }
        } else if (!currentOverlayEnabled) {
            overlayBitmap = null
            isOverlayLoading = false
            Log.d("MainScreen", "Overlay: Disabled, clearing overlay bitmap.")
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithLandmarks(
                    modifier = Modifier.fillMaxSize(),
                    landmarkResult = landmarkResult,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    overlayBitmap = overlayBitmap,
                    overlayEnabled = currentOverlayEnabled,
                    cameraBitmap = cameraBitmap
                )
            } else {
                CameraPermissionContent(onCheckCameraPermission = onCheckCameraPermission)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ControlPanel(
            currentOverlayEnabled = currentOverlayEnabled,
            overlayEnabled = overlayEnabled,
            isRendererReady = isRendererReady,
            isLoading = isLoading,
            onCaptureClick = {
                handleCaptureClick(
                    context = context,
                    isRendererReady = isRendererReady,
                    isLoading = isLoading,
                    setLoading = { isLoading = it },
                    landmarkResult = landmarkResult,
                    cameraBitmap = cameraBitmap,
                    renderer = renderer,
                    onDebugImagesGenerated = { img1, img2, img3 ->
                        image1DebugBitmap = img1
                        image2DebugBitmap = img2
                        image3DebugBitmap = img3
                        showDebugImagesDialog = true
                    }
                )
            },
            onOverlayChange = { enabled ->
                (overlayEnabled as? MutableState<Boolean>)?.value = enabled
                if (!enabled) {
                    overlayBitmap = null
                }
            }
        )
    }

    if (showDebugImagesDialog) {
        DebugImagesDialog(
            image1DebugBitmap = image1DebugBitmap,
            image2DebugBitmap = image2DebugBitmap,
            image3DebugBitmap = image3DebugBitmap,
            onDismiss = {
                showDebugImagesDialog = false
                image1DebugBitmap = null
                image2DebugBitmap = null
                image3DebugBitmap = null
            }
        )
    }
}

@Composable
fun CameraPreviewWithLandmarks(
    modifier: Modifier = Modifier,
    landmarkResult: FaceLandmarkerResult?,
    imageWidth: Int,
    imageHeight: Int,
    overlayBitmap: Bitmap?,
    overlayEnabled: Boolean,
    cameraBitmap: Bitmap? // 新增相机预览图像
) {
    var overlayWidth by remember { mutableStateOf(1) }
    var overlayHeight by remember { mutableStateOf(1) }
    var scaleFactor by remember { mutableStateOf(1f) }

    Box(modifier = modifier.onGloballyPositioned { layoutCoordinates: LayoutCoordinates ->
        overlayWidth = layoutCoordinates.size.width
        overlayHeight = layoutCoordinates.size.height
        if (imageWidth > 0 && imageHeight > 0 && overlayWidth > 0 && overlayHeight > 0) {
            scaleFactor = max(overlayWidth * 1f / imageWidth, overlayHeight * 1f / imageHeight)
        }
    }) {
        // Display camera preview using Image composable
        if (cameraBitmap != null) {
            var bitmapWithLandmarks = drawFaceLandmarksOnBitmap(cameraBitmap, landmarkResult)
            if (bitmapWithLandmarks == null) {
                bitmapWithLandmarks = cameraBitmap
            }
            var bitmapWith3D = bitmapWithLandmarks
            if (overlayEnabled && overlayBitmap != null) {
                bitmapWith3D = draw3DOverlayToBitmap(
                    cameraImage = bitmapWithLandmarks,
                    modelImage = overlayBitmap,
                    landmarkResult = landmarkResult,
                )
            }
            if (bitmapWith3D == null) {
                bitmapWith3D = bitmapWithLandmarks
            }
            Image(
                bitmap = bitmapWith3D.asImageBitmap(),
                contentDescription = "Camera Preview",
                modifier = Modifier.fillMaxSize()
            )
        }

//    Canvas(modifier = Modifier.fillMaxSize()) {
//      if (scaleFactor <= 0f || imageWidth <= 0 || imageHeight <= 0) return@Canvas
//
//      drawFaceLandmarksToCanvas(
//        landmarkResult = landmarkResult,
//        imageWidth = imageWidth,
//        imageHeight = imageHeight
//      )
//      // Draw Overlay Bitmap
//      if (overlayEnabled) {
//        draw3DOverlayToCanvas(
//          modelImange = overlayBitmap,
//          landmarkResult = landmarkResult,
//          cameraInageWidth = imageWidth,
//          cameraImageHeight = imageHeight
//        )
//      }
//    }
    }
}

@Composable
fun CameraPermissionContent(onCheckCameraPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Camera permission needed to show live preview.")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onCheckCameraPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun ControlPanel(
    currentOverlayEnabled: Boolean,
    overlayEnabled: State<Boolean>,
    isRendererReady: Boolean,
    isLoading: Boolean,
    onCaptureClick: () -> Unit,
    onOverlayChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Enable 3D Model Overlay")
        Switch(
            checked = currentOverlayEnabled,
            onCheckedChange = onOverlayChange
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onCaptureClick,
            enabled = isRendererReady && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Debug")
            }
        }
    }
}

@Composable
fun DebugImagesDialog(
    image1DebugBitmap: Bitmap?,
    image2DebugBitmap: Bitmap?,
    image3DebugBitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调试图像 Debug Images") },
        text = {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("1. 原始图像 + 特征点", style = MaterialTheme.typography.titleMedium)
                image1DebugBitmap?.let { img ->
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "Original + Landmarks",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(img.width.toFloat() / img.height.toFloat())
                            .padding(vertical = 4.dp)
                            .border(1.dp, Color.Gray)
                    )
                } ?: Text("图像1未生成", modifier = Modifier.padding(vertical = 4.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text("2. 3D模型渲染", style = MaterialTheme.typography.titleMedium)
                image2DebugBitmap?.let { img ->
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "3D Model",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(img.width.toFloat() / img.height.toFloat())
                            .padding(vertical = 4.dp)
                            .border(1.dp, Color.Gray)
                    )
                } ?: Text("图像2未生成", modifier = Modifier.padding(vertical = 4.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text("3. 原始图像 + 特征点 + 3D模型", style = MaterialTheme.typography.titleMedium)
                image3DebugBitmap?.let { img ->
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "Combined Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(img.width.toFloat() / img.height.toFloat())
                            .padding(vertical = 4.dp)
                            .border(1.dp, Color.Gray)
                    )
                } ?: Text("图像3未生成", modifier = Modifier.padding(vertical = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun handleCaptureClick(
    context: Context,
    isRendererReady: Boolean,
    isLoading: Boolean,
    setLoading: (Boolean) -> Unit,
    landmarkResult: FaceLandmarkerResult?,
    cameraBitmap: Bitmap?,
    renderer: ModelRender,
    onDebugImagesGenerated: (Bitmap?, Bitmap?, Bitmap?) -> Unit
) {
    if (!isRendererReady) {
        Toast.makeText(context, "Renderer not ready!", Toast.LENGTH_SHORT).show()
        return
    }
    if (isLoading) return

    setLoading(true)
    Log.d("MainScreen", "拍摄按钮点击，开始生成调试图像...")

    // Generate Image 2 (3D Model) first
    renderer.applyLandmarkResultAndRender(landmarkResult)
        .handle { bitmap, throwable ->
            (context as? ComponentActivity)?.runOnUiThread {
                setLoading(false)
                if (throwable != null) {
                    val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable
                    Log.e("MainScreen", "3D Model rendering failed", cause)
                    Toast.makeText(
                        context,
                        "渲染失败: ${cause.message ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                    onDebugImagesGenerated(null, null, null)
                } else if (bitmap != null) {
                    Log.d("MainScreen", "3D Model (Image 2) rendered successfully.")
                    var image1: Bitmap? = null
                    var image3: Bitmap? = null

                    if (cameraBitmap != null && landmarkResult != null) {
                        image1 = drawFaceLandmarksOnBitmap(
                            originalBitmap = cameraBitmap,
                            landmarkResult = landmarkResult
                        )
                        Log.d("MainScreen", "Original + Landmarks (Image 1) generated.")

                        if (image1 != null) {
                            image3 = draw3DOverlayToBitmap(
                                cameraImage = image1,
                                modelImage = bitmap,
                                landmarkResult = landmarkResult,
                            )
                            Log.d("MainScreen", "Combined image (Image 3) generated.")
                        }
                    }

                    onDebugImagesGenerated(image1, bitmap, image3)
                } else {
                    Log.e("MainScreen", "3D Model rendering completed but bitmap was null.")
                    onDebugImagesGenerated(null, null, null)
                }
            }
        }
}
