package com.example.filament_android_demo

import android.Manifest
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.core.content.ContextCompat
import com.example.filament_android_demo.HeadlessRenderer.headMeshName
import com.example.filament_android_demo.ui.theme.Filament_android_demoTheme
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity(), FaceLandmarkerHelper.LandmarkerListener {
  private lateinit var headlessRenderer: HeadlessRenderer
  private var isRendererInitialized = false

  // --- MediaPipe and CameraX ---
  private lateinit var backgroundExecutor: ExecutorService
  private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
  private var cameraProvider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  internal var preview: Preview? = null
  private var imageAnalyzer: ImageAnalysis? = null
  private var cameraFacing = CameraSelector.LENS_FACING_FRONT

  // Compose State for Landmark Results
  private val _landmarkResult = mutableStateOf<FaceLandmarkerResult?>(null)
  val landmarkResult: State<FaceLandmarkerResult?> = _landmarkResult

  private val _imageWidth = mutableStateOf(1)
  val imageWidth: State<Int> = _imageWidth

  private val _imageHeight = mutableStateOf(1)
  val imageHeight: State<Int> = _imageHeight

  // Permission Launcher
  private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
      if (isGranted) {
        showToast("Camera permission granted")
        startCamera()
      } else {
        showToast("Camera permission denied")
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    headlessRenderer = HeadlessRenderer()

    try {
      isRendererInitialized = headlessRenderer.init(this)
      if (!isRendererInitialized) {
        showToast("HeadlessRenderer 初始化失败")
        Log.e("MainActivity", "HeadlessRenderer initialization failed.")
      } else {
        showToast("HeadlessRenderer 初始化成功")
        Log.i("MainActivity", "HeadlessRenderer initialization successful.")
      }
    } catch (e: Exception) {
      isRendererInitialized = false
      showToast("HeadlessRenderer 初始化异常: ${e.message}")
      Log.e("MainActivity", "HeadlessRenderer initialization exception", e)
    }

    backgroundExecutor = Executors.newSingleThreadExecutor()
    setupFaceLandmarkerHelper()

    enableEdgeToEdge()
    setContent {
      Filament_android_demoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          MainScreen(
            modifier = Modifier.padding(innerPadding),
            renderer = headlessRenderer,
            isRendererReady = isRendererInitialized,
            onCheckCameraPermission = { checkCameraPermission() },
            landmarkResult = landmarkResult.value,
            imageWidth = imageWidth.value,
            imageHeight = imageHeight.value
          )
        }
      }
    }
    checkCameraPermission()
  }

  private fun checkCameraPermission() {
    when (PackageManager.PERMISSION_GRANTED) {
      ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) -> {
        startCamera()
      }

      else -> {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }
  }

  private fun setupFaceLandmarkerHelper() {
    faceLandmarkerHelper = FaceLandmarkerHelper(
      context = this,
      runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM,
      minFaceDetectionConfidence = FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE,
      minFaceTrackingConfidence = FaceLandmarkerHelper.DEFAULT_FACE_TRACKING_CONFIDENCE,
      minFacePresenceConfidence = FaceLandmarkerHelper.DEFAULT_FACE_PRESENCE_CONFIDENCE,
      maxNumFaces = FaceLandmarkerHelper.DEFAULT_NUM_FACES,
      currentDelegate = FaceLandmarkerHelper.DELEGATE_CPU,
      faceLandmarkerHelperListener = this
    )
  }

  private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
      try {
        cameraProvider = cameraProviderFuture.get()
        bindCameraUseCases()
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to get camera provider", e)
        showToast("Failed to initialize camera")
      }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun bindCameraUseCases() {
    val cameraProvider = cameraProvider ?: return

    val cameraSelector = CameraSelector.Builder()
      .requireLensFacing(cameraFacing)
      .build()

    // 获取当前屏幕旋转角度 (兼容 API 级别)
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      this.display?.rotation ?: Surface.ROTATION_0
    } else {
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.rotation
    }

    preview = Preview.Builder()
      .setTargetAspectRatio(AspectRatio.RATIO_4_3)
      .setTargetRotation(rotation)
      .build()

    imageAnalyzer = ImageAnalysis.Builder()
      .setTargetAspectRatio(AspectRatio.RATIO_4_3)
      .setTargetRotation(rotation)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
      .build()
      .also {
        it.setAnalyzer(backgroundExecutor) { imageProxy ->
          detectFace(imageProxy)
        }
      }

    cameraProvider.unbindAll()
    try {
      camera = cameraProvider.bindToLifecycle(
        this,
        cameraSelector,
        preview,
        imageAnalyzer
      )
    } catch (exc: Exception) {
      Log.e("MainActivity", "Use case binding failed", exc)
      showToast("Could not start camera: ${exc.message}")
    }
  }

  private fun detectFace(imageProxy: ImageProxy) {
    if (::faceLandmarkerHelper.isInitialized && !faceLandmarkerHelper.isClose()) {
      faceLandmarkerHelper.detectLiveStream(
        imageProxy = imageProxy,
        isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
      )
    } else {
      Log.w("MainActivity", "detectFace called but helper not ready.")
      imageProxy.close()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i("MainActivity", "onDestroy called, cleaning up resources.")
    backgroundExecutor.shutdown()
    if (::headlessRenderer.isInitialized) {
      headlessRenderer.cleanup()
    }
    if (::faceLandmarkerHelper.isInitialized && !faceLandmarkerHelper.isClose()) {
      Executors.newSingleThreadExecutor().execute {
        faceLandmarkerHelper.clearFaceLandmarker()
        Log.i("MainActivity", "FaceLandmarkerHelper cleaned up.")
      }
    }
    cameraProvider?.unbindAll()
  }

  override fun onError(error: String, errorCode: Int) {
    runOnUiThread {
      showToast("FaceLandmarker Error: $error")
      Log.e("MainActivity", "FaceLandmarker Error ($errorCode): $error")
      _landmarkResult.value = null
    }
  }

  override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
    runOnUiThread {
      // *** ADD LOGGING HERE ***
      val result = resultBundle.result
      val matrixExists = result.facialTransformationMatrixes().isPresent && result.facialTransformationMatrixes().get().isNotEmpty()
      Log.d("MainActivity", "onResults: Received result. Matrix present? $matrixExists (Timestamp: ${result.timestampMs()})")
      // *************************
      _landmarkResult.value = resultBundle.result
      _imageWidth.value = resultBundle.inputImageWidth
      _imageHeight.value = resultBundle.inputImageHeight
    }
  }

  override fun onEmpty() {
    runOnUiThread {
      _landmarkResult.value = null
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
  renderer: HeadlessRenderer,
  isRendererReady: Boolean,
  onCheckCameraPermission: () -> Unit,
  landmarkResult: FaceLandmarkerResult?,
  imageWidth: Int,
  imageHeight: Int
) {
  val context = LocalContext.current
  var showDialog by remember { mutableStateOf(false) }
  var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isLoading by remember { mutableStateOf(false) }
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
      ) == PackageManager.PERMISSION_GRANTED
    )
  }

  LaunchedEffect(Unit) {
    hasCameraPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
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
          imageHeight = imageHeight
        )
      } else {
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
    }

    Spacer(modifier = Modifier.height(16.dp))

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text("Click '拍摄' for Filament render.")
      Button(
        onClick = {
          if (!isRendererReady) {
            Toast.makeText(context, "Renderer not ready!", Toast.LENGTH_SHORT).show()
            return@Button
          }
          if (isLoading) return@Button

          isLoading = true
          renderedBitmap = null // 清除之前的 bitmap

          // 点击时缓存当前 landmarkResult
          val resultToApply = landmarkResult

          val entityNameToCenter = headMeshName // 需要居中的实体名
          val scaleFactor = 5.0f // 可调整（1.0为紧贴，<1.0为有留白）

          Log.d("MainScreen", "Button clicked. Adjusting viewport for '$entityNameToCenter'...")

          renderer.updateViewPortAsync(entityNameToCenter, scaleFactor)
            .thenCompose {
              Log.d("MainScreen", "Viewport update complete. Applying latest landmark result...")
              renderer.applyLandmarkResult(resultToApply)
            }
            .thenCompose {
              Log.d("MainScreen", "Landmark result applied. Starting Filament render...")
              renderer.render()
            }
            .handle { bitmap, throwable ->
              (context as? ComponentActivity)?.runOnUiThread {
                isLoading = false
                if (throwable != null) {
                  val cause = if (throwable is CompletionException) throwable.cause
                    ?: throwable else throwable
                  Log.e("MainScreen", "Viewport update or Filament Rendering failed", cause)
                  Toast.makeText(
                    context,
                    "Operation failed: ${cause.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                  ).show()
                } else if (bitmap != null) {
                  Log.d("MainScreen", "Filament Rendering successful after applying landmarks.")
                  renderedBitmap = bitmap
                  showDialog = true
                } else {
                  Log.e("MainScreen", "Operation completed but bitmap was null.")
                }
              }
            }
        },
        enabled = isRendererReady && !isLoading
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary
          )
        } else {
          Text("拍摄 (Capture Filament)")
        }
      }
    }

    if (showDialog && renderedBitmap != null) {
      AlertDialog(
        onDismissRequest = {
          showDialog = false
          renderedBitmap = null
        },
        title = { Text("Filament Rendered Image") },
        text = {
          Image(
            bitmap = renderedBitmap!!.asImageBitmap(),
            contentDescription = "Headless Render Result",
            modifier = Modifier.fillMaxWidth()
          )
        },
        confirmButton = {
          TextButton(onClick = {
            showDialog = false
            renderedBitmap = null
          }) { Text("Close") }
        }
      )
    }
  }
}

