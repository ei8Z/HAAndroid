# HAAndroid · TODO 强化清单

> 建立日期：2026-09-04 · 总索引见 `开发者信息/个人项目-待办与强化清单.md`
> 配套阅读：本仓库 `项目交接.md`（架构、8 条血泪教训、构建姿势）

---

## P0-① TFLite GPU→CPU **二次回退**补全（本次讨论的 C 项）

### 现状与缺口（已核实代码）

`app/src/main/java/com/ei8z/haandroid/vision/FaceRecognizer.kt`：

```kotlin
try {
    options.addDelegate(GpuDelegate())
} catch (t: Throwable) {          // ✅ 覆盖 delegate 创建失败（NoClassDefFoundError 等）
    Log.w("FaceRecognizer", "GPU delegate unavailable, fallback to CPU: $t")
    options.setNumThreads(4)
}
val modelBuffer = loadModelFile(context.assets, "mobilefacenet.tflite")
interpreter = Interpreter(modelBuffer, options)   // ⚠️ 这里若失败，只会被外层 catch(Exception) 吞掉
```

**缺口**：外层 `catch (e: Exception)`（约第 42 行）只打日志、把 `interpreter` 留在 `null`；之后 `recognize()` 直接返回空数组 —— **不会再退回 CPU 重试**。也就是说：
- 已覆盖：「GPU delegate 建不起来」→ 用 CPU ✅
- 未覆盖：「delegate 建起来了，但 Interpreter 应用它时失败」（不支持的算子 / 驱动不兼容）→ 模型直接不可用 ❌

### 改造方案（约 10–20 行）

把 Interpreter 构造抽成一次「先 GPU、失败再纯 CPU」的两段式：

```kotlin
private fun buildInterpreter(context: Context, buffer: ByteBuffer): Pair<Interpreter?, Boolean> {
    // 第一段：尝试 GPU
    runCatching {
        val gpuOptions = Interpreter.Options().apply { addDelegate(GpuDelegate()) }
        return Interpreter(buffer, gpuOptions) to true
    }.onFailure { Log.w(TAG, "GPU path failed (delegate or interpreter), retry on CPU: $it") }

    // 第二段：纯 CPU
    return runCatching {
        val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
        Interpreter(buffer, cpuOptions) to false
    }.onFailure { Log.e(TAG, "CPU path failed too", it) }.getOrNull()
}
```

要点：
1. `runCatching` 内部仍是 `Throwable` 语义（`Result` 捕获所有 Throwable），但**必须显式保留 `catch (Throwable)` 的注释**，说明为什么不能用 `Exception`
2. 记录当前生效路径（`isGpuActive` 字段），供日志/上报使用
3. 如果第一段 `Interpreter` 构造成功但 `run()` 时才失败（极少见），可在 `recognize()` 里加一次「异常 → 重建为 CPU 解释器 → 重试一次」的保护

### 验收标准

- [ ] 真机 logcat 能看到明确一行：`FaceRecognizer: using GPU delegate` 或 `using CPU (4 threads)`
- [ ] 人为制造 GPU 失败（如临时移除 `tensorflow-lite-gpu-api` 依赖，或在不支持 GPU 的设备上跑）→ 应用不崩溃、识别仍可用
- [ ] 顺带得到答案：**你这台设备走的到底是 GPU 还是 CPU**（若一直走 CPU，简历里就不要提"GPU 加速"，只讲"降级策略"）

---

## P0-② 真机复验两笔待验修复

- [ ] `125b072` 策略命令回调先行修复生效（下发 `set_detection_policy` → 观察到 `Detection policy updated → applied`）
- [ ] `0b49ef1` 启动不卡死修复生效（点「启动服务」→ 通知栏出现、相机绑定成功、无 ANR）
- 日志：`adb logcat -s MainActivity:VisionService:MqttManager:VisionProcessor *:S`

## P0-③ 推 GitHub + CI 首跑

- [ ] 建立远程 `ei8Z/HAAndroid` 并推送（`.github/workflows` 已配好，首次运行需验证徽章）
- [ ] 确认仓库内无密钥/敏感信息

## P1-④ 补单元测试（当前为零，面试硬伤）

优先顺序（纯逻辑、无需设备）：
1. 协议序列化：`VisionDetectionMessage` / `HeartbeatMessage` / `CommandMessage` 的 `encodeDefaults=false` 行为、`is_historical` 省略语义
2. 检测策略解析：`set_detection_policy` 的 JSON 解析与持久化
3. 节流逻辑：≤5FPS 上报节流、条码每 2 帧一次

## P2-⑤ 录双屏演示视频（约 2 分钟）

左：App 预览（检测框 + 识别内容）；右：`mosquitto_sub -t "ind/vision/#" -v` 终端报文。
流程：启动服务 → 扫条码 → 人员入镜 → 下发策略 → 断网 10 秒补报。脚本见 `开发者信息/Demo演示脚本.md`。

## P2-⑥ 技术债（审查遗留 G 系列）

- [ ] G2 无后摄时的降级提示
- [ ] G6 `kotlinOptions` 弃用告警
- [ ] G7 release 开启 R8
- [ ] G8 文案入 `strings.xml`
- [ ] G9 魔数提取常量
- [ ] G13 重启策略文档化
