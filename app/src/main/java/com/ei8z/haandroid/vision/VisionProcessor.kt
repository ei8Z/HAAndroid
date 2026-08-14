package com.ei8z.haandroid.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.ei8z.haandroid.data.model.Detection
import com.ei8z.haandroid.data.model.VisionDetectionMessage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工业现场视觉处理流水线
 *
 * - 人员感知：MediaPipe blaze_face 检测 + MobileFaceNet 身份特征提取
 * - 物料标识：ML Kit 条码/二维码解码（托盘/物料/工单标签）
 * - 检测策略可由 MQTT 命令 `set_detection_policy` 动态开关（见 VisionForegroundService）
 */
class VisionProcessor(private val context: Context, private val nodeId: String) {

    private val TAG = "VisionProcessor"
    private var faceDetector: FaceDetector? = null
    private val faceRecognizer = FaceRecognizer(context)
    private val barcodeScanner = BarcodeScanner()
    private val processingMutex = Mutex()

    /** 策略开关，由 VisionForegroundService 根据本地配置 + MQTT 命令同步 */
    @Volatile var enablePersonDetection = true
    @Volatile var enableBarcodeScanning = true

    private var frameIndex = 0L

    init {
        setupModels()
    }

    private fun setupModels() {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("blaze_face_short_range.tflite")
                .build()

            val faceOptions = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .build()

            faceDetector = FaceDetector.createFromOptions(context, faceOptions)
            Log.i(TAG, "MediaPipe FaceDetector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "FaceDetector init failed: ${e.message}")
        }
    }

    suspend fun processImage(imageProxy: ImageProxy): VisionDetectionMessage? {
        if (processingMutex.isLocked) {
            imageProxy.close()
            return null
        }

        return processingMutex.withLock {
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap().rotate(rotationDegrees.toFloat())

                val mpImage = BitmapImageBuilder(bitmap).build()
                val imageWidth = bitmap.width.toFloat()
                val imageHeight = bitmap.height.toFloat()

                val allDetections = mutableListOf<Detection>()

                // 1. 人员感知（可被 MQTT 策略关闭）
                if (enablePersonDetection) {
                    faceDetector?.detect(mpImage)?.detections()?.forEach { face ->
                        val bbox = face.boundingBox()

                        // 身份特征提取
                        val identity = try {
                            val faceBitmap = cropFace(bitmap, bbox)
                            val embedding = faceRecognizer.recognize(faceBitmap)
                            // 上报完整的 Embedding 字符串，供后端匹配身份
                            // 格式示例: "0.123,0.456,-0.789..."
                            embedding.joinToString(",") { String.format("%.4f", it) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Recognition error", e)
                            "err"
                        }

                        val normBbox = listOf(
                            bbox.left / imageWidth,
                            bbox.top / imageHeight,
                            bbox.width() / imageWidth,
                            bbox.height() / imageHeight
                        )

                        allDetections.add(Detection(
                            type = "person",
                            identity = identity,
                            confidence = face.categories().firstOrNull()?.score() ?: 0f,
                            bbox = normBbox
                        ))
                    }
                }

                // 2. 条码/二维码解码（每 2 帧一次，节省端侧算力；可被 MQTT 策略关闭）
                if (enableBarcodeScanning && frameIndex % 2 == 0L) {
                    barcodeScanner.scan(bitmap).forEach { scan ->
                        allDetections.add(Detection(
                            type = "barcode",
                            value = scan.value,
                            format = scan.format,
                            confidence = scan.confidence,
                            bbox = scan.normalizedBox
                        ))
                    }
                }
                frameIndex++

                VisionDetectionMessage(
                    node_id = nodeId,
                    timestamp = System.currentTimeMillis(),
                    detections = allDetections
                )
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
                null
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        if (degrees == 0f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun cropFace(original: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.coerceIn(0f, original.width.toFloat()).toInt()
        val top = bbox.top.coerceIn(0f, original.height.toFloat()).toInt()
        val width = bbox.width().toInt().coerceAtMost(original.width - left)
        val height = bbox.height().toInt().coerceAtMost(original.height - top)
        if (width <= 0 || height <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(original, left, top, width, height)
    }

    fun release() {
        faceDetector?.close()
        faceRecognizer.close()
        barcodeScanner.close()
    }
}
