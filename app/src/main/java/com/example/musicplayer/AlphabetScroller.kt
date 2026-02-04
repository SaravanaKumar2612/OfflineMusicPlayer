package com.example.musicplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class AlphabetScroller @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val alphabet = ('A'..'Z').toList()
    private val paint = Paint().apply {
        color = Color.LTGRAY
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Callback when user drags (returns the letter)
    var onSectionChanged: ((Char) -> Unit)? = null
    // Callback when user lifts finger
    var onTouchActionUp: (() -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sectionHeight = height / alphabet.size.toFloat()
        paint.textSize = (sectionHeight * 0.75f).coerceAtMost(40f)

        alphabet.forEachIndexed { index, char ->
            val xPos = width / 2f
            val yPos = (sectionHeight * index) + (sectionHeight / 2) + (paint.textSize / 3)
            canvas.drawText(char.toString(), xPos, yPos, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val sectionHeight = height / alphabet.size.toFloat()
                val index = (event.y / sectionHeight).toInt().coerceIn(0, alphabet.size - 1)
                val selectedChar = alphabet[index]

                onSectionChanged?.invoke(selectedChar)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onTouchActionUp?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}