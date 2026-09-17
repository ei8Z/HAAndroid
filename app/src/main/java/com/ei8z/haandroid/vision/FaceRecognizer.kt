package com.ei8z.haandroid.vision

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceRecognizer(context: Context) {
    companion object {
        private const val TAG = "FaceRecognizer"
        private const val MODEL_PATH = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val CPU_THREADS = 4
        private const val NORM_MEAN = 127.5f
        private const val NORM_STD = 128f
    }

    private var interpreter: Interpreter? = null
    private var embeddingSize = 192 // 默认值，加载后根据模型输出动态调整
    private var isGpuActive = false

    init {
        try {
            val modelBuffer = loadModelFile(context.assets, MODEL_PATH)
            val (builtInterpreter, gpuUsed) = buildInterpreter(modelBuffer)
            interpreter = builtInterpreter
            isGpuActive = gpuUsed

            // 动态检测模型输出维度
            interpreter?.getOutputTensor(0)?.shape()?.let { shape ->
                if (shape.size >= 2) {
                    embeddingSize = shape[1]
                    Log.i(TAG, "Model loaded: isGpuActive=$isGpuActive, embeddingSize=$embeddingSize")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during FaceRecognizer initialization", e)
        }
    }

    private fun buildInterpreter(buffer: ByteBuffer): Pair<Interpreter?, Boolean> {
        // 第一段：尝试 GPU
        runCatching {
            val gpuOptions = Interpreter.Options().apply {
                addDelegate(GpuDelegate())
            }
            return Interpreter(buffer, gpuOptions) to true
        }.onFailure {
            Log.w(TAG, "GPU path failed (delegate or interpreter), retry on CPU: $it")
        }

        // 第二段：纯 CPU
        return runCatching {
            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(CPU_THREADS)
            }
            Interpreter(buffer, cpuOptions) to false
        }.onFailure {
            Log.e(TAG, "CPU path failed too", it)
        }.getOrNull() ?: (null to false)
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun recognize(faceBitmap: Bitmap): FloatArray {
        val model = interpreter ?: return FloatArray(0)
        val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
        
        val output = Array(1) { FloatArray(embeddingSize) }
        try {
            model.run(byteBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return FloatArray(0)
        }
        
        return output[0]
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixelValue in intValues) {
            // MobileFaceNet 标准归一化: (x - 127.5) / 128
            byteBuffer.putFloat(((pixelValue shr 16 and 0xFF) - NORM_MEAN) / NORM_STD)
            byteBuffer.putFloat(((pixelValue shr 8 and 0xFF) - NORM_MEAN) / NORM_STD)
            byteBuffer.putFloat(((pixelValue and 0xFF) - NORM_MEAN) / NORM_STD)
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}
