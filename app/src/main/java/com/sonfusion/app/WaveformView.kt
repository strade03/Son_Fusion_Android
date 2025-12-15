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

    fun clearSelection() { 
        selectionStart = -1; 
        selectionEnd = -1; 
        invalidate() 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // width peut être 0 au démarrage, ou très grand si zoomé
        if (samples.isEmpty() || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        // Nombre de samples représentés par 1 pixel
        // Si w est très grand (zoom max), samplesPerPixel peut être < 1 (on dessine plusieurs pixels pour 1 sample)
        // Mais ici on fait une simplification : 1 pixel = au moins 1 sample pour éviter les boucles infinies
        val samplesPerPixel = (samples.size / w).coerceAtLeast(0.1f) 

        // On parcourt les pixels de l'écran (de 0 à width)
        for (i in 0 until width) {
            val startIdx = (i * samplesPerPixel).toInt()
            val endIdx = ((i + 1) * samplesPerPixel).toInt().coerceAtMost(samples.size)
            
            var maxVal = 0
            if (startIdx < samples.size) {
                // Si on a moins d'un sample par pixel (gros zoom), on prend juste la valeur du sample
                if (startIdx >= endIdx) {
                    maxVal = abs(samples[startIdx].toInt())
                } else {
                    for (j in startIdx until endIdx) {
                        val v = abs(samples[j].toInt())
                        if (v > maxVal) maxVal = v
                    }
                }
            }

            val scaledH = (maxVal / 32767f) * centerY
            canvas.drawLine(i.toFloat(), centerY - scaledH, i.toFloat(), centerY + scaledH, paint)
        }

        // Dessin Selection
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = (selectionStart.toFloat() / samples.size) * w
            val x2 = (selectionEnd.toFloat() / samples.size) * w
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Dessin Tête de lecture
        val px = (playheadPos.toFloat() / samples.size) * w
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return false
        val sampleIdx = ((event.x / width) * samples.size).toInt().coerceIn(0, samples.size)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectionStart = sampleIdx
                selectionEnd = sampleIdx
                playheadPos = sampleIdx
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selectionEnd = sampleIdx
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
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