# 工业现场边缘视觉感知终端 - 接口规范文档 (Industrial Edition)

> **版本**: v2.0 (Industrial Edition)  
> **更新日期**: 2026-08-03  
> **状态**: 已实现

---

## 📋 概述

本项目是工业现场的**边缘视觉感知终端**：Android 设备作为边缘计算节点，通过 CameraX 获取视频流，在端侧并行执行**条码/二维码解码（ML Kit）**与**人员感知（MediaPipe + TFLite）**，并通过 MQTT 将结构化语义数据上报给 MES / 调度系统 / 数据中台。

相比云端识别方案，端侧推理具有三个工业场景刚需特性：

1. **离线可用**：条码与人员模型全部打包在 APK 内，无公网依赖，适配工业内网环境；
2. **低延迟**：解码在端侧完成，单帧 < 100ms，适合扫码枪替代与在岗检测场景；
3. **低成本**：复用产线现有 Android 设备（PDA/工位平板/巡检手机），无需专用相机模组。

### 核心能力

| 能力 | 实现 | 说明 |
|------|------|------|
| 条码/二维码解码 | ML Kit BarcodeScanning（standalone） | 全格式支持，托盘/物料/工单标签 |
| 人员感知 | MediaPipe blaze_face + MobileFaceNet | 输出身份特征向量（Embedding），由后端匹配身份 |
| 常驻感知 | ForegroundService(type=camera) + WakeLock | 灭屏持续运行 |
| 高可靠性 | Room 离线缓存 1000 条 FIFO | 网络恢复后自动补报（is_historical 标记） |
| 状态同步 | MQTT LWT 遗嘱消息 | 实时在线/离线感知 |
| 动态策略 | MQTT 命令 `set_detection_policy` | 调度系统可远程开关条码/人员检测，策略持久化 |

---

## 🔌 通信协议

### 1. MQTT 连接与状态 (LWT)

**连接配置**:

- **ClientID**: `ind_vision_${node_id}`
- **CleanSession**: `false` (确保离线期间指令不丢失)
- **AutomaticReconnect**: `true`（断线自动重连，配合 CleanSession=false 实现离线补报）
- **KeepAlive**: `60s`

**在线状态同步 (Standard LWT)**:

- **Status Topic**: `ind/vision/${node_id}/status`
- **Connect Payload**: `online` (Retained: true)
- **Last Will Message**:
    - **Topic**: `ind/vision/${node_id}/status`
    - **Payload**: `offline`
    - **QoS**: 1
    - **Retained**: true

### 2. 主题总览

| 方向 | Topic | 频率 | 说明 |
|------|-------|------|------|
| 上行 | `ind/vision/${node_id}/status` | 事件触发 | 在线/离线（retained） |
| 上行 | `ind/vision/${node_id}/heartbeat` | 30s | 电量/充电状态/固件版本 |
| 上行 | `ind/vision/${node_id}/detection` | 检出触发，最高 5 FPS | 视觉检测结果 |
| 下行 | `ind/command/#` | 事件触发 | 调度指令（策略/事件/告警） |
| 下行 | `ind/vision/${node_id}/config` | 事件触发 | 节点配置更新 |

### 3. 发布消息格式 (Kotlin Serialization)

所有消息统一使用 `kotlinx.serialization` 序列化。

#### A. 视觉检测消息 (Detection)

**Topic**: `ind/vision/${node_id}/detection`  
**频率**: 检出触发或最高 5 FPS。

```kotlin
@Serializable
data class VisionDetectionMessage(
    val node_id: String,
    val timestamp: Long,        // Unix 时间戳 (ms)
    val detections: List<Detection>,
    val environment: Environment? = null,
    val is_historical: Boolean = false // 是否为离线补报数据
)

@Serializable
data class Detection(
    val type: String,           // "person" | "barcode"
    val identity: String? = null,   // person: 身份特征向量（逗号分隔）
    val confidence: Float? = null,  // person: 置信度 0-1；barcode: null（无置信度语义）
    val bbox: List<Float>? = null,  // 归一化 [x, y, w, h]
    val action: String? = null,     // 人员行为描述（预留）
    val landmarks: List<Point>? = null,
    val value: String? = null,  // barcode: 解码内容
    val format: String? = null, // barcode: 制式 QR_CODE/CODE_128/EAN_13...
)
```

