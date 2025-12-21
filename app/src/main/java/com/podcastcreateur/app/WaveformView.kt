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
    private var totalSamplesEstimate = 0L
    private val samplesPerPoint = 882
    private var zoomFactor = 1.0f 

    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0

    private val paint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false 
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
    
    // GESTURE DETECTOR CONFIGURÉ POUR SCROLL VS SELECT
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true 
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tap simple : Déplace le curseur, annule la sélection
            playheadPos = pixelToSample(e.x)
            selectionStart = -1
            selectionEnd = -1
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Appui Long : DÉMARRE LA SÉLECTION
            isDraggingSelection = true
            val s = pixelToSample(e.x)
            selectionStart = s
            selectionEnd = s
            // Feedback tactile (vibreur)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            
            // Empêche le parent de scroller pendant qu'on sélectionne
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    fun initialize(totalSamples: Long) {
        this.totalSamplesEstimate = totalSamples
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalPoints = if (points.size > 0 && points.size * samplesPerPoint > totalSamplesEstimate) {
            points.size.toLong()
        } else {
            totalSamplesEstimate / samplesPerPoint
        }
        val contentWidth = (totalPoints * zoomFactor).toInt()
        val finalWidth = resolveSize(contentWidth, widthMeasureSpec)
        val finalHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(contentWidth.coerceAtLeast(finalWidth), finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val h = height.toFloat()
        val centerY = h / 2f
        
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerLinePaint)

        for (i in points.indices) {
            val x = i * zoomFactor
            val valPeak = points[i] 
            val barHeight = valPeak * centerY * 0.95f 
            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, paint)
        }

        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = sampleToPixel(selectionStart)
            val x2 = sampleToPixel(selectionEnd)
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        val px = sampleToPixel(playheadPos)
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }
    
    fun sampleToPixel(sample: Int): Float {
        val pointIndex = sample / samplesPerPoint
        return pointIndex * zoomFactor
    }

    fun pixelToSample(x: Float): Int {
        val pointIndex = x / zoomFactor
        return (pointIndex * samplesPerPoint).toInt()
    }
    
    fun getCenterSample(scrollX: Int, visibleWidth: Int): Int {
        val centerX = scrollX + (visibleWidth / 2)
        return pixelToSample(centerX.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Le gestureDetector gère Tap et LongPress
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }

        when(event.action) {
            MotionEvent.ACTION_MOVE -> {
                // Si on est en mode sélection (activé par LongPress), on met à jour la fin
                if (isDraggingSelection) {
                    val s = pixelToSample(event.x)
                    selectionEnd = s
                    invalidate()
                    return true
                }
                // Sinon, on renvoie false pour laisser le ScrollView parent gérer le scroll
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingSelection) {
                    isDraggingSelection = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    
                    // Remettre start < end
                    if(selectionStart > selectionEnd) {
                        val t = selectionStart; selectionStart = selectionEnd; selectionEnd = t
                    }
                    invalidate()
                    return true
                }
            }
        }
        
        // Par défaut, si on n'est pas en train de sélectionner, on laisse le parent gérer (Scroll)
        return false
    }
    
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}