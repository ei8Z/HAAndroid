# 工业视觉感知终端 - MQTT 对接文档

> **版本**: v2.0 (Industrial Edition)
> **更新日期**: 2026-08-03

---

## 📋 目录

1. [MQTT 连接配置](#mqtt-连接配置)
2. [Android 上行消息](#android-上行消息)
3. [接收调度指令](#接收调度指令)
4. [调试与验证](#调试与验证)

---

## 🔌 MQTT 连接配置

### Broker 信息

| 配置项 | 值 |
|-------|---|
| Broker 地址 | 内网 Mosquitto / EMQX（开发默认 `tcp://192.168.0.3:1883`） |
| 端口 | `1883` |
| Client ID 格式 | `ind_vision_${node_id}` |
| Keep Alive | `60` 秒 |
| Clean Session | `false` |
| 自动重连 | `true`（isAutomaticReconnect，断线自动恢复） |

### IP 地址配置（真机测试）

Android 模拟器/真机无法访问 `localhost`：

| 场景 | IP 地址 |
|-----|---------|
| 模拟器 | `10.0.2.2` |
| 同一 WiFi 真机 | 电脑的局域网 IP (`192.168.x.x`) |

**快速获取电脑 IP**:

```bash
# macOS
ipconfig getifaddr en0

# Linux
hostname -I
```

---

## 📤 Android 上行消息

### 1. 状态消息（连接/断开）

| Topic | Payload | QoS | Retained |
|-------|---------|-----|----------|
| `ind/vision/${node_id}/status` | `"online"` / `"offline"` | 1 | true |

### 2. 心跳消息（每 30 秒）

| Topic | Payload | 频率 |
|-------|---------|------|
| `ind/vision/${node_id}/heartbeat` | HeartbeatMessage | 30s |

```json
{
  "node_id": "line_a_01",
  "timestamp": 1752700000000,
  "battery_level": 85,
  "is_charging": false,
  "version": "2.0-industrial"
}
```

### 3. 视觉检测结果（核心）

| Topic | Payload | 触发条件 |
|-------|---------|---------|
| `ind/vision/${node_id}/detection` | VisionDetectionMessage | 检出目标 / 最高 5 FPS |

**条码检出**:

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
  "environment": { "location": "line_a" }
}
```

**人员检出**:

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

**字段说明**:

| 字段 | 类型 | 说明 |
|-----|------|------|
| `node_id` | string | 节点标识（如 `line_a_01`、`warehouse_02`） |
| `timestamp` | long | Unix 时间戳（毫秒） |
| `detections[].type` | string | `"person"` / `"barcode"` |
| `detections[].value` | string? | 条码解码内容（type=barcode） |
| `detections[].format` | string? | 制式：QR_CODE / CODE_128 / EAN_13 ... |
| `detections[].identity` | string? | 人员身份特征向量（后端匹配） |
| `detections[].confidence` | float? | person: 置信度 0-1；barcode: null（无置信度语义） |
| `detections[].bbox` | list? | 归一化边界框 [x, y, w, h] |
| `is_historical` | boolean | 离线补报标记；**实时消息省略此字段（默认 false）** |

> 离线补报：MQTT 断连时检测结果写入 Room（上限 1000 条 FIFO），自动重连后以 `is_historical=true` 补报；补报走 QoS1，Broker 确认送达后才删除本地缓存。

---

## 📥 接收调度指令

调度系统（MES / 调度平台）下发指令到 `ind/command/#`：

| command | 参数 | 说明 |
|---------|------|------|
| `set_detection_policy` | `enable_person`, `enable_barcode` | 动态开关检测项（持久化） |
| `capture_event` | `event_id` | 现场事件留痕 |
| `send_alert` | `alert_type`, `message`, `severity` | 终端安全告警 |
| `update_config` | 任意键值 | 配置热更新 |

### 示例：下班后关闭人员感知、仅保留扫码

```bash
mosquitto_pub -h 192.168.0.3 -t "ind/command/policy" -m '{
  "command": "set_detection_policy",
  "target": "line_a_01",
  "parameters": { "enable_person": false, "enable_barcode": true },
  "priority": "normal",
  "timestamp": 1752700000000
}'
```

---

## 🐛 调试与验证

### 1. 订阅所有上行消息

```bash
mosquitto_sub -h 192.168.0.3 -t "ind/vision/#" -v
```

### 2. 验证 LWT 状态

```bash
# 终端在线时：
mosquitto_sub -h 192.168.0.3 -t "ind/vision/+/status" -v
# → ind/vision/line_a_01/status online

# 强杀进程 / 断网后（Broker 自动发布遗嘱）：
# → ind/vision/line_a_01/status offline
```

### 3. 验证动态策略

```bash
# 关闭条码扫描 → 终端日志出现 "Detection policy applied"
mosquitto_pub -h 192.168.0.3 -t "ind/command/policy" -m '{"command":"set_detection_policy","target":"line_a_01","parameters":{"enable_person":true,"enable_barcode":false},"timestamp":1752700000000}'
```

---

## 常见问题

| 问题 | 处理 |
|------|------|
| 真机连不上 Broker | 检查同一 WiFi；`ipconfig getifaddr en0` 获取电脑 IP 替换 `192.168.0.3` |
| 模拟器连不上 | 使用 `10.0.2.2` |
| 检测结果不推送 | 确认 `enable_barcode/enable_person` 策略；确认 App 在前台服务状态 |
| 离线数据不补报 | 补报是客户端主动重发（QoS1）：确认 `CleanSession=false`、自动重连已启用；重连后最长 5s 内触发 |
