# godot-link-test

`GameLinkServer`（`../README.md` 的“手机游戏联动”一节）的最小验证客户端，不是正式游戏，只用来确认 MetroSensorTester → 本地 socket → Godot 这条链路真的通。

用 Godot 4.x 打开这个文件夹（`project.godot`）即可。

## 跑法

**手机上跑（真实场景）**：把这个项目导出成 Android APK，装到跟 MetroSensorTester 同一台手机上。先在 MetroSensorTester 里点“开始采集”，再打开这个测试 app，应该能看到实时的 `state`/`train_moving`/`player_active` 等字段刷新。

**电脑上跑（开发时更快）**：手机用 USB 连电脑，MetroSensorTester 开始采集后，执行

```
adb forward tcp:8765 tcp:8765
```

然后直接在 Godot 编辑器里按 Play——电脑上的 `127.0.0.1:8765` 会被转发到手机的回环端口，不用每次改完 Godot 代码都重新打包 APK。

## 看到的数据不对/连不上怎么排查

- 确认 MetroSensorTester 已经点了“开始采集”（socket 一直监听，但只有采集中才会广播数据）。
- 用 `adb forward` 方案时确认端口转发没断（`adb forward --list` 能看到）。
- Godot 输出面板会 `print` 每一行收到的原始 JSON，方便对照 `../README.md` 里的字段说明。
