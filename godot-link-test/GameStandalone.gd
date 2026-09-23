extends "res://Game.gd"

# 独立试玩版入口：不连接/不依赖 MetroSensorTester，开局直接用固定节奏
# （运行 60 秒 / 停站 20 秒，见 Game.gd 的 standalone_run_seconds /
# standalone_station_seconds）循环下去，直到手动退出。
#
# 用途：没带着 MetroSensorTester 一起跑的时候也能玩、演示、或者单独验证
# 数值改动的手感，不用先去外面找地铁。跟正式版（Game.tscn/GameLink.gd 那条
# 真实数据链路）共用同一份 Game.gd 逻辑，只是把 standalone_mode 开关打开。
#
# 怎么用：
# - 编辑器里直接把这个场景设成当前场景运行（Godot 右上角场景切换，或者
#   F6/"运行当前场景"），不用改 project.godot 的 run/main_scene。
# - 要打包成单独一个 APK 给别人玩：把 project.godot 里
#   run/main_scene 临时改成 res://GameStandalone.tscn 再导出，导出完记得
#   改回 res://Game.tscn（正式版），避免下次忘了导出成独立版。

func _ready() -> void:
	standalone_mode = true
	super._ready()