**条码检出示例**:

```json
{
  "node_id": "line_a_01",
  "timestamp": 1752700000000,
  "detections": [
    {
      "type": "barcode",
      "value": "WO-20260803-0017",
      "format": "CODE_128",
      "confidence": null,
      "bbox": [0.31, 0.42, 0.26, 0.18]
    }
  ],
  "environment": { "location": "line_a", "light_level": null },
  "is_historical": false
}
```

**人员检出示例**:

```json
{
  "node_id": "line_a_01",
  "timestamp": 1752700001000,
  "detections": [
    {
      "type": "person",
      "identity": "0.0231,-0.4582,0.9123,...",
      "confidence": 0.96,
      "bbox": [0.52, 0.20, 0.15, 0.30]
    }
  ]
}
```

> 序列化规则：`encodeDefaults=false`，默认值字段不输出——`is_historical=false` 省略即表示实时消息；`environment.light_level` 未采集时省略。
```

#### B. 心跳与元数据 (Heartbeat)

**Topic**: `ind/vision/${node_id}/heartbeat`  
**频率**: 30s/次。

```json
{
  "node_id": "line_a_01",
  "timestamp": 1752700000000,
  "battery_level": 85,
  "is_charging": false,
  "version": "2.0-industrial"
}
```

### 4. 接收指令

**Topic**: `ind/command/#`（QoS 1）

| command | 参数 | 说明 |
|---------|------|------|
| `set_detection_policy` | `enable_person`(bool), `enable_barcode`(bool) | 动态开关检测项，**持久化到 DataStore** |
| `capture_event` | `event_id`(string) | 现场事件留痕（演示级桩，正式版接审计系统） |
| `send_alert` | `alert_type`, `message`, `severity` | 终端侧安全告警（通知栏） |
| `update_config` | 任意键值 | 配置热更新（演示级桩） |

```json
{
  "command": "set_detection_policy",
  "target": "line_a_01",
  "parameters": {
    "enable_person": true,
    "enable_barcode": true
  },
  "priority": "normal",
  "timestamp": 1752700000000
}
```

---

## 🛠️ 技术栈与实现规范

### 1. 运行模式：VisionForegroundService

- **前台服务**: 不可移除通知栏，`foregroundServiceType="camera"`（Android 14+ 合规）
- **电源管理**: `PARTIAL_WAKE_LOCK` 支持灭屏推理
- **CameraX**: 绑定到 Service 生命周期，与 Activity 预览解耦

### 2. 视觉处理流水线 (VisionProcessor)

- **串行节流**: 互斥锁保证单帧单处理；条码扫描每 2 帧执行一次，平衡算力与检出率
- **人员感知**: MediaPipe FaceDetector（GPU delegate）→ 裁剪人脸 → MobileFaceNet 提取 192 维 Embedding
- **条码解码**: ML Kit standalone，全格式；与 MediaPipe 管线互不阻塞
- **动态策略**: `enablePersonDetection` / `enableBarcodeScanning` 由 DataStore Flow 驱动

### 3. 数据持久化

- **Room**: `offline_detections` 表，1000 条 FIFO；补报走 QoS1，**Broker 确认送达后才删除**缓存
- **DataStore**: NodeID（首次生成即落盘，进程间一致）/ Broker / FPS / 检测策略

---

## 📂 模型文件路径

- **人员检测**: `app/src/main/assets/blaze_face_short_range.tflite`
- **身份特征**: `app/src/main/assets/mobilefacenet.tflite`
- **条码解码**: ML Kit standalone（随 APK 打包，无需下载）

---

## 🔐 权限要求

- `android.permission.CAMERA`
- `android.permission.FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA`
- `android.permission.WAKE_LOCK`
- `android.permission.POST_NOTIFICATIONS` (Android 13+)
- `android.permission.INTERNET`

---

## 🚀 后续路线

1. **安全装备检测**：安全帽/反光衣检测模型（TFLite object detection）
2. **行为识别**：姿态估计（PoseLandmarker）识别倒地/越界等安全事件
3. **多节点管理**：Web 控制台批量下发策略与 OTA
4. **与 AGV 联动**：扫码结果直接驱动 `ind/agv/{id}/task` 任务下发
