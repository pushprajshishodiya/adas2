package com.adas.app.detection

import android.graphics.RectF

/**
 * Bounding box detection result from object detector
 */
data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val trackId: Int = -1
)

/**
 * Represents a tracked vehicle with speed estimation
 */
data class TrackedVehicle(
    val id: Int,
    val label: String,
    val boundingBox: RectF,
    val confidence: Float,
    val estimatedDistanceMeters: Float,    // estimated distance in meters
    val relativeSpeedKmh: Float,           // speed relative to ego vehicle
    val absoluteSpeedKmh: Float,           // absolute speed estimate
    val isRearVehicle: Boolean,            // true = back camera
    val warningLevel: WarningLevel
)

/**
 * Side lane availability info
 */
data class LaneSpaceInfo(
    val leftSpaceFraction: Float,    // 0.0 = blocked, 1.0 = fully open
    val rightSpaceFraction: Float,
    val leftObstacleLabel: String?,
    val rightObstacleLabel: String?,
    val leftDistanceMeters: Float,   // -1 if no obstacle
    val rightDistanceMeters: Float
)

/**
 * Full ADAS frame analysis result
 */
data class AdasFrame(
    val timestamp: Long,
    val frontDetections: List<TrackedVehicle>,
    val rearDetections: List<TrackedVehicle>,
    val laneSpaceInfo: LaneSpaceInfo,
    val egoSpeedKmh: Float,              // GPS speed of our vehicle
    val frontCollisionWarning: CollisionWarning?,
    val rearCollisionWarning: CollisionWarning?,
    val laneChangeWarning: LaneChangeWarning?
)

data class CollisionWarning(
    val vehicle: TrackedVehicle,
    val timeToCollisionSeconds: Float,
    val level: WarningLevel
)

data class LaneChangeWarning(
    val side: Side,
    val blockedByLabel: String,
    val level: WarningLevel
)

enum class WarningLevel {
    SAFE,       // Green
    CAUTION,    // Yellow
    WARNING,    // Orange
    DANGER      // Red
}

enum class Side { LEFT, RIGHT }

/**
 * Speed calculation state for optical flow tracking
 */
data class SpeedEstimate(
    val kmh: Float,
    val confidence: Float,
    val method: String  // "optical_flow", "box_size_delta", "gps"
)

/**
 * Camera type identifier
 */
enum class CameraType { FRONT, REAR }
