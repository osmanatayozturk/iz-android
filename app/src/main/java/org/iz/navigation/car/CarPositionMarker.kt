package org.iz.navigation.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.iz.navigation.ui.PaletteColors

/** Original map artwork. The nose is at the top. The front-view badge has no map heading. */
internal fun carPositionMarker(colors: PaletteColors, dark: Boolean, directional: Boolean, stale: Boolean = false): Bitmap {
    val height = if (directional) 64 else 44
    val bitmap = Bitmap.createBitmap(48 * 2, height * 2, Bitmap.Config.ARGB_8888).apply {
        density = DisplayMetrics.DENSITY_XHIGH
    }
    val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    val halo = colors.surface.toArgb()
    val body = if (stale) ColorUtils.blendARGB(colors.ink.toArgb(), halo, 0.36f) else colors.route.toArgb()
    val glass = if (dark) 0xff263d50.toInt() else 0xffb3dfee.toInt()
    fun fill(color: Int) { paint.style = Paint.Style.FILL; paint.color = color }
    fun outlined(path: Path, width: Float) {
        paint.color = halo
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        canvas.drawPath(path, paint)
        fill(body)
        canvas.drawPath(path, paint)
    }
    if (directional) {
        val sedan = Path().apply {
            moveTo(24f, 3f)
            cubicTo(35f, 3f, 39f, 7f, 39f, 19f)
            lineTo(39f, 24f); lineTo(43f, 26f); lineTo(43f, 30f); lineTo(39f, 29f)
            lineTo(39f, 51f); quadTo(39f, 60f, 32f, 60f)
            lineTo(16f, 60f); quadTo(9f, 60f, 9f, 51f)
            lineTo(9f, 29f); lineTo(5f, 30f); lineTo(5f, 26f); lineTo(9f, 24f)
            lineTo(9f, 19f); cubicTo(9f, 7f, 13f, 3f, 24f, 3f); close()
        }
        outlined(sedan, 4f)
        fill(glass)
        canvas.drawPath(Path().apply {
            moveTo(12f, 21f); quadTo(24f, 15f, 36f, 21f)
            lineTo(33f, 30f); quadTo(24f, 27f, 15f, 30f); close()
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(15f, 43f); quadTo(24f, 46f, 33f, 43f)
            lineTo(35f, 51f); quadTo(24f, 55f, 13f, 51f); close()
        }, paint)
        fill(Color.WHITE)
        canvas.drawRoundRect(12f, 9f, 18f, 12f, 1.5f, 1.5f, paint)
        canvas.drawRoundRect(30f, 9f, 36f, 12f, 1.5f, 1.5f, paint)
        fill(0xffff7e66.toInt())
        canvas.drawRoundRect(11f, 53f, 15f, 57f, 1f, 1f, paint)
        canvas.drawRoundRect(33f, 53f, 37f, 57f, 1f, 1f, paint)
    } else {
        fill(halo)
        canvas.drawCircle(24f, 22f, 21f, paint)
        fill(body)
        canvas.drawCircle(24f, 22f, 18.5f, paint)
        fill(halo)
        canvas.drawPath(Path().apply {
            moveTo(11f, 23f); lineTo(15f, 13f); quadTo(16f, 11f, 19f, 11f)
            lineTo(29f, 11f); quadTo(32f, 11f, 33f, 13f); lineTo(37f, 23f)
            lineTo(37f, 30f); lineTo(33f, 30f); lineTo(33f, 33f); lineTo(29f, 33f)
            lineTo(29f, 30f); lineTo(19f, 30f); lineTo(19f, 33f); lineTo(15f, 33f)
            lineTo(15f, 30f); lineTo(11f, 30f); close()
        }, paint)
        fill(glass)
        canvas.drawPath(Path().apply {
            moveTo(15f, 21f); lineTo(18f, 14f); lineTo(30f, 14f); lineTo(33f, 21f); close()
        }, paint)
        fill(body)
        canvas.drawCircle(16f, 25.5f, 1.8f, paint)
        canvas.drawCircle(32f, 25.5f, 1.8f, paint)
    }
    return bitmap
}
