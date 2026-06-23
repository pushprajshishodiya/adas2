package com.adas.app.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.adas.app.detection.*

class AdasOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var adasFrame: AdasFrame? = null
    var isRearViewActive = false
    var scaleX = 1f
    var scaleY = 1f

    private val paintSafe    = makeBoxPaint(Color.parseColor("#00E676"), 3f)
    private val paintCaution = makeBoxPaint(Color.parseColor("#FFD600"), 3f)
    private val paintWarning = makeBoxPaint(Color.parseColor("#FF6D00"), 4f)
    private val paintDanger  = makeBoxPaint(Color.parseColor("#FF1744"), 5f)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99050A10")
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 38f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val egoSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 60f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 20f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = adasFrame ?: return
        val vehicles = if (isRearViewActive) frame.rearDetections else frame.frontDetections
        vehicles.forEach { drawVehicleBox(canvas, it) }
        drawBottomHud(canvas, frame)
        frame.frontCollisionWarning?.let { if (it.level != WarningLevel.SAFE) drawBanner(canvas, it, true) }
        frame.rearCollisionWarning?.let  { if (it.level != WarningLevel.SAFE) drawBanner(canvas, it, false) }
    }

    private fun drawVehicleBox(canvas: Canvas, v: TrackedVehicle) {
        val l = v.boundingBox.left   * scaleX
        val t = v.boundingBox.top    * scaleY
        val r = v.boundingBox.right  * scaleX
        val b = v.boundingBox.bottom * scaleY
        val paint = boxPaintFor(v.warningLevel)
        val cs = minOf(r - l, b - t) * 0.22f

        canvas.drawLine(l, t, l+cs, t, paint);  canvas.drawLine(l, t, l, t+cs, paint)
        canvas.drawLine(r-cs, t, r, t, paint);  canvas.drawLine(r, t, r, t+cs, paint)
        canvas.drawLine(l, b-cs, l, b, paint);  canvas.drawLine(l, b, l+cs, b, paint)
        canvas.drawLine(r-cs, b, r, b, paint);  canvas.drawLine(r, b-cs, r, b, paint)

        val dist = if (v.estimatedDistanceMeters > 500f) "—" else "${"%.0f".format(v.estimatedDistanceMeters)}m"
        val spd  = if (v.relativeSpeedKmh == 0f) "" else " Δ${"%.0f".format(v.relativeSpeedKmh)}"
        val text = "${v.label.uppercase()} $dist$spd"

        labelPaint.getTextBounds(text, 0, text.length, tmpRect)
        val tw = tmpRect.width().toFloat()
        val th = tmpRect.height().toFloat()
        canvas.drawRoundRect(l-2, t-th-12, l+tw+10, t-2, 5f, 5f, labelBgPaint)
        canvas.drawText(text, l+4, t-5, labelPaint)
    }

    private fun drawBottomHud(canvas: Canvas, frame: AdasFrame) {
        val hudH = 100f
        val hudT = height - hudH - 130f
        val hL = width * 0.18f
        val hR = width * 0.82f
        canvas.drawRoundRect(hL, hudT, hR, hudT+hudH, 20f, 20f, hudBgPaint)
        canvas.drawRoundRect(hL, hudT, hR, hudT+hudH, 20f, 20f, accentStrokePaint)

        val cx = width / 2f
        canvas.drawText("${"%.0f".format(frame.egoSpeedKmh)}", cx, hudT+62f, egoSpeedPaint)
        canvas.drawText("km/h", cx, hudT+86f, smallPaint)

        frame.rearCollisionWarning?.let { w ->
            speedPaint.color = levelColor(w.level)
            canvas.drawText("▲${"%.0f".format(w.vehicle.relativeSpeedKmh)}km/h", hR-55f, hudT+52f, speedPaint)
        }
        frame.frontCollisionWarning?.let { w ->
            speedPaint.color = levelColor(w.level)
            canvas.drawText("${"%.0f".format(w.vehicle.estimatedDistanceMeters)}m", hL+55f, hudT+52f, speedPaint)
        }
    }

    private fun drawBanner(canvas: Canvas, w: CollisionWarning, front: Boolean) {
        val bH = 50f
        val bY = if (front) 0f else height.toFloat() - bH
        bannerPaint.color = levelColor(w.level)
        canvas.drawRect(0f, bY, width.toFloat(), bY+bH, bannerPaint)

        val dir = if (front) "▲ FRONT" else "▼ REAR"
        val ttc = if (w.timeToCollisionSeconds < 99f) " TTC ${"%.1f".format(w.timeToCollisionSeconds)}s" else ""
        val msg = "⚠  $dir  ${w.vehicle.label.uppercase()}  ${"%.0f".format(w.vehicle.estimatedDistanceMeters)}m$ttc"
        labelPaint.textSize = 22f
        labelPaint.color = Color.WHITE
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(msg, width/2f, bY+33f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = 28f
    }

    private fun boxPaintFor(level: WarningLevel) = when(level) {
        WarningLevel.SAFE    -> paintSafe
        WarningLevel.CAUTION -> paintCaution
        WarningLevel.WARNING -> paintWarning
        WarningLevel.DANGER  -> paintDanger
    }

    private fun levelColor(level: WarningLevel): Int = when (level) {
        WarningLevel.DANGER  -> Color.parseColor("#DDFF1744")
        WarningLevel.WARNING -> Color.parseColor("#DDFF6D00")
        WarningLevel.CAUTION -> Color.parseColor("#DDFFD600")
        WarningLevel.SAFE    -> Color.parseColor("#DD00E676")
    }

    private fun makeBoxPaint(color: Int, sw: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.strokeWidth = sw
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
    }
}
