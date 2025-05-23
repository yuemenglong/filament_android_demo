package com.example.filament_android_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.cos

/**
 * 将面部特征点绘制到原始图像上，返回一个新的Bitmap
 * @param originalBitmap 原始图像
 * @param landmarkResult 面部特征点结果
 * @return 带有面部特征点的新Bitmap
 */
fun drawFaceLandmarksOnBitmap(
    originalBitmap: Bitmap?,
    landmarkResult: FaceLandmarkerResult?
): Bitmap? {
    // 如果原始图像或特征点为空，直接返回原始图像
    if (originalBitmap == null || landmarkResult == null || landmarkResult.faceLandmarks().isEmpty()) {
        return originalBitmap
    }

    // 创建一个可变的原始图像副本
    val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(resultBitmap)
    val paint = Paint().apply {
        color = android.graphics.Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    val pointPaint = Paint().apply {
        color = android.graphics.Color.RED
        strokeWidth = 6f
        style = Paint.Style.FILL
    }

    val imageWidth = originalBitmap.width
    val imageHeight = originalBitmap.height

    landmarkResult.faceLandmarks().forEach { landmarks ->
        // 绘制连接线
        com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FACE_LANDMARKS_CONNECTORS.forEach { connector ->
            val startIdx = connector.start()
            val endIdx = connector.end()
            if (startIdx >= 0 && startIdx < landmarks.size && endIdx >= 0 && endIdx < landmarks.size) {
                val start = landmarks[startIdx]
                val end = landmarks[endIdx]

                val startX = start.x() * imageWidth
                val startY = start.y() * imageHeight
                val endX = end.x() * imageWidth
                val endY = end.y() * imageHeight

                canvas.drawLine(startX, startY, endX, endY, paint)
            }
        }

        // 绘制特征点
        landmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth
            val y = landmark.y() * imageHeight
            canvas.drawCircle(x, y, 2f, pointPaint)
        }
    }

    return resultBitmap
}

/**
 * 将 3D 模型 overlay（Bitmap）绘制到原始图像上，返回一个新的Bitmap
 * @param cameraImage 原始图像
 * @param modelImage 3D模型图像
 * @param landmarkResult 面部特征点结果
 * @param overlayScaleRelativeToFace 相对于面部的比例系数
 * @return 带有模型覆盖层的新Bitmap
 */
