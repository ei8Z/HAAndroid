package com.ei8z.haandroid.vision

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceRecognizer(context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 112 
    private var embeddingSize = 192 // 默认改为 192

    init {
        try {
            val options = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                options.addDelegate(GpuDelegate(delegateOptions))
            } else {
                options.setNumThreads(4)
            }
            
            val modelBuffer = loadModelFile(context.assets, "mobilefacenet.tflite")
            interpreter = Interpreter(modelBuffer, options)
            
            // 动态检测模型输出维度，防止以后再次报错
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            if (outputShape != null && outputShape.size >= 2) {
                embeddingSize = outputShape[1]
                Log.i("FaceRecognizer", "Detected model embedding size: $embeddingSize")
            }
            
            Log.i("FaceRecognizer", "MobileFaceNet model loaded successfully")
        } catch (e: Exception) {
            Log.e("FaceRecognizer", "Error loading model", e)
        }
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * 提取人脸特征向量 (Embedding)
     */
    fun recognize(faceBitmap: Bitmap): FloatArray {
        val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
        
        // 使用动态获取的维度
        val output = Array(1) { FloatArray(embeddingSize) }
        interpreter?.run(byteBuffer, output)
        
        return output[0]
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixelValue in intValues) {
            // MobileFaceNet 标准归一化: (x - 127.5) / 128
            byteBuffer.putFloat(((pixelValue shr 16 and 0xFF) - 127.5f) / 128f)
            byteBuffer.putFloat(((pixelValue shr 8 and 0xFF) - 127.5f) / 128f)
            byteBuffer.putFloat(((pixelValue and 0xFF) - 127.5f) / 128f)
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}
