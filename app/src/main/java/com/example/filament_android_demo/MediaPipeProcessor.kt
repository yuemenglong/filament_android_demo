package com.example.filament_android_demo

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

class MediaPipeProcessor(private val context: Context) : FaceLandmarkerHelper.LandmarkerListener {

    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private var onResultCallback: Consumer<FaceLandmarkerHelper.ResultBundle>? = null
    private var onErrorCallback: ((String, Int) -> Unit)? = null
    private var onEmptyCallback: (() -> Unit)? = null

    data class MediaPipeConfig(
        val minFaceDetectionConfidence: Float = FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE,
        val minFaceTrackingConfidence: Float = FaceLandmarkerHelper.DEFAULT_FACE_TRACKING_CONFIDENCE,
        val minFacePresenceConfidence: Float = FaceLandmarkerHelper.DEFAULT_FACE_PRESENCE_CONFIDENCE,
        val maxNumFaces: Int = FaceLandmarkerHelper.DEFAULT_NUM_FACES,
        val currentDelegate: Int = FaceLandmarkerHelper.DELEGATE_CPU
    )

    fun init(
        activity: ComponentActivity,
        initialCameraFacing: Int = CameraSelector.LENS_FACING_FRONT,
        config: MediaPipeConfig = MediaPipeConfig()
    ) {
        backgroundExecutor = Executors.newSingleThreadExecutor()
        this.cameraFacing = initialCameraFacing

        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = context,
            runningMode = RunningMode.LIVE_STREAM,
            minFaceDetectionConfidence = config.minFaceDetectionConfidence,
            minFaceTrackingConfidence = config.minFaceTrackingConfidence,
            minFacePresenceConfidence = config.minFacePresenceConfidence,
            maxNumFaces = config.maxNumFaces,
            currentDelegate = config.currentDelegate,
            faceLandmarkerHelperListener = this
        )

        startCamera(activity)
    }

    private fun startCamera(activity: ComponentActivity) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(activity)
            } catch (e: Exception) {
                val errorMsg = "Failed to get camera provider"
                Log.e("MediaPipeProcessor", errorMsg, e)
                onErrorCallback?.invoke(errorMsg, -1)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("UnsafeOptInUsageError")
    private fun bindCameraUseCases(activity: ComponentActivity) {
        val cameraProvider = cameraProvider ?: run {
            Log.e("MediaPipeProcessor", "Camera provider not initialized.")
            onErrorCallback?.invoke("Camera provider not initialized.", -1)
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraFacing)
            .build()

        val rotation = activity.display?.rotation ?: android.view.Surface.ROTATION_0

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
                activity,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            val errorMsg = "Use case binding failed"
            Log.e("MediaPipeProcessor", errorMsg, exc)
            onErrorCallback?.invoke(errorMsg, -1)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        if (::faceLandmarkerHelper.isInitialized && !faceLandmarkerHelper.isClose()) {
            faceLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        } else {
            imageProxy.close()
        }
    }

    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    fun setOnResult(callback: Consumer<FaceLandmarkerHelper.ResultBundle>) {
        this.onResultCallback = callback
    }

    fun setOnEmptyListener(listener: () -> Unit) {
        this.onEmptyCallback = listener
    }

    fun setOnErrorListener(listener: (error: String, errorCode: Int) -> Unit) {
        this.onErrorCallback = listener
    }

    fun release() {
        Log.i("MediaPipeProcessor", "Releasing MediaPipeProcessor resources.")
        if (::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown) {
            backgroundExecutor.shutdown()
        }
        if (::faceLandmarkerHelper.isInitialized && !faceLandmarkerHelper.isClose()) {
            val helper = faceLandmarkerHelper
            Executors.newSingleThreadExecutor().execute {
                helper.clearFaceLandmarker()
                Log.i("MediaPipeProcessor", "FaceLandmarkerHelper resources cleared.")
            }
        }
        cameraProvider?.unbindAll()
        Log.i("MediaPipeProcessor", "Camera resources unbound.")
    }

    override fun onError(error: String, errorCode: Int) {
        if (onErrorCallback != null) {
            onErrorCallback?.invoke(error, errorCode)
        } else {
            Log.e("MediaPipeProcessor", "FaceLandmarker Error ($errorCode): $error")
        }
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        onResultCallback?.accept(resultBundle)
    }

    override fun onEmpty() {
        onEmptyCallback?.invoke()
    }
}