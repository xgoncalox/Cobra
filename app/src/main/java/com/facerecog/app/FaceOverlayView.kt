package com.facerecog.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

enum class FaceStatus { RECOGNIZED, UNKNOWN, VERIFYING }

data class OverlayFace(val box: RectF, val label: String, val status: FaceStatus)

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faces: List<OverlayFace> = emptyList()
    private val previousLabels = HashMap<Int, String>()
    private val popProgress = HashMap<Int, Float>()

    private val knownColor = ContextCompat.getColor(context, R.color.recognized)
    private val knownGlow = ContextCompat.getColor(context, R.color.recognized_glow)
    private val unknownColor = ContextCompat.getColor(context, R.color.unknown)
    private val unknownGlow = ContextCompat.getColor(context, R.color.unknown_glow)
    private val verifyingColor = ContextCompat.getColor(context, R.color.scan_hint)
    private val verifyingGlow = ContextCompat.getColor(context, R.color.scan_hint)

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val glowPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var pulsePhase = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setFaces(newFaces: List<OverlayFace>) {
        newFaces.forEachIndexed { index, face ->
            val prev = previousLabels[index]
            if (prev != null && prev != face.label) {
                animatePop(index)
            }
            previousLabels[index] = face.label
        }
        faces = newFaces
        invalidate()
    }

    private fun animatePop(index: Int) {
        val anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 500
            addUpdateListener {
                popProgress[index] = it.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        faces.forEachIndexed { index, face ->
            val (color, glow) = when (face.status) {
                FaceStatus.RECOGNIZED -> knownColor to knownGlow
                FaceStatus.UNKNOWN -> unknownColor to unknownGlow
                FaceStatus.VERIFYING -> verifyingColor to verifyingGlow
            }
            val pop = popProgress[index] ?: 0f

            val pulseAlpha = (60 + 60 * kotlin.math.sin(pulsePhase * Math.PI * 2)).toInt().coerceIn(20, 140)
            glowPaint.color = glow
            glowPaint.alpha = pulseAlpha
            glowPaint.strokeWidth = 14f + pop * 18f
            val inset = -6f - pop * 10f
            canvas.drawRoundRect(
                RectF(face.box.left + inset, face.box.top + inset, face.box.right - inset, face.box.bottom - inset),
                20f, 20f, glowPaint
            )

            boxPaint.color = color
            boxPaint.strokeWidth = 6f + pop * 4f
            canvas.drawRoundRect(face.box, 18f, 18f, boxPaint)

            textBgPaint.color = color
            val textWidth = textPaint.measureText(face.label)
            val pillTop = face.box.top - 58f
            canvas.drawRoundRect(
                face.box.left, pillTop,
                face.box.left + textWidth + 32f, face.box.top - 6f,
                14f, 14f, textBgPaint
            )
            canvas.drawText(face.label, face.box.left + 16f, face.box.top - 22f, textPaint)
        }
    }
}
