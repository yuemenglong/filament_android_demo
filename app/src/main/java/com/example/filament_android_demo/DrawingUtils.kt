package com.example.filament_android_demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos

fun DrawScope.drawFaceLandmarksToCanvas(
  landmarkResult: FaceLandmarkerResult?,
  imageWidth: Int,
  imageHeight: Int
) {
  val canvasWidth = this.size.width
  val canvasHeight = this.size.height

  if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
    return
  }

  val scaleFactor = max(canvasWidth / imageWidth, canvasHeight / imageHeight)
  val scaledImageWidth = imageWidth * scaleFactor
  val scaledImageHeight = imageHeight * scaleFactor
  val offsetX = (canvasWidth - scaledImageWidth) / 2f
  val offsetY = (canvasHeight - scaledImageHeight) / 2f

  landmarkResult?.let { result ->
    result.faceLandmarks().forEach { landmarks ->
      // 绘制连接线
      com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FACE_LANDMARKS_CONNECTORS.forEach { connector ->
        val startIdx = connector.start()
        val endIdx = connector.end()
        if (startIdx >= 0 && startIdx < landmarks.size && endIdx >= 0 && endIdx < landmarks.size) {
          val start = landmarks[startIdx]
          val end = landmarks[endIdx]

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

      // 绘制关键点
      landmarks.forEach { landmark ->
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

/**
 * 这是一个用于从4x4变换矩阵中提取欧拉角的辅助函数。
 * 该函数假定MediaPipe的脸部姿态通常使用YXZ（先Y轴偏航，后X轴俯仰，最后Z轴滚动）欧拉角顺序。
 * 返回的数组顺序为 [俯仰角 (pitch), 偏航角 (yaw), 翻滚角 (roll)]，单位为弧度。
 *
 * 注意：在俯仰角接近 +/- 90度时（万向锁），偏航角和翻滚角会变得耦合，
 * 此时计算可能不完全精确，但对于大多数常见的面部姿态追踪场景来说是足够的。
 *
 * @param matrix 包含4x4变换矩阵的16个浮点数数组（行主序）。
 * @return 包含俯仰、偏航和翻滚角的浮点数数组，顺序为 [pitch, yaw, roll]。
 */
private fun extractEulerAngles(matrix: FloatArray): FloatArray {
  require(matrix.size == 16) { "变换矩阵必须是4x4矩阵（16个元素）。" }

  // 提取3x3旋转矩阵的元素 (4x4矩阵的左上角3x3部分)
  val R00 = matrix[0]
  val R01 = matrix[1]
  val R02 = matrix[2]
  val R10 = matrix[4]
  val R11 = matrix[5]
  val R12 = matrix[6]
  val R20 = matrix[8]
  val R21 = matrix[9]
  val R22 = matrix[10]

  // 计算俯仰角 (Pitch, 绕X轴旋转)
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

private fun extractOffset(matrix: FloatArray): FloatArray {
  require(matrix.size == 16) { "变换矩阵必须是4x4矩阵（16个元素）。" }
  return floatArrayOf(matrix[12], matrix[13], matrix[14])
}

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
    // 调试时可以取消注释以下行:
    // println("警告: Yaw角度 (${Math.toDegrees(absYaw).toInt()} 度) 过大，宽度修正可能被限制。cosYawFactor: $cosYawFactor")
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
    // 调试时可以取消注释以下行:
    // println("警告: Pitch角度 (${Math.toDegrees(absPitch).toInt()} 度) 过大，高度修正可能被限制。cosPitchFactor: $cosPitchFactor")
  } else {
    faceHeightOnCanvasFixed = faceHeightOnCanvas / cosPitchFactor
  }

  return Pair(faceWidthOnCanvasFixed, faceHeightOnCanvasFixed)
  // 或者: return FixedFaceDimensions(faceWidthOnCanvasFixed, faceHeightOnCanvasFixed)
}

/**
 * 在 Canvas 上根据人脸关键点绘制 3D 模型 overlay（Bitmap）。
 */
fun DrawScope.draw3DOverlayToCanvas(
  modelImange: Bitmap?,
  landmarkResult: FaceLandmarkerResult?,
  cameraInageWidth: Int,
  cameraImageHeight: Int,
  overlayScaleRelativeToFace: Float = 1.8f
) {
  if (modelImange == null || landmarkResult == null || landmarkResult.faceLandmarks().isEmpty()) {
    return
  }

  val canvasWidth = this.size.width
  val canvasHeight = this.size.height

  if (cameraInageWidth <= 0 || cameraImageHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
    return
  }

  Log.d("YML", "---------------------------------------------------------------------------")
  val (rot, offset) = if (landmarkResult.facialTransformationMatrixes().isPresent &&
    landmarkResult.facialTransformationMatrixes().get().isNotEmpty()
  ) {
    val transformationMatrix = landmarkResult.facialTransformationMatrixes().get()[0]
    val eulerAngles = extractEulerAngles(transformationMatrix)
    val offset = extractOffset(transformationMatrix)
    Pair(Pair(eulerAngles[0], eulerAngles[1]), Pair(offset[0], offset[1]))
  } else {
    Pair(Pair(0.0f, 0.0f), Pair(0.0f, 0.0f))
  }
  val (pitch, yaw) = rot
  val (offsetX, offsetY) = offset
  Log.d("YML", "Yaw: $yaw, Pitch: $pitch, OffsetX: $offsetX, OffsetY: $offsetY")
  // 记录4x4变换矩阵
  if (landmarkResult.facialTransformationMatrixes().isPresent &&
    landmarkResult.facialTransformationMatrixes().get().isNotEmpty()
  ) {
    val matrix = landmarkResult.facialTransformationMatrixes().get()[0]
    // 按4x4格式记录矩阵
    Log.d("YML", "Transformation Matrix 4x4:")
    Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[0], matrix[1], matrix[2], matrix[3]))
    Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[4], matrix[5], matrix[6], matrix[7]))
    Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[8], matrix[9], matrix[10], matrix[11]))
    Log.d("YML", String.format("[%.4f, %.4f, %.4f, %.4f]", matrix[12], matrix[13], matrix[14], matrix[15]))
  }


  val scaleFactor = max(canvasWidth / cameraInageWidth, canvasHeight / cameraImageHeight)
  Log.d("YML", "cameraInageWidth: $cameraInageWidth, cameraImageHeight: $cameraImageHeight")
  Log.d("YML", "canvasWidth: $canvasWidth, canvasHeight: $canvasHeight")
  Log.d("YML", "scaleFactor: $scaleFactor")
  val scaledImageWidth = cameraInageWidth * scaleFactor
  val scaledImageHeight = cameraImageHeight * scaleFactor
  val canvasOffsetX = (canvasWidth - scaledImageWidth) / 2f
  val canvasOffsetY = (canvasHeight - scaledImageHeight) / 2f
  Log.d("YML", "scaledImageWidth: $scaledImageWidth, scaledImageHeight: $scaledImageHeight")
  Log.d("YML", "canvasOffsetX: $canvasOffsetX, canvasOffsetY: $canvasOffsetY")

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

      val faceRectLeft = minXNorm * scaledImageWidth + canvasOffsetX
      val faceRectTop = minYNorm * scaledImageHeight + canvasOffsetY
      val faceRectRight = maxXNorm * scaledImageWidth + canvasOffsetX
      val faceRectBottom = maxYNorm * scaledImageHeight + canvasOffsetY
      Log.d("YML", "faceRectLeft: $faceRectLeft, faceRectRight: $faceRectRight")
      Log.d("YML", "faceRectTop: $faceRectTop, faceRectBottom: $faceRectBottom")

      val faceWidthOnCanvas = faceRectRight - faceRectLeft
      val faceHeightOnCanvas = faceRectBottom - faceRectTop
      val (fixedFaceWidthOnCanvas, fixedFaceHeightOnCanvas) = fixFaceSize(
        faceWidthOnCanvas.toDouble(),
        faceHeightOnCanvas.toDouble(),
        yaw.toDouble(),
        pitch.toDouble()
      )
      val faceCenterX = faceRectLeft + faceWidthOnCanvas / 2f
      val faceCenterY = faceRectTop + faceHeightOnCanvas / 2f
      Log.d("YML", "faceWidthOnCanvas: $faceWidthOnCanvas, faceHeightOnCanvas: $faceHeightOnCanvas")
      Log.d("YML", "fixedFaceWidthOnCanvas: $fixedFaceWidthOnCanvas, fixedFaceHeightOnCanvas: $fixedFaceHeightOnCanvas")
      Log.d("YML", "faceCenterX: $faceCenterX, faceCenterY: $faceCenterY")
      /*这里的faceCenter指的是脸的中心点，而不是头的中心点*/


      // 横向位置补偿参数
      val K_yaw_offset = 0.20f // 可根据实际效果调整
      val horizontalOffset = yaw * K_yaw_offset * faceWidthOnCanvas
