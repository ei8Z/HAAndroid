package com.ei8z.haandroid.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.ei8z.haandroid.data.model.Detection
import com.ei8z.haandroid.data.model.Environment
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
 *
 * 线程模型：本类所有方法都在单线程内串行调用（调用方持 Mutex + 单线程执行器），
 * 其中条码解码内部切换到 Default 线程池。
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

    // 帧缓冲池（M10）：旋转输出与裁剪输出的位图跨帧复用，避免每帧分配
    private var frameBuffer: Bitmap? = null
    private var cropBuffer: Bitmap? = null

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

    /**
     * 处理一帧。ImageProxy 的所有权转交给本方法：无论成功失败都会关闭。
     * 同一时刻只处理一帧（Mutex），竞争帧直接丢弃。
     */
    suspend fun processImage(imageProxy: ImageProxy): VisionDetectionMessage? {
        if (processingMutex.isLocked) {
            imageProxy.close()
            return null
        }

        return processingMutex.withLock {
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val rawBitmap = imageProxy.toBitmap()
                val bitmap = rotate(rawBitmap, rotationDegrees.toFloat())
                if (bitmap !== rawBitmap) {
                    // 旋转结果已进入池化缓冲，CameraX 新分配的源图可立即回收
                    rawBitmap.recycle()
                }
                // degrees==0 时 bitmap 即 rawBitmap：不主动回收
                // （ML Kit 异步回调仍持有引用，交由 GC 处理）

                val imageWidth = bitmap.width.toFloat()
                val imageHeight = bitmap.height.toFloat()

                val allDetections = mutableListOf<Detection>()

                // 1. 人员感知（可被 MQTT 策略关闭）
                if (enablePersonDetection) {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    try {
                        faceDetector?.detect(mpImage)?.detections()?.forEach { face ->
                            val bbox = face.boundingBox()

                            // 身份特征提取
                            val identity = try {
                                val faceBitmap = cropFace(bitmap, bbox)
                                val embedding = faceRecognizer.recognize(faceBitmap)
                                if (embedding.isEmpty()) {
                                    // 模型不可用（加载失败）：标记为识别错误而非崩溃
                                    "err"
                                } else {
                                    // 上报完整的 Embedding 字符串，供后端匹配身份
                                    embedding.joinToString(",") { String.format("%.4f", it) }
                                }
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
                                confidence = face.categories().firstOrNull()?.score(),
                                bbox = normBbox
                            ))
                        }
                    } finally {
                        mpImage.close() // 释放 GPU delegate 持有的原生句柄
                    }
                }

                // 2. 条码/二维码解码（每 2 帧一次，节省端侧算力；可被 MQTT 策略关闭）
                if (enableBarcodeScanning && frameIndex % 2 == 0L) {
                    barcodeScanner.scan(bitmap).forEach { scan ->
                        allDetections.add(Detection(
                            type = "barcode",
                            value = scan.value,
                            format = scan.format,
                            confidence = null, // 条码解码无置信度语义
                            bbox = scan.normalizedBox
                        ))
                    }
                }
                frameIndex++

                VisionDetectionMessage(
                    node_id = nodeId,
                    timestamp = System.currentTimeMillis(),
                    detections = allDetections,
                    environment = Environment(location = nodeId)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
                null
            } finally {
                imageProxy.close()
            }
        }
    }

    /**
     * 旋转到位图缓冲：目标位图从池中复用（90/270° 宽高互换）。
     * 串行调用（Mutex 保证），池化缓冲无并发风险。
     */
    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val swap = degrees % 180f != 0f
        val outWidth = if (swap) bitmap.height else bitmap.width
        val outHeight = if (swap) bitmap.width else bitmap.height
        val dst = frameBuffer?.takeIf { it.width == outWidth && it.height == outHeight }
            ?: Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                .also { frameBuffer = it }
        val matrix = Matrix().apply { postRotate(degrees) }
        Canvas(dst).drawBitmap(bitmap, matrix, null)
        return dst
    }

    private fun cropFace(original: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.coerceIn(0f, original.width.toFloat()).toInt()
        val top = bbox.top.coerceIn(0f, original.height.toFloat()).toInt()
        val width = bbox.width().toInt().coerceAtMost(original.width - left)
        val height = bbox.height().toInt().coerceAtMost(original.height - top)
        if (width <= 0 || height <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val dst = cropBuffer?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { cropBuffer = it }
        Canvas(dst).drawBitmap(
            original,
            Rect(left, top, left + width, top + height),
            Rect(0, 0, width, height),
            null
        )
        return dst
    }

    fun release() {
        faceDetector?.close()
        faceRecognizer.close()
        barcodeScanner.close()
    }
}
