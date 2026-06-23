package com.adas.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCameraManager"
        private val ANALYSIS_SIZE = Size(640, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    var onFrontFrame: ((Bitmap, Long) -> Unit)? = null
    var onRearFrame: ((Bitmap, Long) -> Unit)? = null

    private var frameCountFront = 0
    private var frameCountRear = 0
    private val skipFactor = 3

    fun start(
        lifecycleOwner: LifecycleOwner,
        frontPreview: PreviewView?,
        rearPreview: PreviewView?
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameras(lifecycleOwner, frontPreview, rearPreview)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        // For RGBA_8888 format
        val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return bmp
    }

    private fun bindCameras(
        lifecycleOwner: LifecycleOwner,
        frontPreview: PreviewView?,
        rearPreview: PreviewView?
    ) {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            // Back camera = road ahead
            val backPreviewUC = frontPreview?.let {
                Preview.Builder().build().also { p -> p.setSurfaceProvider(it.surfaceProvider) }
            }
            val backAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_SIZE)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { proxy ->
                        frameCountFront++
                        if (frameCountFront % skipFactor == 0) {
                            try {
                                val bmp = imageProxyToBitmap(proxy)
                                onFrontFrame?.invoke(bmp, System.currentTimeMillis())
                            } catch (e: Exception) {
                                Log.e(TAG, "Front frame error: ${e.message}")
                            }
                        }
                        proxy.close()
                    }
                }

            val backUseCases = mutableListOf<UseCase>(backAnalysis)
            backPreviewUC?.let { backUseCases.add(it) }
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *backUseCases.toTypedArray())

            // Front camera = rear traffic — try separately, don't crash if unavailable
            try {
                val frontPreviewUC = rearPreview?.let {
                    Preview.Builder().build().also { p -> p.setSurfaceProvider(it.surfaceProvider) }
                }
                val frontAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(ANALYSIS_SIZE)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            frameCountRear++
                            if (frameCountRear % skipFactor == 0) {
                                try {
                                    val bmp = imageProxyToBitmap(proxy)
                                    onRearFrame?.invoke(bmp, System.currentTimeMillis())
                                } catch (e: Exception) {
                                    Log.e(TAG, "Rear frame error: ${e.message}")
                                }
                            }
                            proxy.close()
                        }
                    }
                val frontUseCases = mutableListOf<UseCase>(frontAnalysis)
                frontPreviewUC?.let { frontUseCases.add(it) }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, *frontUseCases.toTypedArray())
                Log.i(TAG, "Dual cameras bound")
            } catch (e: Exception) {
                Log.w(TAG, "Front camera unavailable, single-camera mode: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed: ${e.message}")
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        if (!analysisExecutor.isShutdown) analysisExecutor.shutdown()
    }
}
