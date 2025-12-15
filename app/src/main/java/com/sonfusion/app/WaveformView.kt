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
    
    // Niveau de zoom interne (1.0 = largeur de l'écran)
    private var zoomFactor = 1.0f 
    
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
    
    // Nouvelle méthode pour gérer le zoom proprement
    fun setZoomLevel(factor: Float) {
        zoomFactor = factor.coerceIn(1.0f, 20.0f)
        requestLayout() // Déclenche onMeasure
        invalidate()
    }

    fun clearSelection() { 
        selectionStart = -1; 
        selectionEnd = -1; 
        invalidate() 
    }

    // C'est ici que la magie du zoom opère
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        
        // La largeur désirée est la largeur écran * facteur de zoom
        val desiredWidth = (screenWidth * zoomFactor).toInt()
        
        // On s'assure que ce n'est pas moins que la taille proposée par le parent
        val finalWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val finalHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty() || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        val samplesPerPixel = (samples.size / w).coerceAtLeast(0.1f) 

        for (i in 0 until width) {
            val startIdx = (i * samplesPerPixel).toInt()
            val endIdx = ((i + 1) * samplesPerPixel).toInt().coerceAtMost(samples.size)
            
            var maxVal = 0
            if (startIdx < samples.size) {
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

        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = (selectionStart.toFloat() / samples.size) * w
            val x2 = (selectionEnd.toFloat() / samples.size) * w
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        val px = (playheadPos.toFloat() / samples.size) * w
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty() || width == 0) return false
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