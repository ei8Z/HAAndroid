package com.ei8z.haandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
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
import com.ei8z.haandroid.data.SettingsManager
import com.ei8z.haandroid.databinding.ActivityMainBinding
import com.ei8z.haandroid.service.VisionForegroundService
import com.ei8z.haandroid.vision.VisionProcessor
import com.ei8z.haandroid.vision.VisionProcessorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var visionProcessor: VisionProcessor? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

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
     * 相机所有权约定（避免 CameraX unbindAll 双组件互杀）：
     * - 服务未运行：Activity 持有相机做本地预览；
     * - 服务运行中：相机由前台服务持有，Activity 只显示状态。
     */
    private fun setupVisionEngine() {
        lifecycleScope.launch {
            if (VisionForegroundService.isRunning) {
                binding.tvStatus.text = "状态: 后台服务运行中（相机由服务持有）"
                return@launch
            }
            if (visionProcessor != null) return@launch // 已初始化，避免重复绑定
            val settings = SettingsManager(this@MainActivity)
            val nodeId = settings.getOrCreateNodeId()
            // 模型加载为 IO 密集操作；与前台服务共享同一实例（引用计数）
            visionProcessor = withContext(Dispatchers.IO) {
                VisionProcessorProvider.acquire(this@MainActivity, nodeId)
            }
            startCameraPreview()
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

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
                    val result = visionProcessor?.processImage(imageProxy)
                    result?.let {
                        // 在主线程更新 UI 遮罩层
                        runOnUiThread {
                            binding.overlayView.setResults(it.detections)
                        }
                    }
                }
            }

            // 工业场景默认后置摄像头（条码/二维码扫描 + 现场人员感知）
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startVisionService() {
        // 先释放 Activity 的相机占用，避免与服务抢占 CameraX
        ProcessCameraProvider.getInstance(this).addListener({
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        }, ContextCompat.getMainExecutor(this))

        val intent = Intent(this, VisionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.tvStatus.text = "状态: 后台服务启动中…"
    }

    private fun stopVisionService() {
        val intent = Intent(this, VisionForegroundService::class.java)
        stopService(intent)
        // 等服务销毁（isRunning=false）后恢复本地预览
        lifecycleScope.launch {
            delay(400)
            setupVisionEngine()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        VisionProcessorProvider.release() // 引用计数归零时才真正释放模型
        visionProcessor = null
    }
}