//      val horizontalOffset = offsetX * 0.1 * faceWidthOnCanvas

      // 纵向位置补偿参数
      val K_pitch_offset = 0.20f // 可根据实际效果调整
      val verticalOffset = pitch * K_pitch_offset * faceHeightOnCanvas
      Log.d("YML", "horizontalOffset: $horizontalOffset, verticalOffset: $verticalOffset")

      val overlayTargetWidth = faceWidthOnCanvas * overlayScaleRelativeToFace
      val bitmapAspectRatio =
        if (modelImange.height > 0) modelImange.width.toFloat() / modelImange.height.toFloat() else 1f
      val overlayTargetHeight = overlayTargetWidth / bitmapAspectRatio
      Log.d("YML", "overlayTargetWidth: $overlayTargetWidth, overlayTargetHeight: $overlayTargetHeight")

      // 应用横向和纵向补偿
      val destLeftRaw = (faceCenterX - overlayTargetWidth / 2f).toInt()
      val destTopRaw = (faceCenterY - overlayTargetHeight / 2f).toInt()
      val destLeft = (faceCenterX - overlayTargetWidth / 2f + horizontalOffset).toInt()
      val destTop = (faceCenterY - overlayTargetHeight / 2f + verticalOffset).toInt()
      Log.d("YML", "destLeftRaw: $destLeftRaw, destLeft: $destLeft")
      Log.d("YML", "destTopRaw: $destTopRaw, destTop: $destTop")

      val imageBitmapToDraw = modelImange.asImageBitmap()

      drawImage(
        image = imageBitmapToDraw,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(modelImange.width, modelImange.height),
        dstOffset = IntOffset(destLeft, destTop),
        dstSize = IntSize(
          overlayTargetWidth.toInt(),
          overlayTargetHeight.toInt()
        )
      )
    }
  }
}