/*TODO 修改这个函数
将3D模型覆盖在原始图像上的时候，通过将模型的中心点（绿色的那个）与面部特征点的中心点(修正后的)进行对齐，废弃之前的对齐方式
* */
fun draw3DOverlayToBitmap(
    cameraImage: Bitmap?,
    modelImage: Bitmap?,
    landmarkResult: FaceLandmarkerResult?,
    overlayScaleRelativeToFace: Float = 1.8f
): Bitmap? {
    if (cameraImage == null || modelImage == null || landmarkResult == null || landmarkResult.faceLandmarks()
            .isEmpty()
    ) {
        return cameraImage
    }

    val imageWidth = cameraImage.width
    val imageHeight = cameraImage.height

    // 创建一个可变的原始图像副本
    val resultBitmap = cameraImage.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(resultBitmap)

    // 初始化绘制点所需 Paint 对象和半径
    val pointRadius = 10f // 可以根据需要调整点的大小
    val redPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    val yellowPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    val bluePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }
    val greenPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    Log.d("YML", "---------------------------------------------------------------------------")
    val (rot, pos) = if (landmarkResult.facialTransformationMatrixes().isPresent &&
        landmarkResult.facialTransformationMatrixes().get().isNotEmpty()
    ) {
        // 掉4x4格式记录矩阵
        val matrix = landmarkResult.facialTransformationMatrixes().get()[0]
        Log.d("YML", "Transformation Matrix 4x4:")
        Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[0], matrix[1], matrix[2], matrix[3]))
        Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[4], matrix[5], matrix[6], matrix[7]))
        Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[8], matrix[9], matrix[10], matrix[11]))
        Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[12], matrix[13], matrix[14], matrix[15]))

        val transformationMatrix = landmarkResult.facialTransformationMatrixes().get()[0]
        val eulerAngles = extractEulerAngles(transformationMatrix)
        val offset = extractOffset(transformationMatrix)
        Pair(Pair(eulerAngles[0], eulerAngles[1]), Pair(offset[0], offset[1]))
    } else {
        Log.d("YML", "Facial transformation matrix not present or empty.")
        Pair(Pair(0.0f, 0.0f), Pair(0.0f, 0.0f))
    }
    val (pitch, yaw) = rot
    val (posX, posY) = pos // 这些posX, posY是归一化的，范围可能是[-1, 1]或[0,1]，取决于Matrix
    Log.d("YML", "Yaw: $yaw, Pitch: $pitch")
    Log.d("YML", "posX from matrix: $posX, posY from matrix: $posY") // 注意: posX, posY的解释可能需要根据matrix的定义来
    Log.d("YML", "imageWidth: $imageWidth, imageHeight: $imageHeight")

    val allLandmarks = landmarkResult.faceLandmarks().firstOrNull()
    if (allLandmarks != null && allLandmarks.isNotEmpty()) {
        var minXNorm = Float.MAX_VALUE
        var minYNorm = Float.MAX_VALUE
        var maxXNorm = Float.MIN_VALUE
        var maxYNorm = Float.MIN_VALUE

        allLandmarks.forEach { landmark ->
            minXNorm = minOf(minXNorm, landmark.x())
            minYNorm = minOf(minYNorm, landmark.y())
            maxXNorm = maxOf(maxXNorm, landmark.x())
            maxYNorm = maxOf(maxYNorm, landmark.y())
        }

        if (minXNorm < maxXNorm && minYNorm < maxYNorm) {
            val faceRectLeft = minXNorm * imageWidth
            val faceRectTop = minYNorm * imageHeight
            val faceRectRight = maxXNorm * imageWidth
            val faceRectBottom = maxYNorm * imageHeight

            val faceWidthOnBitmap = faceRectRight - faceRectLeft
            val faceHeightOnBitmap = faceRectBottom - faceRectTop
            // fixFaceSize 函数的实现未提供，假设它返回修正后的宽高
            val (fixedFaceWidthOnBitmap, fixedFaceHeightOnBitmap) = fixFaceSize(
                faceWidthOnBitmap.toDouble(),
                faceHeightOnBitmap.toDouble(),
                yaw.toDouble(),
                pitch.toDouble()
            )

            Log.d("YML", "Face size on bitmap: Width: $faceWidthOnBitmap, Height: $faceHeightOnBitmap")
            Log.d("YML", "Fixed face size on bitmap: Width: $fixedFaceWidthOnBitmap, Height: $fixedFaceHeightOnBitmap")

            /*这里的faceCenter指的是脸的中心点，而不是头的中心点*/
            val K_yaw_offset = 0.15f // 可根据实际效果调整
            val K_pitch_offset = 0.15f // 可根据实际效果调整
            // 注意： offsetX和offsetY的计算逻辑可能需要根据yaw和pitch的实际影响来调整
            // 当前逻辑：yaw和pitch都对X和Y产生影响
            val offsetX = (yaw * K_yaw_offset * faceWidthOnBitmap)
            val offsetY = (pitch * K_pitch_offset * faceHeightOnBitmap)
            Log.d("YML", "Calculated offset for center: offsetX: $offsetX, offsetY: $offsetY")

            val faceCenterX = faceRectLeft + faceWidthOnBitmap / 2f
            val faceCenterY = faceRectTop + faceHeightOnBitmap / 2f
            val fixedFaceCenterX = faceCenterX + offsetX
            val fixedFaceCenterY = faceCenterY + offsetY
            Log.d("YML", "Original face center (landmark center): faceCenterX: $faceCenterX, faceCenterY: $faceCenterY")
            Log.d("YML", "Fixed face center: fixedFaceCenterX: $fixedFaceCenterX, fixedFaceCenterY: $fixedFaceCenterY")

            val overlayTargetWidth = faceWidthOnBitmap * overlayScaleRelativeToFace // 使用原始宽度计算目标宽度
            val bitmapAspectRatio =
                if (modelImage.height > 0) modelImage.width.toFloat() / modelImage.height.toFloat() else 1f
            val overlayTargetHeight = overlayTargetWidth / bitmapAspectRatio

            // --- MODIFICATION START: 将3D模型的有效像素中心与修正后的面部中心对齐 ---
            // 获取3D模型图像的有效像素中心点（模型自身的局部坐标）
            val modelEffectiveCenterLocal = getEffectivePixelsCenter(modelImage)

            // 检查是否能获取到模型有效中心，以及模型和目标绘制尺寸是否有效
            if (modelEffectiveCenterLocal != null && modelImage.width > 0 && modelImage.height > 0 &&
                overlayTargetWidth > 0f && overlayTargetHeight > 0f
            ) {

                val localEffCenterX = modelEffectiveCenterLocal.first
                val localEffCenterY = modelEffectiveCenterLocal.second

                // 计算模型在画布上绘制时，从其原始尺寸到目标尺寸的缩放因子
                val scaleFactorX = overlayTargetWidth / modelImage.width.toFloat()
                val scaleFactorY = overlayTargetHeight / modelImage.height.toFloat()

                // 计算模型绘制区域 (destRect) 的左上角 (destLeft, destTop) 坐标，
                // 使得模型的有效像素中心点 (localEffCenterX, localEffCenterY) 经过缩放和平移后，
                // 正好落在画布上的 fixedFaceCenterX, fixedFaceCenterY 位置。
                // fixedFaceCenterX = destLeft + localEffCenterX * scaleFactorX
                // fixedFaceCenterY = destTop + localEffCenterY * scaleFactorY
                // 由此推导出：
                val destLeft = fixedFaceCenterX - (localEffCenterX * scaleFactorX)
                val destTop = fixedFaceCenterY - (localEffCenterY * scaleFactorY)

                Log.d(
                    "YML",
                    "New 3D model alignment: destLeft: $destLeft, destTop: $destTop. Aligning model effective center to fixed face center."
                )
                Log.d(
                    "YML_POINTS",
                    "Model local effective center: ($localEffCenterX, $localEffCenterY), Target canvas position for this point: ($fixedFaceCenterX, $fixedFaceCenterY)"
                )

                // 定义模型在画布上的目标绘制矩形区域
                val destRect = Rect(
                    destLeft.toInt(),
                    destTop.toInt(),
                    (destLeft + overlayTargetWidth).toInt(),
                    (destTop + overlayTargetHeight).toInt()
                )

                // 确保目标绘制区域的宽高有效
                if (destRect.width() > 0 && destRect.height() > 0) {
                    // 将模型图像绘制到计算出的目标矩形区域
                    canvas.drawBitmap(modelImage, null, destRect, null)
                    Log.d("YML", "3D model drawn to Rect: $destRect")

                    // 绘制模型有效像素中心点在画布上的位置 (绿色)，用于验证对齐效果
                    // 此点理论上应与 fixedFaceCenterX, fixedFaceCenterY (黄色点) 重合或非常接近
                    val mappedModelEffCenterX = destRect.left + localEffCenterX * scaleFactorX
                    val mappedModelEffCenterY = destRect.top + localEffCenterY * scaleFactorY
                    canvas.drawCircle(mappedModelEffCenterX, mappedModelEffCenterY, pointRadius, greenPaint)
                    Log.d(
                        "YML_POINTS",
                        "Drew Model's Effective Pixel Center (Green) at: ($mappedModelEffCenterX, $mappedModelEffCenterY). This should match Yellow dot."
                    )

                    // 绘制3D模型在画布上的边界框几何中心点 (蓝色)
                    // 注意：此点不一定是模型的视觉或有效像素中心
                    val modelBoundingBoxCenterX = destRect.centerX().toFloat()
                    val modelBoundingBoxCenterY = destRect.centerY().toFloat()
                    canvas.drawCircle(modelBoundingBoxCenterX, modelBoundingBoxCenterY, pointRadius, bluePaint)
                    Log.d(
                        "YML_POINTS",
                        "Drew 3D Model Bounding Box Center (Blue) at: ($modelBoundingBoxCenterX, $modelBoundingBoxCenterY)"
                    )

                } else {
                    Log.w(
                        "YML",
                        "Destination Rect for model has zero or negative width/height. Skipping model drawing. Rect: $destRect"
                    )
                }
            } else {
                // 记录无法进行模型对齐和绘制的原因
                var reason = "Unknown reason."
                if (modelEffectiveCenterLocal == null) {
                    reason = "Model's effective pixel center not found (getEffectivePixelsCenter returned null)."
                } else if (modelImage.width <= 0 || modelImage.height <= 0) {
                    reason = "Model image has zero or negative width/height."
                } else if (overlayTargetWidth <= 0f || overlayTargetHeight <= 0f) {
                    reason = "Overlay target width or height is zero or negative."
                }
                Log.w("YML", "Cannot align or draw model: $reason Skipping model drawing.")
                // 如果模型未绘制，则与模型相关的绿色和蓝色点也不会绘制。
            }
            // --- MODIFICATION END ---
            // --- 绘制标记点 ---
            // --- 标记点绘制结束 ---

        } else {
            Log.w("YML", "Calculated landmark bounds are invalid (min >= max). Skipping overlay and point drawing.")
        }
    } else {
        Log.w("YML", "No face landmarks found in the result. Skipping overlay and point drawing.")
    }
    Log.d("YML", "---------------------------------------------------------------------------")
    return resultBitmap
}

