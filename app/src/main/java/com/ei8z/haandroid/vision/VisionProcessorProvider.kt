package com.ei8z.haandroid.vision

import android.content.Context

/**
 * VisionProcessor 进程级共享单例 + 引用计数（审查项 M7）。
 *
 * 模型集（MediaPipe FaceDetector + TFLite MobileFaceNet + ML Kit 条码）
 * 加载成本高，Activity 预览与前台服务不应各自持有副本。
 * 引用计数归零时才真正释放模型资源。
 *
 * 注意：
 * - acquire 会执行模型加载（IO），调用方必须在后台线程执行；
 * - 已知取舍：Activity 旋转若导致引用计数短暂归零，模型会重建
 *   （模型总大小 ~5MB，加载 < 1s，演示级场景可接受）。
 */
object VisionProcessorProvider {

    @Volatile
    private var processor: VisionProcessor? = null

    @Volatile
    private var refCount = 0

    @Synchronized
    fun acquire(context: Context, nodeId: String): VisionProcessor {
        refCount++
        processor?.let { return it }
        return VisionProcessor(context.applicationContext, nodeId).also { processor = it }
    }

    @Synchronized
    fun release() {
        if (refCount > 0) refCount--
        if (refCount == 0) {
            processor?.release()
            processor = null
        }
    }
}
