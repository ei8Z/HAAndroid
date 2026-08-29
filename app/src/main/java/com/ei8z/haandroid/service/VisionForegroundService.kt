package com.ei8z.haandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ei8z.haandroid.R
import com.ei8z.haandroid.data.DetectionPolicy
import com.ei8z.haandroid.data.SettingsManager
import com.ei8z.haandroid.data.db.AppDatabase
import com.ei8z.haandroid.data.db.OfflineDetection
import com.ei8z.haandroid.data.model.CommandMessage
import com.ei8z.haandroid.data.model.HeartbeatMessage
import com.ei8z.haandroid.net.MqttManager
import com.ei8z.haandroid.vision.VisionProcessor
import com.ei8z.haandroid.vision.VisionProcessorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

class VisionForegroundService : LifecycleService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var database: AppDatabase
    private var mqttManager: MqttManager? = null
    private var visionProcessor: VisionProcessor? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // 用于节流：记录上次发送消息的时间
    private var lastSendTime = 0L
    private var minSendInterval = 200L // 由 DETECTION_FPS 配置驱动，默认 5 FPS

    private val commandListener = object : MqttManager.CommandListener {
        override fun onCommandReceived(command: CommandMessage) {
            Log.d(TAG, "Received command: ${command.command} on ${command.target}")
            when (command.command) {
                "set_detection_policy" -> handleSetDetectionPolicy(command)
                "capture_event" -> handleCaptureEvent(command)
                "send_alert" -> handleAlertCommand(command)
                "update_config" -> handleConfigUpdate(command)
                else -> Log.w(TAG, "Unknown command: ${command.command}")
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "vision_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val TAG = "VisionService"

        /** 供 MainActivity 判断相机所有权（避免 CameraX 双组件互杀） */
        @Volatile
        var isRunning = false
            private set

        /** MQTT 连接状态（供 Activity 界面轮询显示） */
        @Volatile
        var isConnected = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settingsManager = SettingsManager(this)
        database = AppDatabase.getDatabase(this)

        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        initCore()
    }

    private fun initCore() {
        lifecycleScope.launch {
            val nodeId = settingsManager.getOrCreateNodeId()
            val brokerUrl = settingsManager.mqttBroker.first()
            val fps = settingsManager.detectionFps.first().coerceIn(1, 10)
            minSendInterval = 1000L / fps

            visionProcessor = withContext(Dispatchers.IO) {
                // 与 Activity 预览共享同一模型实例（引用计数管理）
                VisionProcessorProvider.acquire(this@VisionForegroundService, nodeId)
            }
            mqttManager = MqttManager(this@VisionForegroundService, nodeId)

            // 收集并应用检测策略（本地默认值 + MQTT 命令动态下发）
            launch {
                settingsManager.detectionPolicy.collect { policy ->
                    visionProcessor?.enablePersonDetection = policy.enablePerson
                    visionProcessor?.enableBarcodeScanning = policy.enableBarcode
                    Log.i(TAG, "Detection policy applied: $policy")
                }
            }

            // 1. MQTT 优先连接（先确认网络链路，相机随后启动）
            mqttManager?.connect(brokerUrl) {
                Log.i(TAG, "MQTT Connected")
                isConnected = true
                mqttManager?.subscribeToCommands(commandListener, nodeId)
            }

            // 心跳与补报任务无条件启动：循环内自带 isConnected 判断，
            // 首次连接失败后，自动重连成功即可恢复上报
            startSyncTask()
            startHeartbeatTask(nodeId)
            startStatusReporter()

            // 2. 相机延迟启动：给 Activity 端相机释放留出时间，避免 HAL 重开卡死
            launch {
                delay(400)
                startCamera()
            }
        }
    }

    /** 每 2 秒同步连接状态（Activity 界面轮询 + 心跳之间的中间粒度） */
    private fun startStatusReporter() {
        lifecycleScope.launch {
            while (isActive) {
                isConnected = mqttManager?.isConnected() == true
                delay(2000)
            }
        }
    }

    /**
     * 动态检测策略：调度系统可通过 MQTT 下发，控制终端是否执行人员感知 / 条码扫描。
     * 策略持久化到 DataStore，断网重启后仍生效。
     */
    private fun handleSetDetectionPolicy(command: CommandMessage) {
        val enablePerson = command.parameters["enable_person"]?.jsonPrimitive?.booleanOrNull ?: true
        val enableBarcode = command.parameters["enable_barcode"]?.jsonPrimitive?.booleanOrNull ?: true
        lifecycleScope.launch {
            settingsManager.updateDetectionPolicy(DetectionPolicy(enablePerson, enableBarcode))
        }
        Log.i(TAG, "Detection policy updated: person=$enablePerson, barcode=$enableBarcode")
    }

    /** 事件留痕（演示级桩：现场扫码/感知事件由调度系统记录审计） */
    private fun handleCaptureEvent(command: CommandMessage) {
        val eventId = command.parameters["event_id"]?.jsonPrimitive?.contentOrNull ?: ""
        Log.i(TAG, "Capture event logged: id=$eventId, target=${command.target}")
    }

    private fun handleAlertCommand(command: CommandMessage) {
        val alertType = command.parameters["alert_type"]?.jsonPrimitive?.contentOrNull ?: "info"
        val message = command.parameters["message"]?.jsonPrimitive?.contentOrNull ?: ""
        val severity = command.parameters["severity"]?.jsonPrimitive?.contentOrNull ?: "low"
        updateNotificationWithAlert(alertType, message, severity)
    }

    private fun handleConfigUpdate(command: CommandMessage) {
        // 演示级桩：正式版在此处热更新节点配置（阈值/FPS/模型版本）
        Log.i(TAG, "Handling dynamic config update: ${command.parameters}")
    }

    private fun updateNotificationWithAlert(type: String, message: String, severity: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = when(severity) {
            "critical", "high" -> "⚠️ 紧急安全警报"
            "medium" -> "🔔 状态提醒"
            else -> "ℹ️ 系统消息"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("[$type] $message")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(when(severity) {
                "critical", "high" -> NotificationCompat.PRIORITY_HIGH
                else -> NotificationCompat.PRIORITY_DEFAULT
            })
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun startHeartbeatTask(nodeId: String) {
        lifecycleScope.launch {
            while (isActive) {
                if (mqttManager?.isConnected() == true) {
                    val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    val heartbeat = HeartbeatMessage(
                        node_id = nodeId,
                        timestamp = System.currentTimeMillis(),
                        battery_level = level,
                        is_charging = isCharging,
                        version = "2.0-industrial"
                    )
                    mqttManager?.publishMessage("ind/vision/$nodeId/heartbeat", heartbeat)
                }
                delay(30000)
            }
        }
    }

    private fun startSyncTask() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = database.offlineDetectionDao()
            while (isActive) {
                if (mqttManager?.isConnected() == true) {
                    val offlineRecords = dao.getOldestDetections()
                    if (offlineRecords.isNotEmpty()) {
                        for (record in offlineRecords) {
                            try {
                                val originalMsg = Json.decodeFromString<com.ei8z.haandroid.data.model.VisionDetectionMessage>(record.jsonContent)
                                val historicalMsg = originalMsg.copy(is_historical = true)
                                val payload = Json.encodeToString(historicalMsg)
                                // QoS1 + Broker 确认送达后才删除缓存，避免瞬时断连丢数据
                                mqttManager?.publishWithCallback(
                                    "ind/vision/${historicalMsg.node_id}/detection",
                                    payload,
                                    qos = 1
                                ) {
                                    dao.deleteByIds(listOf(record.id))
                                }
                                delay(100)
                            } catch (e: Exception) {
                                Log.e(TAG, "Sync record failed", e)
                            }
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

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
                // 推理在后台线程执行，避免主线程阻塞（MediaPipe/TFLite 为同步调用）
                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        val currentTime = System.currentTimeMillis()
                        // 1. 核心处理逻辑（ImageProxy 由 processor 全权关闭）
                        val result = visionProcessor?.processImage(imageProxy)

                        // 2. 只有在检测到物体且满足节流时间的情况下才发送
                        if (result != null && result.detections.isNotEmpty()) {
                            if (currentTime - lastSendTime >= minSendInterval) {
                                if (mqttManager?.isConnected() == true) {
                                    mqttManager?.publishMessage("ind/vision/${result.node_id}/detection", result)
                                    lastSendTime = currentTime
                                } else {
                                    cacheOfflineData(result)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        /*Log.e(TAG, "Processing error", e)*/
                    }
                }
            }

            // 工业场景默认后置摄像头（条码扫描 + 现场人员感知）
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // 绑定重试：部分设备重开相机偶发失败，稍候重试（bindToLifecycle 需主线程）
            lifecycleScope.launch {
                for (attempt in 1..3) {
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            this@VisionForegroundService,
                            cameraSelector,
                            imageAnalysis
                        )
                        Log.i(TAG, "Camera bound (attempt $attempt)")
                        return@launch
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed (attempt $attempt)", e)
                        delay(800)
                    }
                }
                Log.e(TAG, "Camera binding failed after 3 attempts")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun cacheOfflineData(message: com.ei8z.haandroid.data.model.VisionDetectionMessage) {
        withContext(Dispatchers.IO) {
            val dao = database.offlineDetectionDao()
            if (dao.getCount() >= 1000) {
                dao.deleteOldest(1)
            }
            dao.insert(OfflineDetection(
                timestamp = message.timestamp,
                jsonContent = Json.encodeToString(message)
            ))
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "工业感知服务", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("工业感知节点运行中")
            .setContentText("正在扫描现场条码与人员信息…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HAAndroid::IndustrialVisionWakeLock")
        wakeLock?.acquire()
    }

    override fun onDestroy() {
        mqttManager?.disconnect()
        cameraProvider?.unbindAll()
        cameraProvider = null
        VisionProcessorProvider.release() // 引用计数归零时才真正释放模型
        visionProcessor = null
        wakeLock?.release()
        cameraExecutor.shutdown()
        isRunning = false
        isConnected = false
        super.onDestroy()
    }
}
