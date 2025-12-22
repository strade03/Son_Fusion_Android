package com.podcastcreateur.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val points = ArrayList<Float>()
    private var totalPointsEstimate = 0L
    private var zoomFactor = 1.0f 

    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0
    
    var onPositionChanged: ((Int) -> Unit)? = null
    
    // 🔥 NOUVEAUTÉ : Zones coupées (visuellement)
    private val cutRegions = mutableListOf<Pair<Int, Int>>() // Liste des (start, end) coupés
  
    private val paint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false 
    }

    private val outOfBoundsPaint = Paint().apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.FILL
    }
    
    // 🔥 NOUVEAUTÉ : Paint pour les zones coupées
    private val cutRegionPaint = Paint().apply {
        color = Color.parseColor("#66FF0000") // Rouge semi-transparent
        style = Paint.Style.FILL
    }

    private val centerLinePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }
    
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#44FFEB3B")
        style = Paint.Style.FILL
    }
    
    private val playheadPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
    }

    private var isDraggingSelection = false
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true 
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            playheadPos = pixelToIndex(e.x)
            selectionStart = -1
            selectionEnd = -1
            onPositionChanged?.invoke(playheadPos)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            isDraggingSelection = true
            val s = pixelToIndex(e.x)
            selectionStart = s
            selectionEnd = s
            playheadPos = s
            onPositionChanged?.invoke(playheadPos)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    fun initialize(totalPoints: Long) {
        this.totalPointsEstimate = totalPoints
        clearData()
    }
    
    fun clearData() {
        points.clear()
        selectionStart = -1
        selectionEnd = -1
        playheadPos = 0
        cutRegions.clear() // 🔥 Réinitialiser les coupes
        requestLayout()
        invalidate()
    }

    fun appendData(newPoints: FloatArray) {
        for(p in newPoints) points.add(p)
        requestLayout()
        invalidate()
    }
    
    fun setZoomLevel(factor: Float) {
        zoomFactor = factor
        requestLayout()
        invalidate()
    }

    fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
        invalidate()
    }
    
    // 🔥 NOUVEAUTÉ : Marquer visuellement une zone comme coupée
    fun applyCutVisually(startIdx: Int, endIdx: Int) {
        cutRegions.add(Pair(startIdx, endIdx))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val total = if (points.size > totalPointsEstimate) points.size.toLong() else totalPointsEstimate
        val contentWidth = (total * zoomFactor).toInt()
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val finalWidth = contentWidth.coerceAtLeast(parentWidth)
        val finalHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val h = height.toFloat()
        val centerY = h / 2f
        
        // Dessiner le fond de la zone "après le son"
        val audioEndX = points.size * zoomFactor
        if (audioEndX < width) {
            canvas.drawRect(audioEndX, 0f, width.toFloat(), h, outOfBoundsPaint)
        }

        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerLinePaint)

        // Dessiner la waveform
        for (i in points.indices) {
            val x = i * zoomFactor
            val valPeak = points[i] 
            val barHeight = valPeak * centerY * 0.95f 
            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, paint)
        }
        
        // 🔥 NOUVEAUTÉ : Dessiner les zones coupées par-dessus
        cutRegions.forEach { (start, end) ->
            val x1 = sampleToPixel(start)
            val x2 = sampleToPixel(end)
            canvas.drawRect(x1, 0f, x2, h, cutRegionPaint)
        }

        // Dessiner la sélection actuelle
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = sampleToPixel(selectionStart)
            val x2 = sampleToPixel(selectionEnd)
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Dessiner le playhead
        val px = sampleToPixel(playheadPos)
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }
    
    fun sampleToPixel(index: Int): Float {
        return index * zoomFactor
    }

    fun pixelToIndex(x: Float): Int {
        return (x / zoomFactor).toInt()
    }
    
    fun getCenterSample(scrollX: Int, visibleWidth: Int): Int {
        val centerX = scrollX + (visibleWidth / 2)
        return pixelToIndex(centerX.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }

        when(event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    val s = pixelToIndex(event.x)
                    selectionEnd = s
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingSelection) {
                    isDraggingSelection = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    
                    if(selectionStart > selectionEnd) {
                        val t = selectionStart; selectionStart = selectionEnd; selectionEnd = t
                    }
                    if (selectionStart >= 0 && selectionStart != selectionEnd) {
                        playheadPos = selectionStart
                        onPositionChanged?.invoke(playheadPos)
                    }
                    invalidate()
                    return true
                }
            }
        }
        return false
    }
    
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}