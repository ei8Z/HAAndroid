package com.ei8z.haandroid.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 单帧条码/二维码解码结果
 *
 * @param value 解码内容（rawValue 优先）
 * @param format 条码制式名（QR_CODE / CODE_128 / EAN_13 ...）
 * @param normalizedBox 归一化边界框 [x, y, w, h]，坐标系与已转正的位图一致
 */
data class BarcodeScan(
    val value: String,
    val format: String,
    val normalizedBox: List<Float>?,
    val confidence: Float = 1f,
)

/**
 * ML Kit 条码扫描器封装。
 *
 * 独立于 MediaPipe/TFLite 管线：
 * - 全格式（FORMAT_ALL_FORMATS）支持，覆盖托盘/物料/工单常见的一维码与二维码；
 * - standalone 版本，模型随 APK 打包，无需 Google Play Services，离线可用（工业内网友好）。
 */
class BarcodeScanner {

    private val TAG = "BarcodeScanner"

    private val scanner = BarcodeScanning.getClient(
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    /**
     * 对单帧位图执行条码解码。
     * 失败/未检出时返回空列表（不抛出），由上游决定是否继续。
     */
    suspend fun scan(bitmap: Bitmap): List<BarcodeScan> = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val results = barcodes.mapNotNull { barcode ->
                        val value = barcode.rawValue ?: barcode.displayValue ?: return@mapNotNull null
                        BarcodeScan(
                            value = value,
                            format = formatName(barcode.format),
                            normalizedBox = barcode.boundingBox?.let { box ->
                                listOf(
                                    box.left.toFloat() / bitmap.width,
                                    box.top.toFloat() / bitmap.height,
                                    box.width().toFloat() / bitmap.width,
                                    box.height().toFloat() / bitmap.height
                                )
                            }
                        )
                    }
                    if (continuation.isActive) continuation.resume(results)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Barcode scan failed (degraded to empty): ${e.message}")
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN"
    }

    fun close() {
        scanner.close()
    }
}
