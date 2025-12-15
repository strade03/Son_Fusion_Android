package com.podcastcreateur.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: ShortArray = ShortArray(0)
    private val paint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#550000FF")
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
    }

    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0

    fun setWaveform(data: ShortArray) {
        samples = data
        requestLayout()
        invalidate()
    }
    
    // Pour calculer la position en pixels par rapport aux samples, on utilise la largeur actuelle de la vue
    fun getPixelsPerSample(): Float {
        if (samples.isEmpty()) return 0f
        return width.toFloat() / samples.size
    }

    fun clearSelection() { 
        selectionStart = -1; 
        selectionEnd = -1; 
        invalidate() 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty() || width == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        // On dessine un point par pixel horizontal
        // On calcule combien de samples correspondent à 1 pixel
        val samplesPerPixel = samples.size / w

        for (i in 0 until width) {
            val startIdx = (i * samplesPerPixel).toInt()
            val endIdx = ((i + 1) * samplesPerPixel).toInt().coerceAtMost(samples.size)
            
            var maxVal = 0
            // On prend la valeur max (peak) dans cet intervalle
            if (startIdx < endIdx) {
                for (j in startIdx until endIdx) {
                    val v = abs(samples[j].toInt())
                    if (v > maxVal) maxVal = v
                }
            }

            val scaledH = (maxVal / 32767f) * centerY
            canvas.drawLine(i.toFloat(), centerY - scaledH, i.toFloat(), centerY + scaledH, paint)
        }

        // Draw Selection
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = (selectionStart.toFloat() / samples.size) * w
            val x2 = (selectionEnd.toFloat() / samples.size) * w
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Draw Playhead
        val px = (playheadPos.toFloat() / samples.size) * w
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return false

        // Conversion coordonnée X -> Index Sample
        val sampleIdx = ((event.x / width) * samples.size).toInt().coerceIn(0, samples.size)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectionStart = sampleIdx
                selectionEnd = sampleIdx
                playheadPos = sampleIdx
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selectionEnd = sampleIdx
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (selectionStart > selectionEnd) {
                    val temp = selectionStart
                    selectionStart = selectionEnd
                    selectionEnd = temp
                }
                performClick()
            }
        }
        return true
    }
}