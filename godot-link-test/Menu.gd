extends Control

# 开局菜单：玩家自己选"联机模式"（真实连 MetroSensorTester）还是
# "独立试玩"（不用手机采集数据，固定 60 秒跑 / 20 秒停节奏循环）。
# 取代了之前 Game.tscn / GameStandalone.tscn 两个场景文件 + 导出前手动改
# run/main_scene 的做法——现在只有一个 APK，进游戏先选模式。

@onready var connect_button: Button = $ConnectButton
@onready var standalone_button: Button = $StandaloneButton


func _ready() -> void:
	connect_button.pressed.connect(_on_connect_pressed)
	standalone_button.pressed.connect(_on_standalone_pressed)


func _on_connect_pressed() -> void:
	GameConfig.standalone_mode = false
	get_tree().change_scene_to_file("res://Game.tscn")


func _on_standalone_pressed() -> void:
	GameConfig.standalone_mode = true
	get_tree().change_scene_to_file("res://Game.tscn")
