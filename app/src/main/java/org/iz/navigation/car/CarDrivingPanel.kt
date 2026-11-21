package org.iz.navigation.car

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.navigation.model.Maneuver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.speed.roadSpeedPresentation
import org.iz.navigation.ui.PaletteColors
import org.iz.navigation.ui.formatDistance
import kotlin.math.roundToInt

/** Drive-related content drawn inside the intersection of the host's stable and visible areas. */
internal class CarDrivingPanel(context: Context) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private val turn = label(24f, bold = true)
    private var drawnGlyph: CarTurnGlyph? = null
    private val speeds = LinearLayout(context)
    private val ownSpeed = label(24f)
    private val limit = label(24f)
    private val source = label(14f)
    var showsTurn = false
        private set
    val reservedTop get() = if (visibility == View.VISIBLE) dp(8) + measuredHeight else 0

    init {
        orientation = VERTICAL
        visibility = View.GONE
        setPadding(dp(10), dp(6), dp(10), dp(6))
        turn.setPadding(0, 0, 0, dp(4))
        turn.compoundDrawablePadding = dp(8)
        addView(turn)
        speeds.addView(ownSpeed)
        speeds.addView(limit, LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        addView(speeds)
        source.setPadding(0, dp(2), 0, 0)
        addView(source)
    }

    fun setColors(colors: PaletteColors) {
        listOf(turn, ownSpeed, limit, source).forEach { it.setTextColor(colors.ink.toArgb()) }
        turn.compoundDrawableTintList = ColorStateList.valueOf(colors.ink.toArgb())
        background = GradientDrawable().apply {
            setColor(colors.surface.toArgb())
            cornerRadius = dp(8).toFloat()
        }
    }

    fun clear() {
        visibility = View.GONE
        showsTurn = false
    }

    fun update(state: NavigationState, warning: String?, safe: Rect, bottomInset: Int) {
        if (!state.roadSpeed.visible || safe.isEmpty) {
            clear()
            return
        }
        val progressValid = carCanPresentProgress(state)
        val speed = when {
            state.gpsStale || state.fix == null -> RoadSpeedState()
            state.simulation || state.paused || state.loading || state.arrived ||
                state.guidance && !progressValid -> state.roadSpeed.copy(limit = null)
            else -> state.roadSpeed
        }
        val values = roadSpeedPresentation(speed)
        ownSpeed.setMetric("Hızım", "${values.ownText} km/sa")
        limit.setMetric("Hız sınırı", values.limitText + if (speed.limit?.valueKmh != null) " km/sa" else "")
        source.setValue(values.sourceText)
        val margin = dp(8)
        val width = minOf(safe.width() - margin * 2, dp(344))
        // Reserve room for the position marker and attribution below the panel.
        val height = safe.height() - margin * 2 - bottomInset - dp(56)
        if (width <= 0 || height <= 0) {
            clear()
            return
        }
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val maneuver = state.progress?.takeIf { progressValid }?.let { state.route?.maneuvers?.get(it.maneuverIndex) }
        val glyph = maneuver?.let { carTurnGlyph(it.type) }
        if (maneuver != null && glyph != null) {
            val progress = checkNotNull(state.progress)
            val cue = carManeuverCue(maneuver.turnInstruction(), state.simulation, warning)
            turn.setValue("${formatDistance(progress.nextManeuverDistanceMeters)} · $cue")
            if (drawnGlyph != glyph) {
                turn.setCompoundDrawables(glyph.drawable(context, dp(40)), null, null, null)
                drawnGlyph = glyph
            }
            turn.visibility = View.VISIBLE
            measure(widthSpec, heightSpec)
            showsTurn = turn.lineCount <= 3 && measuredHeight <= height
        } else showsTurn = false
        if (!showsTurn) {
            turn.visibility = View.GONE
            measure(widthSpec, heightSpec)
        }
        // A very narrow host can leave less than one complete speed row.
        if (measuredHeight > height || ownSpeed.lineCount != 2 || limit.lineCount != 2 ||
            ownSpeed.measuredWidth + dp(12) + limit.measuredWidth > measuredWidth - paddingLeft - paddingRight) {
            clear()
            return
        }
        val params = layoutParams as FrameLayout.LayoutParams
        val left = safe.left + margin
        val top = safe.top + margin
        if (params.width != measuredWidth || params.leftMargin != left || params.topMargin != top) {
            params.width = measuredWidth
            params.leftMargin = left
            params.topMargin = top
            layoutParams = params
        }
        visibility = View.VISIBLE
    }

    private fun TextView.setValue(value: String) {
        if (text.toString() != value) text = value
    }

    private fun TextView.setMetric(name: String, value: String) {
        val metric = "$name\n$value"
        if (text.toString() != metric) text = SpannableString(metric).apply {
            setSpan(RelativeSizeSpan(0.65f), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

internal enum class CarTurnShape(val path: String) {
    STRAIGHT("M16,28 V5 M9,12 L16,5 L23,12"),
    SLIGHT("M9,28 V21 Q9,17 12,14 L24,5 M15,5 H24 V14"),
    TURN("M8,28 V16 Q8,10 14,10 H27 M20,3 L27,10 L20,17"),
    SHARP("M8,28 V13 Q8,4 15,10 L26,21 M26,12 V21 H17"),
    U_TURN("M7,28 V13 C7,3 24,3 24,13 V25 M18,19 L24,25 L30,19"),
    FORK("M9,28 V19 Q9,16 12,13 L24,5 M16,5 H24 V13");
}

internal data class CarTurnGlyph(val shape: CarTurnShape, val left: Boolean = false) {
    fun drawable(context: Context, size: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply {
            scale(size / 32f, size / 32f)
            if (left) scale(-1f, 1f, 16f, 16f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (shape == CarTurnShape.FORK) {
            paint.alpha = 130
            canvas.drawLine(9f, 20f, 9f, 5f, paint)
            paint.alpha = 255
        }
        canvas.drawPath(checkNotNull(PathParser.createPathFromPathData(shape.path)), paint)
        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, size, size) }
    }
}

/** Host routing retains its own diagram for specialized moves (e.g. roundabouts, ramps and merges). */
internal fun carTurnGlyph(type: Int): CarTurnGlyph? = when (carManeuverType(type)) {
    Maneuver.TYPE_STRAIGHT -> CarTurnGlyph(CarTurnShape.STRAIGHT)
    Maneuver.TYPE_TURN_SLIGHT_RIGHT -> CarTurnGlyph(CarTurnShape.SLIGHT)
    Maneuver.TYPE_TURN_SLIGHT_LEFT -> CarTurnGlyph(CarTurnShape.SLIGHT, left = true)
    Maneuver.TYPE_TURN_NORMAL_RIGHT -> CarTurnGlyph(CarTurnShape.TURN)
    Maneuver.TYPE_TURN_NORMAL_LEFT -> CarTurnGlyph(CarTurnShape.TURN, left = true)
    Maneuver.TYPE_TURN_SHARP_RIGHT -> CarTurnGlyph(CarTurnShape.SHARP)
    Maneuver.TYPE_TURN_SHARP_LEFT -> CarTurnGlyph(CarTurnShape.SHARP, left = true)
    Maneuver.TYPE_U_TURN_RIGHT -> CarTurnGlyph(CarTurnShape.U_TURN)
    Maneuver.TYPE_U_TURN_LEFT -> CarTurnGlyph(CarTurnShape.U_TURN, left = true)
    Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> CarTurnGlyph(CarTurnShape.FORK)
    Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> CarTurnGlyph(CarTurnShape.FORK, left = true)
    else -> null
}
