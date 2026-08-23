# 工业视觉感知 (EdgeVision Industrial)

> **工业现场边缘视觉感知终端** —— 一台 Android 设备 = 扫码枪 + 在岗检测 + 传感上报节点

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-24-green)](https://developer.android.com)
[![CI](https://github.com/ei8Z/HAAndroid/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ei8Z/HAAndroid/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange)](https://www.apache.org/licenses/LICENSE-2.0)

## 它解决什么问题

工业现场需要大量**感知末梢**：产线扫码、在岗检测、物料标签核验、事件留痕。传统方案依赖专用硬件（扫码枪/工业相机）和云端识别，成本高、依赖公网。本项目用**普通 Android 设备**（PDA / 工位平板 / 巡检手机）作为边缘计算节点：

- **端侧全离线推理**：条码解码（ML Kit）+ 人员感知（MediaPipe + TFLite）全部跑在本地，适配工业内网；
- **标准 MQTT 接入**：结构化检测结果实时上报 MES / 调度系统，支持断网缓存与自动补报；
- **远程可治理**：调度系统可通过 MQTT 命令动态开关检测项、下发告警。

## 架构

```
┌───────────────────────────────────────────────────────────┐
│                    工业调度系统 / MES                        │
│            (MQTT Broker: mosquitto / EMQX)                 │
└───────────────▲───────────────────────────┬───────────────┘
     上行(ind/vision/{id}/...)         下行(ind/command/#)
                │                           │
┌───────────────┴───────────────────────────▼───────────────┐
│                 VisionForegroundService                    │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │  VisionProcessor（串行流水线）    │  │   MqttManager   │   │
│  │  ├ MediaPipe FaceDetector (GPU) │  │  LWT/心跳/补报    │   │
│  │  ├ MobileFaceNet Embedding      │  └─────────────────┘   │
│  │  └ ML Kit BarcodeScanning       │                        │
│  └──────────────┴──────────────────┘   ┌─────────────────┐  │
│  CameraX ImageAnalysis (RGBA_8888)      │ Room 离线缓存     │  │
│  ForegroundService(type=camera)         │ (1000条 FIFO)    │  │
│  WakeLock 灭屏推理                      │ DataStore 策略    │  │
└────────────────────────────────────────┴──────────────────┘  │
```

## 核心能力

| 能力 | 实现 | 亮点 |
|------|------|------|
| 条码/二维码解码 | ML Kit standalone（全格式） | 模型随 APK 打包，无 GMS 依赖，内网可用 |
| 人员感知 | MediaPipe blaze_face + MobileFaceNet | 输出 192 维身份特征向量，后端匹配身份 |
| 常驻感知 | ForegroundService(type=camera) + WakeLock | Android 14+ 合规，灭屏持续运行 |
| 断网续传 | Room 1000 条 FIFO + MQTT 补报 | 重连后自动补报，`is_historical` 标记 |
| 动态策略 | MQTT `set_detection_policy` | 调度系统远程开关检测项，策略持久化 |
| 状态同步 | MQTT LWT 遗嘱消息 | 节点在线/离线实时可见 |

## 技术决策（Why）

- **为什么 ML Kit 而不是 ZXing？** ML Kit 对模糊、低光、多码同帧场景检出率更高，且免调参；standalone 版本离线可用，符合工业内网约束。
- **为什么 MQTT 而不是 HTTP 上报？** 长连接、QoS 分级、遗嘱消息、Broker 广播，是工业 IoT 的事实标准；终端无需暴露 IP。
- **为什么 Embedding 上报而不是本地身份库？** 身份库由后端统一管理（员工变动/权限），终端只做特征提取，避免敏感数据驻留边缘设备。
- **为什么条码扫描每 2 帧执行一次？** 单帧互斥锁保证不堆积；条码解码与人员检测共享算力，节流后在 5 FPS 上报节奏下仍能稳定检出。

## 快速开始

1. 准备一台 Android 设备（Android 7.0+）与 MQTT Broker：

```bash
# macOS 本地调试
brew install mosquitto && mosquitto -p 1883
```

2. 修改 Broker 地址：`data/SettingsManager.kt` 中默认 `tcp://192.168.0.3:1883`（真机使用电脑局域网 IP，模拟器用 `10.0.2.2`）。
3. 构建安装：

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

4. 打开 App → 授权相机 → 点击「启动服务」，观察日志与 Broker 订阅：

```bash
mosquitto_sub -h 127.0.0.1 -t "ind/vision/#" -v
```

5. 用电脑屏幕显示一个条码/二维码（工单、物料标签），对准摄像头即可看到检出结果与青色框标注。

## MQTT 协议

完整协议见 [API_SPEC.md](API_SPEC.md) 与 [MQTT_INTEGRATION_GUIDE.md](MQTT_INTEGRATION_GUIDE.md)。

- 上行：`ind/vision/{node_id}/status | heartbeat | detection`
- 下行：`ind/command/#`（`set_detection_policy` / `capture_event` / `send_alert` / `update_config`）

## 后续路线

- [ ] 安全装备检测（安全帽/反光衣，TFLite object detection）
- [ ] 姿态估计识别倒地/越界安全事件
- [ ] Web 控制台多节点策略管理与 OTA
- [ ] 扫码结果直接驱动 AGV 任务下发（`ind/agv/{id}/task`）

## License

Apache License 2.0
