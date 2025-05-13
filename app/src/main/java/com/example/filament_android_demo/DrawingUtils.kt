package com.example.filament_android_demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max

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