/**
 * 从4x4变换矩阵中提取欧拉角
 * @param matrix 变换矩阵
 * @return [pitch, yaw, roll] 数组
 */
private fun extractEulerAngles(matrix: FloatArray): FloatArray {
    require(matrix.size == 16) { "变换矩阵必须是4x4矩阵（16个元素）。" }

    // 提取3x3旋转矩阵的元素 (4x4矩阵的左上觓3x3部分)
    val R00 = matrix[0]
    val R01 = matrix[1]
    val R02 = matrix[2]
    val R10 = matrix[4]
    val R11 = matrix[5]
    val R12 = matrix[6]
    val R20 = matrix[8]
    val R21 = matrix[9]
    val R22 = matrix[10]

    // 计算俄仅角 (Pitch, 绕X轴旋转)
    // 公式: pitch = asin(-R12)
    val pitch = Math.asin(-R12.toDouble()).toFloat()

    // 计算偏航角 (Yaw, 绕Y轴旋转)
    // 公式: yaw = atan2(R02, R22)
    val yaw = Math.atan2(R02.toDouble(), R22.toDouble()).toFloat()

    // 计算翻滚角 (Roll, 绕Z轴旋转)
    // 公式: roll = atan2(R10, R11)
    val roll = Math.atan2(R10.toDouble(), R11.toDouble()).toFloat()

    // 返回顺序为 [pitch, yaw, roll]
    return floatArrayOf(pitch, yaw, roll)
}

