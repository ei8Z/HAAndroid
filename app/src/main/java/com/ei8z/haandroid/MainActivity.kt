package com.ei8z.haandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.*
import com.ei8z.haandroid.data.SettingsManager
import com.ei8z.haandroid.data.model.VisionDetectionMessage
import com.ei8z.haandroid.databinding.ActivityMainBinding
import com.ei8z.haandroid.net.MqttManager
import com.ei8z.haandroid.service.VisionForegroundService
import com.ei8z.haandroid.vision.VisionProcessor
import com.ei8z.haandroid.vision.VisionProcessorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREVIEW_PUBLISH_INTERVAL_MS = 200L // 预览上报节流（最高 5 FPS）
    }

    private lateinit var binding: ActivityMainBinding
    private var visionProcessor: VisionProcessor? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var mqttManager: MqttManager? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var statusPollJob: Job? = null
    private var lastPreviewSend = 0L

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupVisionEngine()
        } else {
            Toast.makeText(this, "需要相机权限来预览识别结果", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        binding.btnStartService.setOnClickListener {
            startVisionService()
        }

        binding.btnStopService.setOnClickListener {
            stopVisionService()
        }
    }

    private fun checkAndRequestPermissions() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            setupVisionEngine()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    /**
     * 两种运行模式（相机所有权互斥）：
     * - 预览模式（服务未运行）：Activity 持有相机 → 画面检测框 + MQTT 上报同时开启；
     * - 服务模式（服务运行中）：相机由前台服务持有 → 灭屏持续感知，Activity 显示横幅。
     */
    private fun setupVisionEngine() {
        lifecycleScope.launch {
            if (VisionForegroundService.isRunning) {
                binding.tvServiceRunning.visibility = View.VISIBLE
                binding.tvStatus.text = "状态: 后台服务运行中"
                startStatusPolling()
                return@launch
            }

            // ===== 预览模式 =====
            val settings = SettingsManager(this@MainActivity)
            val nodeId = settings.getOrCreateNodeId()

            // 1. 相机预览（未绑定才绑定）
            if (cameraProvider == null) {
                startCameraPreview()
            }

            // 2. 预览模式 MQTT（检测框 + 上报同时出现）
            if (mqttManager == null) {
                val broker = settings.mqttBroker.first()
                Log.i(TAG, "Preview: connecting MQTT broker=$broker nodeId=$nodeId")
                mqttManager = MqttManager(nodeId)
                mqttManager?.connect(broker) {
                    Log.i(TAG, "Preview: MQTT Connected")
                    binding.tvStatus.text = "状态: 预览中 · MQTT 已连接（检测框+上报同时开启）"
                }
            }

            // 3. 模型后台并行加载（MediaPipe + TFLite 初始化约 2~5s）
            if (visionProcessor == null) {
                visionProcessor = withContext(Dispatchers.IO) {
                    VisionProcessorProvider.acquire(this@MainActivity, nodeId)
                }
                binding.tvStatus.text = "状态: 预览中 · 模型加载完成"
            }
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            // 1. 预览配置
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // 2. 分析配置 (RGBA_8888 方便预览绘制；限制分辨率降低端侧推理负载)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                // 推理为同步调用，必须放后台线程，避免主线程 ANR
                lifecycleScope.launch(Dispatchers.Default) {
                    val processor = visionProcessor
                    val result = if (processor != null) {
                        processor.processImage(imageProxy)
                    } else {
                        // 模型尚未加载完成：丢弃本帧
                        imageProxy.close()
                        null
                    }
                    result?.let {
                        runOnUiThread {
                            binding.overlayView.setResults(it.detections)
                        }
                        // 预览模式同步上报：检测框 + MQTT 同时出现
                        if (it.detections.isNotEmpty()) {
                            publishPreviewDetection(it)
                        }
                    }
                }
            }

            // 工业场景默认后置摄像头（条码/二维码扫描 + 现场人员感知）
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /** 预览模式上报（节流 5 FPS；Broker 未连接时丢弃，服务模式接管后由服务上报） */
    private fun publishPreviewDetection(message: VisionDetectionMessage) {
        if (mqttManager?.isConnected() != true) return
        val now = System.currentTimeMillis()
        if (now - lastPreviewSend < PREVIEW_PUBLISH_INTERVAL_MS) return
        lastPreviewSend = now
        mqttManager?.publishMessage("ind/vision/${message.node_id}/detection", message)
    }

    private fun startVisionService() {
        // 1. 释放预览资源（相机 + 预览 MQTT），避免与服务抢相机/同 clientId 互踢
        cameraProvider?.unbindAll()
        cameraProvider = null
        mqttManager?.disconnect()
        mqttManager = null
        binding.tvServiceRunning.visibility = View.VISIBLE
        binding.tvStatus.text = "状态: 后台服务启动中…"

        lifecycleScope.launch {
            delay(500) // 给相机硬件关闭留出时间
            val intent = Intent(this@MainActivity, VisionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            startStatusPolling()
        }
    }

    private fun stopVisionService() {
        statusPollJob?.cancel()
        val intent = Intent(this, VisionForegroundService::class.java)
        stopService(intent)
        binding.tvServiceRunning.visibility = View.GONE
        binding.tvStatus.text = "状态: 已停止后台服务"
        // 等服务销毁（isRunning=false）后恢复预览模式
        lifecycleScope.launch {
            delay(500)
            setupVisionEngine()
        }
    }

    /** 服务运行时每秒轮询 MQTT 连接状态并显示在界面 */
    private fun startStatusPolling() {
        if (statusPollJob?.isActive == true) return
        statusPollJob = lifecycleScope.launch {
            while (isActive) {
                binding.tvStatus.text =
                    if (VisionForegroundService.isConnected) {
                        "状态: 后台运行中 · MQTT 已连接"
                    } else {
                        "状态: 后台运行中 · MQTT 未连接（检查 Broker 地址/端口映射）"
                    }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statusPollJob?.cancel()
        cameraExecutor.shutdown()
        mqttManager?.disconnect()
        mqttManager = null
        VisionProcessorProvider.release() // 引用计数归零时才真正释放模型
        visionProcessor = null
        cameraProvider = null
    }
}
