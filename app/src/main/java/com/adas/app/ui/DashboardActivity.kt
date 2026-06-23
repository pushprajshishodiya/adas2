package com.adas.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adas.app.camera.DualCameraManager
import com.adas.app.databinding.ActivityDashboardBinding
import com.adas.app.detection.AdasFrame
import com.adas.app.detection.WarningLevel
import com.adas.app.utils.AlertManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: AdasViewModel by viewModels()
    private lateinit var cameraManager: DualCameraManager
    private lateinit var alertManager: AlertManager

    // Total height of the bar track in pixels (set after layout)
    private var barTrackHeightPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alertManager  = AlertManager(this)
        cameraManager = DualCameraManager(this)

        // Measure track height after layout pass
        binding.progressLeft.post {
            barTrackHeightPx = (binding.progressLeft.parent as View).height
        }

        setupCameras()
        observeAdasFrames()

        binding.previewRear.setOnClickListener {
            binding.adasOverlay.isRearViewActive = !binding.adasOverlay.isRearViewActive
        }
    }

    private fun setupCameras() {
        cameraManager.onFrontFrame = { bmp, ts -> viewModel.processFrontFrame(bmp, ts) }
        cameraManager.onRearFrame  = { bmp, ts -> viewModel.processRearFrame(bmp, ts) }
        cameraManager.start(this, binding.previewFront, binding.previewRear)
    }

    private fun observeAdasFrames() {
        lifecycleScope.launch {
            viewModel.adasFrame.collectLatest { frame ->
                frame ?: return@collectLatest
                runOnUiThread { updateUi(frame) }
            }
        }
    }

    private fun updateUi(frame: AdasFrame) {
        // Overlay
        binding.adasOverlay.apply {
            adasFrame = frame
            scaleX = binding.previewFront.width.toFloat() / 640f
            scaleY = binding.previewFront.height.toFloat() / 480f
            invalidate()
        }

        // Ego speed
        binding.tvEgoSpeed.text = "${frame.egoSpeedKmh.roundToInt()}"

        // Front nearest vehicle
        val frontNearest = frame.frontDetections.minByOrNull { it.estimatedDistanceMeters }
        if (frontNearest != null) {
            binding.tvFrontDist.text  = "${"%.1f".format(frontNearest.estimatedDistanceMeters)}m"
            binding.tvFrontSpeed.text = "Δ${"%.0f".format(frontNearest.relativeSpeedKmh)}km/h"
            binding.tvFrontLabel.text = frontNearest.label.uppercase()
            binding.cardFront.setCardBackgroundColor(warningColor(frontNearest.warningLevel))
        } else {
            binding.tvFrontDist.text  = "— m"
            binding.tvFrontSpeed.text = "—"
            binding.tvFrontLabel.text = "CLEAR"
            binding.cardFront.setCardBackgroundColor(warningColor(WarningLevel.SAFE))
        }

        // Rear nearest vehicle
        val rearNearest = frame.rearDetections.minByOrNull { it.estimatedDistanceMeters }
        if (rearNearest != null) {
            binding.tvRearDist.text  = "${"%.1f".format(rearNearest.estimatedDistanceMeters)}m"
            binding.tvRearSpeed.text = "Δ${"%.0f".format(rearNearest.relativeSpeedKmh)}km/h"
            binding.tvRearLabel.text = rearNearest.label.uppercase()
            binding.cardRear.setCardBackgroundColor(warningColor(rearNearest.warningLevel))
        } else {
            binding.tvRearDist.text  = "— m"
            binding.tvRearSpeed.text = "—"
            binding.tvRearLabel.text = "CLEAR"
            binding.cardRear.setCardBackgroundColor(warningColor(WarningLevel.SAFE))
        }

        // Side space bars — resize the fill View height directly
        val lane = frame.laneSpaceInfo
        val trackH = barTrackHeightPx.takeIf { it > 0 }
            ?: (binding.progressLeft.parent as? View)?.height ?: 0

        setBarFill(binding.progressLeft, lane.leftSpaceFraction,  trackH)
        setBarFill(binding.progressRight, lane.rightSpaceFraction, trackH)

        binding.tvLeftPct.text  = "${(lane.leftSpaceFraction  * 100).roundToInt()}%"
        binding.tvRightPct.text = "${(lane.rightSpaceFraction * 100).roundToInt()}%"
        binding.tvLeftDist.text  = if (lane.leftDistanceMeters  > 0) "${"%.0f".format(lane.leftDistanceMeters)}m"  else "—"
        binding.tvRightDist.text = if (lane.rightDistanceMeters > 0) "${"%.0f".format(lane.rightDistanceMeters)}m" else "—"

        // Bar colour by space level
        binding.progressLeft.setBackgroundColor(spaceColor(lane.leftSpaceFraction))
        binding.progressRight.setBackgroundColor(spaceColor(lane.rightSpaceFraction))

        // Warning banner
        val topWarn  = frame.frontCollisionWarning
        val backWarn = frame.rearCollisionWarning
        val worst = listOfNotNull(topWarn?.level, backWarn?.level).maxByOrNull { it.ordinal }

        if (worst != null && worst != WarningLevel.SAFE) {
            val isFront = topWarn != null &&
                (backWarn == null || topWarn.level.ordinal >= backWarn.level.ordinal)
            val wv  = if (isFront) topWarn!! else backWarn!!
            val dir = if (isFront) "▲ FRONT" else "▼ REAR"
            val ttc = if (wv.timeToCollisionSeconds < 99f)
                "  TTC ${"%.1f".format(wv.timeToCollisionSeconds)}s" else ""
            binding.tvWarningBanner.text =
                "⚠  $dir  ${wv.vehicle.label.uppercase()}  ${"%.0f".format(wv.vehicle.estimatedDistanceMeters)}m$ttc"
            binding.tvWarningBanner.visibility = View.VISIBLE
            binding.tvWarningBanner.setBackgroundColor(warningColor(worst))
            alertManager.alert(worst)
        } else {
            binding.tvWarningBanner.visibility = View.GONE
        }

        binding.tvRearCamLabel.text = if (rearNearest != null)
            "${rearNearest.label.uppercase()} ${"%.0f".format(rearNearest.estimatedDistanceMeters)}m"
        else "REAR CAM"
    }

    /** Resize the fill View to fraction of its parent track height */
    private fun setBarFill(fillView: View, fraction: Float, trackHeightPx: Int) {
        if (trackHeightPx <= 0) return
        val lp = fillView.layoutParams as ViewGroup.LayoutParams
        lp.height = (trackHeightPx * fraction.coerceIn(0f, 1f)).roundToInt()
        fillView.layoutParams = lp
    }

    private fun spaceColor(fraction: Float): Int = when {
        fraction > 0.6f  -> Color.parseColor("#00E676")
        fraction > 0.35f -> Color.parseColor("#FFD600")
        else             -> Color.parseColor("#FF1744")
    }

    private fun warningColor(level: WarningLevel): Int = when (level) {
        WarningLevel.DANGER  -> Color.parseColor("#CCFF1744")
        WarningLevel.WARNING -> Color.parseColor("#CCFF6D00")
        WarningLevel.CAUTION -> Color.parseColor("#CCFFD600")
        WarningLevel.SAFE    -> Color.parseColor("#CC00C853")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stop()
        alertManager.release()
    }
}
