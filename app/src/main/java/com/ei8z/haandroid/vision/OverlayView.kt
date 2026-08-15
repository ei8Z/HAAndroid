package com.ei8z.haandroid.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ei8z.haandroid.data.model.Detection

/**
 * 检测结果叠加层。
 *
 * - person  → 绿色框，标签显示身份特征摘要；
 * - barcode → 青色框，标签显示制式 + 解码内容（截断展示）。
 * 坐标基于后置摄像头已转正的帧，无需镜像翻转。
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<Detection> = listOf()

    private val personBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val personTextPaint = Paint().apply {
        color = Color.GREEN
        textSize = 45f
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val barcodeBoxPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val barcodeTextPaint = Paint().apply {
        color = Color.CYAN
        textSize = 40f
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    /**
     * 设置检测结果并重绘
     */
    fun setResults(detections: List<Detection>) {
        results = detections
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (detection in results) {
            val bbox = detection.bbox ?: continue

            // bbox 为归一化坐标 [x, y, w, h]，基于已转正的位图
            val normX = bbox[0]
            val normY = bbox[1]
            val normW = bbox[2]
            val normH = bbox[3]

            val isBarcode = detection.type == "barcode"
            val boxPaint = if (isBarcode) barcodeBoxPaint else personBoxPaint
            val textPaint = if (isBarcode) barcodeTextPaint else personTextPaint

            val rect = RectF(
                normX * width,
                normY * height,
                (normX + normW) * width,
                (normY + normH) * height
            )

            canvas.drawRect(rect, boxPaint)

            val label = if (isBarcode) {
                "${detection.format}: ${(detection.value ?: "").take(18)}"
            } else {
                val conf = detection.confidence?.let { String.format("%.2f", it) } ?: "?"
                "person($conf)"
            }
            canvas.drawText(label, rect.left, rect.top - 20, textPaint)
        }
    }
}
