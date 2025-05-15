package com.example.filament_android_demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

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
 * @param originalBitmap 原始图像
 * @param modelImage 3D模型图像
 * @param landmarkResult 面部特征点结果
 * @param overlayScaleRelativeToFace 相对于面部的比例系数
 * @return 带有模型覆盖层的新Bitmap
 */
fun draw3DOverlayToBitmap(
    originalBitmap: Bitmap?,
    modelImage: Bitmap?,
    landmarkResult: FaceLandmarkerResult?,
    overlayScaleRelativeToFace: Float = 1.8f
): Bitmap? {
    if (originalBitmap == null || modelImage == null || landmarkResult == null || landmarkResult.faceLandmarks().isEmpty()) {
        return originalBitmap
    }
    
    val imageWidth = originalBitmap.width
    val imageHeight = originalBitmap.height
    
    // 创建一个可变的原始图像副本
    val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(resultBitmap)
    
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
        Pair(Pair(0.0f, 0.0f), Pair(0.0f, 0.0f))
    }
    val (pitch, yaw) = rot
    val (posX, posY) = pos
    Log.d("YML", "Yaw: $yaw, Pitch: $pitch")
    Log.d("YML", "posX: $posX, posY: $posY")
    
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
            Log.d("YML", "minXNorm: $minXNorm, minYNorm: $minYNorm")
            Log.d("YML", "maxXNorm: $maxXNorm, maxYNorm: $maxYNorm")
            
            val faceRectLeft = minXNorm * imageWidth
            val faceRectTop = minYNorm * imageHeight
            val faceRectRight = maxXNorm * imageWidth
            val faceRectBottom = maxYNorm * imageHeight
            Log.d("YML", "faceRectLeft: $faceRectLeft, faceRectRight: $faceRectRight")
            Log.d("YML", "faceRectTop: $faceRectTop, faceRectBottom: $faceRectBottom")

            val faceWidthOnBitmap = faceRectRight - faceRectLeft
            val faceHeightOnBitmap = faceRectBottom - faceRectTop
            val (fixedFaceWidthOnBitmap, fixedFaceHeightOnBitmap) = fixFaceSize(
                faceWidthOnBitmap.toDouble(),
                faceHeightOnBitmap.toDouble(),
                yaw.toDouble(),
                pitch.toDouble()
            )

            Log.d("YML", "faceWidthOnBitmap: $faceWidthOnBitmap, faceHeightOnBitmap: $faceHeightOnBitmap")
            Log.d("YML", "fixedFaceWidthOnBitmap: $fixedFaceWidthOnBitmap, fixedFaceHeightOnBitmap: $fixedFaceHeightOnBitmap")

            /*这里的faceCenter指的是脸的中心点，而不是头的中心点*/

            // 横向位置补偶参数
            val K_yaw_offset = 0.20f // 可根据实际效果调整
            val offsetX = yaw * K_yaw_offset * faceWidthOnBitmap

            // 纵向位置补偶参数
            val K_pitch_offset = 0.20f // 可根据实际效果调整
            val offsetY = pitch * K_pitch_offset * faceHeightOnBitmap
            Log.d("YML", "offsetX: $offsetX, offsetY: $offsetY")

            val faceCenterX = faceRectLeft + faceWidthOnBitmap / 2f
            val faceCenterY = faceRectTop + faceHeightOnBitmap / 2f
            val fixedFaceCenterX = faceCenterX + offsetX
            val fixedFaceCenterY = faceCenterY + offsetY
            Log.d("YML", "faceCenterX: $faceCenterX, faceCenterY: $faceCenterY")
            Log.d("YML", "fixedFaceCenterX: $fixedFaceCenterX, fixedFaceCenterY: $fixedFaceCenterY")

            val overlayTargetWidth = faceWidthOnBitmap * overlayScaleRelativeToFace
            val bitmapAspectRatio =
                if (modelImage.height > 0) modelImage.width.toFloat() / modelImage.height.toFloat() else 1f
            val overlayTargetHeight = overlayTargetWidth / bitmapAspectRatio
            Log.d("YML", "overlayTargetWidth: $overlayTargetWidth, overlayTargetHeight: $overlayTargetHeight")

            // 应用横向和纵向补偶
            val destLeft = (fixedFaceCenterX - overlayTargetWidth / 2f).toInt()
            val destTop = (fixedFaceCenterY - overlayTargetHeight / 2f).toInt()
            Log.d("YML", "destLeft: $destLeft, destTop: $destTop")
            
            // 绘制模型到原始图像上
            val destRect = Rect(
                destLeft,
                destTop,
                (destLeft + overlayTargetWidth).toInt(),
                (destTop + overlayTargetHeight).toInt()
            )
            
            canvas.drawBitmap(modelImage, null, destRect, null)
        }
    }
    
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
