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
        color = Color.parseColor("#550000FF") // Semi-transparent blue
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
        invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        val samplesPerPixel = samples.size / width

        // Draw Waveform (Simplified: just max value per chunk)
        for (i in 0 until width.toInt()) {
            val startIdx = (i * samplesPerPixel).toInt()
            var maxVal = 0
            val endIdx = ((i + 1) * samplesPerPixel).toInt().coerceAtMost(samples.size)

            for (j in startIdx until endIdx) {
                val v = abs(samples[j].toInt())
                if (v > maxVal) maxVal = v
            }

            val scaledH = (maxVal / 32767f) * centerY
            canvas.drawLine(i.toFloat(), centerY - scaledH, i.toFloat(), centerY + scaledH, paint)
        }

        // Draw Selection
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = (selectionStart.toFloat() / samples.size) * width
            val x2 = (selectionEnd.toFloat() / samples.size) * width
            canvas.drawRect(x1, 0f, x2, height, selectionPaint)
        }

        // Draw Playhead
        val px = (playheadPos.toFloat() / samples.size) * width
        canvas.drawLine(px, 0f, px, height, playheadPaint)
    }

    // Basic touch handling for selection
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return false

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