@Composable
fun CameraPreviewWithLandmarks(
  modifier: Modifier = Modifier,
  landmarkResult: FaceLandmarkerResult?,
  imageWidth: Int,
  imageHeight: Int
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val context = LocalContext.current
  val mainActivity = context as? MainActivity

  var localPreviewView: PreviewView? by remember { mutableStateOf(null) }
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
    AndroidView(
      factory = { ctx ->
        PreviewView(ctx).apply {
          layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          scaleType = PreviewView.ScaleType.FILL_START
          implementationMode = PreviewView.ImplementationMode.COMPATIBLE
          localPreviewView = this
        }
      },
      modifier = Modifier.fillMaxSize()
    )

    LaunchedEffect(mainActivity, localPreviewView, mainActivity?.preview) {
      val cameraPreviewUseCase = mainActivity?.preview
      val pv = localPreviewView
      if (cameraPreviewUseCase != null && pv != null) {
        cameraPreviewUseCase.setSurfaceProvider(pv.surfaceProvider)
        Log.d("CameraPreviewWithLandmarks", "SurfaceProvider SET successfully.")
      } else {
        Log.d("CameraPreviewWithLandmarks", "SurfaceProvider NOT set: mainActivity.preview is ${mainActivity?.preview}, localPreviewView is $pv")
      }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
      if (scaleFactor <= 0f || imageWidth <= 0 || imageHeight <= 0) return@Canvas

      landmarkResult?.let { result ->
        result.faceLandmarks().forEach { landmarks ->
          com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FACE_LANDMARKS_CONNECTORS.forEach { connector ->
            val startIdx = connector.start()
            val endIdx = connector.end()
            if (startIdx >= 0 && startIdx < landmarks.size && endIdx >= 0 && endIdx < landmarks.size) {
              val start = landmarks[startIdx]
              val end = landmarks[endIdx]
              val scaledImageWidth = imageWidth * scaleFactor
              val scaledImageHeight = imageHeight * scaleFactor
              val offsetX = (size.width - scaledImageWidth) / 2f
              val offsetY = (size.height - scaledImageHeight) / 2f
              val startX = start.x() * scaledImageWidth + offsetX
              val startY = start.y() * scaledImageHeight + offsetY
              val endX = end.x() * scaledImageWidth + offsetX
              val endY = end.y() * scaledImageHeight + offsetY
              drawLine(
                color = Color.Green,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 4.0f
              )
            }
          }
          landmarks.forEach { landmark ->
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor
            val offsetX = (size.width - scaledImageWidth) / 2f
            val offsetY = (size.height - scaledImageHeight) / 2f
            val pointX = landmark.x() * scaledImageWidth + offsetX
            val pointY = landmark.y() * scaledImageHeight + offsetY
            drawCircle(
              color = Color.Yellow,
              radius = 6f,
              center = Offset(pointX, pointY)
            )
          }
        }
      }
    }
  }
}

@Composable
fun MainScreenPreview() {
  Filament_android_demoTheme {
    MainScreen(
      renderer = HeadlessRenderer(),
      isRendererReady = true,
      onCheckCameraPermission = {},
      landmarkResult = null,
      imageWidth = 1,
      imageHeight = 1
    )
  }
}