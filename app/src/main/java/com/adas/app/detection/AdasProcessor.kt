package com.adas.app.detection

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Core ADAS processor. Takes camera frames and GPS speed,
 * returns a complete AdasFrame with all detections and warnings.
 */
class AdasProcessor(private val vehicleDetector: VehicleDetector) {

    private val frontSpeedEstimator = SpeedEstimator()
    private val rearSpeedEstimator = SpeedEstimator()
    
    // Simple IoU-based tracker state
    private var frontTrackCounter = 0
    private var rearTrackCounter = 0
    private val frontTracks = mutableMapOf<Int, DetectionResult>()
    private val rearTracks = mutableMapOf<Int, DetectionResult>()

    /**
     * Process a single frame from front or rear camera.
     * egoSpeedKmh: GPS speed of our own vehicle.
     */
    fun processFrame(
        bitmap: Bitmap,
        cameraType: CameraType,
        egoSpeedKmh: Float,
        timestampMs: Long
    ): List<TrackedVehicle> {
        val detections = vehicleDetector.detect(bitmap)
        val estimator = if (cameraType == CameraType.FRONT) frontSpeedEstimator else rearSpeedEstimator

        // Assign track IDs using simple IoU matching
        val trackedDetections = assignTrackIds(detections, cameraType)

        return trackedDetections.map { det ->
            val (distM, relSpeedKmh) = estimator.update(
                trackId = det.trackId,
                label = det.label,
                box = det.boundingBox,
                imageWidthPx = bitmap.width,
                timestampMs = timestampMs
            )

            val absSpeed = (egoSpeedKmh + if (cameraType == CameraType.FRONT) -relSpeedKmh else relSpeedKmh)
                .coerceAtLeast(0f)

            val ttc = estimator.estimateTTC(distM, relSpeedKmh)
            val warningLevel = estimator.getWarningLevel(distM, ttc)

            TrackedVehicle(
                id = det.trackId,
                label = det.label,
                boundingBox = det.boundingBox,
                confidence = det.confidence,
                estimatedDistanceMeters = distM,
                relativeSpeedKmh = relSpeedKmh,
                absoluteSpeedKmh = absSpeed,
                isRearVehicle = cameraType == CameraType.REAR,
                warningLevel = warningLevel
            )
        }
    }

    /**
     * Build full ADAS analysis combining front + rear frame data.
     */
    fun buildAdasFrame(
        frontVehicles: List<TrackedVehicle>,
        rearVehicles: List<TrackedVehicle>,
        frontDetections: List<DetectionResult>,
        frontBitmap: Bitmap,
        egoSpeedKmh: Float,
        timestampMs: Long
    ): AdasFrame {

        val laneSpaceInfo = frontSpeedEstimator.analyzeSideSpace(
            frontDetections,
            frontBitmap.width,
            frontBitmap.height
        )

        // Front collision warning: closest approaching front vehicle
        val frontWarning = frontVehicles
            .filter { it.warningLevel != WarningLevel.SAFE && it.relativeSpeedKmh > 0 }
            .minByOrNull { it.estimatedDistanceMeters }
            ?.let { v ->
                val ttc = frontSpeedEstimator.estimateTTC(v.estimatedDistanceMeters, v.relativeSpeedKmh)
                CollisionWarning(v, ttc, v.warningLevel)
            }

        // Rear collision warning: vehicle approaching fast from behind
        val rearWarning = rearVehicles
            .filter { it.warningLevel != WarningLevel.SAFE && it.relativeSpeedKmh > 5f }
            .minByOrNull { it.estimatedDistanceMeters }
            ?.let { v ->
                val ttc = rearSpeedEstimator.estimateTTC(v.estimatedDistanceMeters, v.relativeSpeedKmh)
                CollisionWarning(v, ttc, v.warningLevel)
            }

        // Lane change warning
        val laneWarning = when {
            laneSpaceInfo.leftSpaceFraction < 0.3f ->
                LaneChangeWarning(
                    Side.LEFT,
                    laneSpaceInfo.leftObstacleLabel ?: "obstacle",
                    WarningLevel.WARNING
                )
            laneSpaceInfo.rightSpaceFraction < 0.3f ->
                LaneChangeWarning(
                    Side.RIGHT,
                    laneSpaceInfo.rightObstacleLabel ?: "obstacle",
                    WarningLevel.WARNING
                )
            else -> null
        }

        return AdasFrame(
            timestamp = timestampMs,
            frontDetections = frontVehicles,
            rearDetections = rearVehicles,
            laneSpaceInfo = laneSpaceInfo,
            egoSpeedKmh = egoSpeedKmh,
            frontCollisionWarning = frontWarning,
            rearCollisionWarning = rearWarning,
            laneChangeWarning = laneWarning
        )
    }

    // ──────────────────────────────────────────────
    // Simple IoU tracker
    // ──────────────────────────────────────────────

    private fun assignTrackIds(
        detections: List<DetectionResult>,
        cameraType: CameraType
    ): List<DetectionResult> {
        val tracks = if (cameraType == CameraType.FRONT) frontTracks else rearTracks
        val result = mutableListOf<DetectionResult>()
        val usedTracks = mutableSetOf<Int>()

        for (det in detections) {
            var bestId = -1
            var bestIou = 0.35f  // min IoU threshold for match

            for ((id, prev) in tracks) {
                if (id in usedTracks) continue
                if (prev.label != det.label) continue
                val iou = computeIou(det.boundingBox, prev.boundingBox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestId = id
                }
            }

            if (bestId == -1) {
                // New track
                bestId = if (cameraType == CameraType.FRONT) {
                    frontTrackCounter++; frontTrackCounter
                } else {
                    rearTrackCounter++; rearTrackCounter
                }
            }

            usedTracks.add(bestId)
            tracks[bestId] = det
            result.add(det.copy(trackId = bestId))
        }

        // Prune lost tracks
        tracks.keys.retainAll(usedTracks)

        return result
    }

    private fun computeIou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val intersection = (interRight - interLeft) * (interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val union = aArea + bArea - intersection

        return if (union <= 0f) 0f else intersection / union
    }

    fun reset() {
        frontTracks.clear()
        rearTracks.clear()
        frontSpeedEstimator.clearAll()
        rearSpeedEstimator.clearAll()
    }
}