/**
 * 从变换矩阵中提取偏移量
 * @param matrix 变换矩阵
 * @return [x, y, z] 偏移量数组
 */
private fun extractOffset(matrix: FloatArray): FloatArray {
    require(matrix.size == 16) { "变换矩阵必须是4x4矩阵（16个元素）。" }
    return floatArrayOf(matrix[12], matrix[13], matrix[14])
}

/**
 * 根据面部旋转角度修正面部尺寸
 * @param faceWidthOnCanvas 原始面部宽度
 * @param faceHeightOnCanvas 原始面部高度
 * @param yaw 偏航角（弧度）
 * @param pitch 俄仅角（弧度）
 * @return 修正后的宽度和高度
 */
private fun fixFaceSize(
    faceWidthOnCanvas: Double,
    faceHeightOnCanvas: Double,
    yaw: Double,          // 必须是弧度 (radians)
    pitch: Double         // 必须是弧度 (radians)
): Pair<Double, Double> {

    // 当cos(angle)小于此值时，认为角度过大，修正可能不可靠。
    // 0.1 大约对应于 acos(0.1) ≈ 1.4706 弧度 ≈ 84.26 度。
    val MIN_COS_FACTOR = 0.1

    // 1. 根据 Yaw 修正宽度
    val absYaw = abs(yaw)
    val cosYawFactor = cos(absYaw)

    val faceWidthOnCanvasFixed: Double
    if (cosYawFactor < MIN_COS_FACTOR) {
        // 如果角度过大，cosYawFactor会很小，导致修正后的宽度异常大。
        // 此处采用 MIN_COS_FACTOR 进行有限的最大修正。
        // 另一种策略可能是直接返回原始宽度或标记为不可靠。
        faceWidthOnCanvasFixed = faceWidthOnCanvas / MIN_COS_FACTOR
    } else {
        faceWidthOnCanvasFixed = faceWidthOnCanvas / cosYawFactor
    }

    // 2. 根据 Pitch 修正高度
    val absPitch = abs(pitch)
    val cosPitchFactor = cos(absPitch)

    val faceHeightOnCanvasFixed: Double
    if (cosPitchFactor < MIN_COS_FACTOR) {
        // 同理，对高度进行有限的最大修正。
        faceHeightOnCanvasFixed = faceHeightOnCanvas / MIN_COS_FACTOR
    } else {
        faceHeightOnCanvasFixed = faceHeightOnCanvas / cosPitchFactor
    }

    return Pair(faceWidthOnCanvasFixed, faceHeightOnCanvasFixed)
}

/**
 * 计算 Bitmap 中有效（非透明）像素的中心点。
 * @param bitmap 要分析的 Bitmap。
 * @return Pair&lt;Float, Float&gt;? 表示有效像素中心点的 (x, y) 坐标；如果找不到有效像素，则返回 null。
 */
private fun getEffectivePixelsCenter(bitmap: Bitmap): Pair<Float, Float>? {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) {
        return null
    }

    var sumX = 0.0
    var sumY = 0.0
    var count = 0

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixelColor = pixels[y * width + x]
            if (Color.alpha(pixelColor) > 0) { // 检查像素是否不透明
                sumX += x
                sumY += y
                count++
            }
        }
    }

    return if (count > 0) {
        Pair((sumX / count).toFloat(), (sumY / count).toFloat())
    } else {
        null // 没有找到有效像素
    }
}
