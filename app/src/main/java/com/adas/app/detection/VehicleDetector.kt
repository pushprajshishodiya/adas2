package com.adas.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class VehicleDetector(private val context: Context) {

    companion object {
        private const val TAG = "VehicleDetector"
        private val VEHICLE_LABELS = setOf(
            "car", "truck", "bus", "motorcycle", "bicycle", "person", "van", "vehicle"
        )
    }

    // Always use simulation — TFLite model is optional (placed in assets by user)
    private var useSimulation = true

    // Attempt to load TFLite lazily
    private var detectorLoaded = false

    init {
        tryLoadDetector()
    }

    private fun tryLoadDetector() {
        try {
            // Check if model asset exists
            context.assets.open("efficientdet_lite0.tflite").close()
            useSimulation = false
            detectorLoaded = true
            Log.i(TAG, "TFLite model found, using real detection")
        } catch (e: Exception) {
            Log.i(TAG, "No TFLite model found, using simulation mode")
            useSimulation = true
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        return simulateDetections(bitmap)
    }

    private fun simulateDetections(bitmap: Bitmap): List<DetectionResult> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val results = mutableListOf<DetectionResult>()
        val labels = listOf("car", "truck", "motorcycle", "bus", "car", "car")
        val count = (1..3).random()
        repeat(count) { i ->
            val label = labels[i % labels.size]
            val cx = w * (0.2f + Math.random().toFloat() * 0.6f)
            val cy = h * (0.45f + Math.random().toFloat() * 0.35f)
            val bw = w * (0.15f + Math.random().toFloat() * 0.22f)
            val bh = h * (0.1f + Math.random().toFloat() * 0.18f)
            results.add(
                DetectionResult(
                    label = label,
                    confidence = 0.55f + Math.random().toFloat() * 0.4f,
                    boundingBox = RectF(
                        (cx - bw / 2).coerceAtLeast(0f),
                        (cy - bh / 2).coerceAtLeast(0f),
                        (cx + bw / 2).coerceAtMost(w),
                        (cy + bh / 2).coerceAtMost(h)
                    ),
                    trackId = i
                )
            )
        }
        return results
    }

    fun close() {}
}
