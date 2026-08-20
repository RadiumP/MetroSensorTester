# Metro Sensor Tester

原生 Kotlin/Android 地铁与轻轨传感器采集原型。

## 功能

- 线性加速度（无对应传感器时使用加速度计高通滤波）
- 陀螺仪，并以持机旋转过滤加速度污染
- 磁力计原始 XYZ 与磁场强度
- 气压计
- 麦克风 RMS、峰值与当前实际输入设备
- 使用 Android `AudioRecord` 归一化 PCM16 RMS 固定阈值推断运行/停站
- 停站信号连续 3 秒后确认，并区分“停站”与“停站但玩家活动”
- 人工标记运行和停站
- 使用系统文件选择器导出 UTF-8 CSV（状态字段 schema v2）

## 打开与运行

1. 使用 Android Studio 打开本目录。
2. 等待 Gradle Sync 完成。
3. 用 USB 连接手机并允许 USB 调试。
4. 选择手机后点击 Run。

项目使用 JDK 17、compileSdk 34，最低支持 Android 8.0（API 26）。

## Cocos 环境路径

```text
SDK: C:\Users\38061\AppData\Local\Android\Sdk
NDK: C:\Users\38061\AppData\Local\Android\Sdk\ndk\23.1.7779620
```

当前版本仅在 Activity 保持打开时采集；后台前台服务将在确有需要后加入。

## 状态判定

- 麦克风 RMS 不低于 `0.0030` 时判定运行，不高于 `0.0014` 时进入停站候选，中间区间保持当前列车状态。
- 停站候选必须连续保持 3 秒；运行信号会立即取消候选。
- 玩家活动独立使用加速度 RMS（`0.40 m/s²`）或陀螺仪 RMS（`15 °/s`）判断，不再污染列车状态。
- CSV 的 `state` 为最终展示状态，`train_state` 只表示列车运行/停站，`player_state` 单独表示玩家活动。
