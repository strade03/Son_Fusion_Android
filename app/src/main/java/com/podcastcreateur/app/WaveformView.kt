package com.podcastcreateur.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

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
  
    private val wavePaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false // Désactivé pour la perf sur les lignes droites
    }

    private val outOfBoundsPaint = Paint().apply {
        color = Color.parseColor("#BDBDBD")
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
    private val wavePath = Path() // Réutilisation du Path
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true 

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val idx = pixelToIndex(e.x).coerceIn(0, (points.size - 1).coerceAtLeast(0))
            playheadPos = idx
            selectionStart = -1
            selectionEnd = -1
            onPositionChanged?.invoke(playheadPos)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            isDraggingSelection = true
            val s = pixelToIndex(e.x).coerceIn(0, (points.size - 1).coerceAtLeast(0))
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
        requestLayout()
        invalidate()
    }

    fun appendData(newPoints: FloatArray) {
        for(p in newPoints) points.add(p)
        requestLayout()
        invalidate()
    }
    
    fun deleteRange(start: Int, end: Int) {
        if (start < 0 || end > points.size || start >= end) return
        val count = end - start
        try {
            points.subList(start, end).clear()
        } catch (e: Exception) {
             for (i in 0 until count) points.removeAt(start)
        }
        
        selectionStart = -1
        selectionEnd = -1
        if (playheadPos > start) {
            playheadPos = (playheadPos - count).coerceAtLeast(start)
        }
        requestLayout()
        invalidate()
    }
    
    fun setZoomLevel(factor: Float) {
        zoomFactor = factor.coerceIn(Constants.MIN_ZOOM, Constants.MAX_ZOOM)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val currentSize = points.size.toLong()
        val total = if (currentSize > 0) currentSize else totalPointsEstimate
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
        
        // 1. Zone vide
        val audioEndX = points.size * zoomFactor
        if (audioEndX < width) {
            canvas.drawRect(audioEndX, 0f, width.toFloat(), h, outOfBoundsPaint)
        }

        // 2. Ligne centrale
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerLinePaint)

        // 3. Onde (OPTIMISATION PATH + CLIP)
        val clipBounds = canvas.clipBounds
        // On ne parcourt que les points visibles à l'écran
        val startIdx = pixelToIndex(clipBounds.left.toFloat()).coerceAtLeast(0)
        val endIdx = pixelToIndex(clipBounds.right.toFloat()).coerceAtMost(points.size - 1)

        if (startIdx <= endIdx && points.isNotEmpty()) {
            wavePath.reset()
            // Pour éviter les artefacts, on se déplace au premier point
            val firstX = startIdx * zoomFactor
            wavePath.moveTo(firstX, centerY)

            for (i in startIdx..endIdx) {
                val x = i * zoomFactor
                val valPeak = points[i] 
                val barHeight = valPeak * centerY * 0.95f 
                
                // On dessine une ligne verticale pour chaque point
                wavePath.moveTo(x, centerY - barHeight)
                wavePath.lineTo(x, centerY + barHeight)
            }
            canvas.drawPath(wavePath, wavePaint)
        }

        // 4. SÉLECTION
        if (selectionStart >= 0) {
            val drawStart = min(selectionStart, selectionEnd)
            val drawEnd = max(selectionStart, selectionEnd)
            
            if (drawEnd > drawStart) {
                val x1 = sampleToPixel(drawStart)
                val x2 = sampleToPixel(drawEnd)
                canvas.drawRect(x1, 0f, x2, h, selectionPaint)
            }
        }

        // 5. Curseur
        val px = sampleToPixel(playheadPos)
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }
    
    fun sampleToPixel(index: Int): Float = index * zoomFactor

    fun pixelToIndex(x: Float): Int {
        if (zoomFactor <= 0.001f) return 0
        return (x / zoomFactor).toInt()
    }
    
    fun getPointsCount(): Int = points.size

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) return true
        
        when(event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    val maxIndex = (points.size - 1).coerceAtLeast(0)
                    val s = pixelToIndex(event.x).coerceIn(0, maxIndex)
                    
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
                    if (selectionStart == selectionEnd) {
                        selectionStart = -1
                        selectionEnd = -1
                    } else if (selectionStart >= 0) {
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