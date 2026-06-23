package com.adas.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adas.app.detection.*
import com.adas.app.utils.GpsSpeedProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AdasViewModel(app: Application) : AndroidViewModel(app) {

    private val vehicleDetector = VehicleDetector(app)
    private val adasProcessor   = AdasProcessor(vehicleDetector)
    private val gpsProvider     = GpsSpeedProvider(app)

    private val _adasFrame = MutableStateFlow<AdasFrame?>(null)
    val adasFrame: StateFlow<AdasFrame?> = _adasFrame

    private val mutex = Mutex()
    private var latestFrontVehicles: List<TrackedVehicle>    = emptyList()
    private var latestRearVehicles:  List<TrackedVehicle>    = emptyList()
    private var latestFrontRaw:      List<DetectionResult>   = emptyList()
    private var latestFrontBitmap:   Bitmap?                 = null

    init { gpsProvider.start() }

    fun processFrontFrame(bitmap: Bitmap, ts: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val vehicles = adasProcessor.processFrame(bitmap, CameraType.FRONT, gpsProvider.speedKmh.value, ts)
            val raw = vehicleDetector.detect(bitmap)
            mutex.withLock {
                latestFrontVehicles = vehicles
                latestFrontRaw      = raw
                latestFrontBitmap   = bitmap
            }
            emit(bitmap, ts)
        }
    }

    fun processRearFrame(bitmap: Bitmap, ts: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val vehicles = adasProcessor.processFrame(bitmap, CameraType.REAR, gpsProvider.speedKmh.value, ts)
            mutex.withLock { latestRearVehicles = vehicles }
            emit(latestFrontBitmap ?: bitmap, ts)
        }
    }

    private suspend fun emit(frontBitmap: Bitmap, ts: Long) {
        val frame = mutex.withLock {
            adasProcessor.buildAdasFrame(
                frontVehicles   = latestFrontVehicles,
                rearVehicles    = latestRearVehicles,
                frontDetections = latestFrontRaw,
                frontBitmap     = frontBitmap,
                egoSpeedKmh     = gpsProvider.speedKmh.value,
                timestampMs     = ts
            )
        }
        _adasFrame.value = frame
    }

    override fun onCleared() {
        super.onCleared()
        vehicleDetector.close()
        gpsProvider.stop()
        adasProcessor.reset()
    }
}
