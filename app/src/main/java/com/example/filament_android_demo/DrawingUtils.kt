package com.example.filament_android_demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

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
 * 在 Canvas 上根据人脸关键点绘制 3D 模型 overlay（Bitmap）。
 */
fun DrawScope.draw3DOverlayToCanvas(
  overlayBitmap: Bitmap?,
  landmarkResult: FaceLandmarkerResult?,
  imageWidth: Int,
  imageHeight: Int,
  overlayScaleRelativeToFace: Float = 1.8f
) {
  if (overlayBitmap == null || landmarkResult == null || landmarkResult.faceLandmarks().isEmpty()) {
    return
  }

  val canvasWidth = this.size.width
  val canvasHeight = this.size.height

  if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
    return
  }

  val scaleFactor = max(canvasWidth / imageWidth, canvasHeight / imageHeight)
  val scaledImageWidth = imageWidth * scaleFactor
  val scaledImageHeight = imageHeight * scaleFactor
  val canvasOffsetX = (canvasWidth - scaledImageWidth) / 2f
  val canvasOffsetY = (canvasHeight - scaledImageHeight) / 2f

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
      val faceRectLeft = minXNorm * scaledImageWidth + canvasOffsetX
      val faceRectTop = minYNorm * scaledImageHeight + canvasOffsetY
      val faceRectRight = maxXNorm * scaledImageWidth + canvasOffsetX
      val faceRectBottom = maxYNorm * scaledImageHeight + canvasOffsetY

      val faceWidthOnCanvas = faceRectRight - faceRectLeft
      val faceCenterX = faceRectLeft + faceWidthOnCanvas / 2f
      val faceCenterY = faceRectTop + (faceRectBottom - faceRectTop) / 2f

      val overlayTargetWidth = faceWidthOnCanvas * overlayScaleRelativeToFace
      val bitmapAspectRatio =
        if (overlayBitmap.height > 0) overlayBitmap.width.toFloat() / overlayBitmap.height.toFloat() else 1f
      val overlayTargetHeight = overlayTargetWidth / bitmapAspectRatio

      val destLeft = (faceCenterX - overlayTargetWidth / 2f).toInt()
      val destTop = (faceCenterY - overlayTargetHeight / 2f).toInt()

      val imageBitmapToDraw = overlayBitmap.asImageBitmap()

      drawImage(
        image = imageBitmapToDraw,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(overlayBitmap.width, overlayBitmap.height),
        dstOffset = IntOffset(destLeft, destTop),
        dstSize = IntSize(
          overlayTargetWidth.toInt(),
          overlayTargetHeight.toInt()
        )
      )
    }
  }
}