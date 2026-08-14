package com.ei8z.haandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ei8z.haandroid.data.SettingsManager
import com.ei8z.haandroid.databinding.ActivityMainBinding
import com.ei8z.haandroid.service.VisionForegroundService
import com.ei8z.haandroid.vision.VisionProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            binding.tvStatus.text = "状态: 后台服务已启动"
        }

        binding.btnStopService.setOnClickListener {
            stopVisionService()
            binding.tvStatus.text = "状态: 后台服务已停止"
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

    private fun setupVisionEngine() {
        lifecycleScope.launch {
            val settings = SettingsManager(this@MainActivity)
            val nodeId = settings.nodeId.first()
            visionProcessor = VisionProcessor(this@MainActivity, nodeId)
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

            // 2. 分析配置 (RGBA_8888 方便预览绘制)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                lifecycleScope.launch {
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
        val intent = Intent(this, VisionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVisionService() {
        val intent = Intent(this, VisionForegroundService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        visionProcessor?.release()
    }
}
