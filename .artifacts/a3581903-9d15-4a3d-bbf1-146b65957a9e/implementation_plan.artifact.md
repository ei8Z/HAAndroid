# HAAndroid Code Evolution Plan

Based on the `TODO-强化清单.md`, this plan outlines the technical implementation for strengthening the HAAndroid project, focusing on stability, testing, and technical debt.

## User Review Required

> [!IMPORTANT]
> The P0-① task involves changing the `FaceRecognizer` initialization to a two-stage fallback (GPU -> CPU). This is critical for device compatibility.
> I will also start adding Unit Tests (P1-④) which will help in future interviews and ensure logic correctness.

## Proposed Changes

### P0-① TFLite GPU→CPU Fallback Improvement

#### [MODIFY] [FaceRecognizer.kt](file:///Users/ei8z/Projects/android/HAAndroid/app/src/main/java/com/ei8z/haandroid/vision/FaceRecognizer.kt)
- Refactor the `init` block to use a dedicated `buildInterpreter` method.
- Implement two-stage fallback:
    1. Try building with `GpuDelegate`.
    2. If that fails (any `Throwable`), retry with 4 CPU threads.
- Add `isGpuActive` property for logging/reporting.
- Ensure `Throwable` is caught during delegate/interpreter creation to handle missing libraries or driver issues.

---

### P1-④ Unit Testing Infrastructure & Initial Tests

#### [MODIFY] [VisionDetectionMessage.kt](file:///Users/ei8z/Projects/android/HAAndroid/app/src/main/java/com/ei8z/haandroid/data/model/VisionDetection.kt)
- Ensure `@Serializable` and default values are correctly handled for tests.

#### [NEW] [ModelSerializationTest.kt](file:///Users/ei8z/Projects/android/HAAndroid/app/src/test/java/com/ei8z/haandroid/data/model/ModelSerializationTest.kt)
- Test JSON serialization/deserialization of `VisionDetectionMessage`, `HeartbeatMessage`, and `CommandMessage`.
- Verify `encodeDefaults = false` behavior.

---

### P2-⑥ Technical Debt & Modernization

#### [MODIFY] [build.gradle.kts](file:///Users/ei8z/Projects/android/HAAndroid/app/build.gradle.kts)
- Update `kotlinOptions` to use `compilerOptions` (addressing G6).
- Enable R8/Minification for release builds (addressing G7).

#### [MODIFY] [FaceRecognizer.kt](file:///Users/ei8z/Projects/android/HAAndroid/app/src/main/java/com/ei8z/haandroid/vision/FaceRecognizer.kt)
- Extract magic numbers (input size, thread count) to constants (addressing G9).

## Verification Plan

### Automated Tests
- Run `./gradlew test` to verify serialization logic.

### Manual Verification
- Deploy to device and check logcat for `FaceRecognizer` initialization messages:
    - `using GPU delegate` or `using CPU (4 threads)`.
- Verify that the app still works if GPU is unavailable.
