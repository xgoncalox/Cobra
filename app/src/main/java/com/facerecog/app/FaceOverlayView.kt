package com.facerecog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class OverlayFace(val box: RectF, val label: String, val isKnown: Boolean)

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faces: List<OverlayFace> = emptyList()

    private val knownPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val unknownPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    fun setFaces(newFaces: List<OverlayFace>) {
        faces = newFaces
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (face in faces) {
            val paint = if (face.isKnown) knownPaint else unknownPaint
            canvas.drawRoundRect(face.box, 16f, 16f, paint)

            textBgPaint.color = if (face.isKnown) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            val textWidth = textPaint.measureText(face.label)
            canvas.drawRect(
                face.box.left, face.box.top - 50f,
                face.box.left + textWidth + 20f, face.box.top,
                textBgPaint
            )
            canvas.drawText(face.label, face.box.left + 10f, face.box.top - 14f, textPaint)
        }
    }
}
