extends Control

# Rice Rider — 基础主循环 v0
#
# 状态机：校准中(CALIBRATING) -> 运行(RACE，躲避竞速) -> 停站(STATION，三选一补给)
# -> ... 循环 ... -> 断线/下车(SUMMARY，结算)。
#
# 这一版只做主循环骨架，先验证"真实数据映射到游戏节奏"这件事本身好不好玩：
# 占位方块代替美术，飞行射击彩蛋和完整 Roguelite 卡池都留到下一步。
# 完整设计见仓库根目录 RICE_RIDER_DESIGN.md。
#
# 操作：点/触屏幕左半边=换到左边车道，右半边=换到右边车道。

# ---- 实地测试后需要回来调的常量 ----
# accel_rms / gyro_rms_deg_s 的真实取值范围目前是拍的，先跑几趟地铁，对照
# DebugLabel 里显示的原始数值和 intensity，再回来改这四个 min/max。
@export var accel_rms_min: float = 0.0
@export var accel_rms_max: float = 3.0
@export var gyro_rms_min: float = 0.0
@export var gyro_rms_max: float = 30.0

@export var base_obstacle_speed: float = 300.0  # px/sec，intensity=0 时
@export var max_obstacle_speed: float = 900.0   # px/sec，intensity=1 时
@export var base_spawn_interval: float = 1.1    # 秒，intensity=0 时
@export var min_spawn_interval: float = 0.35    # 秒，intensity=1 时
@export var hazard_chance_scale: float = 0.6    # mic_level_ratio=1 时"举报"障碍出现概率上限

# 没有真实连接时，用假数据在编辑器里先跑一遍手感；一旦真的连上过 MetroSensorTester
# 就再也不会用假数据（避免真机测试时和真实数据打架）。
@export var debug_simulate_without_connection: bool = true

enum GameState { WAITING, CALIBRATING, RACE, STATION, SUMMARY }

const LANE_COUNT := 3
const OBSTACLE_SIZE := Vector2(140, 100)
const PLAYER_SIZE := Vector2(140, 180)

const UPGRADE_POOL := [
	{"name": "改装排气", "desc": "接下来这段路，障碍车速 -15%", "apply": "slow"},
	{"name": "熟客免检", "desc": "获得 1 次撞击免疫", "apply": "shield"},
	{"name": "老江湖", "desc": "举报事件概率 -30%", "apply": "hazard_down"},
]

var lane_x: Array = []
var current_lane := 1
var player: ColorRect
var play_area_width := 1080.0
var play_area_height := 1920.0

var game_state: int = GameState.WAITING
var last_reported_state := ""
var has_ever_connected := false

var obstacles: Array = []
var spawn_timer := 0.0
var current_intensity := 0.0
var current_hazard_chance := 0.0

var orders_delivered := 0
var hits_taken := 0
var reputation := 100
var speed_multiplier := 1.0
var shield_charges := 0
var hazard_chance_multiplier := 1.0
var upgrade_shown_this_stop := false

var sim_timer := 2.0
var sim_is_moving := false

@onready var play_area: Node2D = $PlayArea
@onready var state_label: Label = $HUD/StateLabel
@onready var stats_label: Label = $HUD/StatsLabel
@onready var debug_label: Label = $HUD/DebugLabel
@onready var waiting_overlay: Control = $WaitingOverlay
@onready var upgrade_overlay: Control = $UpgradeOverlay
@onready var upgrade_buttons: Array = [
	$UpgradeOverlay/UpgradeButton1,
	$UpgradeOverlay/UpgradeButton2,
	$UpgradeOverlay/UpgradeButton3,
]
@onready var summary_overlay: Control = $SummaryOverlay
@onready var summary_label: Label = $SummaryOverlay/SummaryLabel
@onready var restart_button: Button = $SummaryOverlay/RestartButton


func _ready() -> void:
	play_area_width = get_viewport_rect().size.x
	play_area_height = get_viewport_rect().size.y
	lane_x = [play_area_width * 0.2, play_area_width * 0.5, play_area_width * 0.8]

	_spawn_player()

	GameLink.connected_changed.connect(_on_link_connected_changed)
	GameLink.data_received.connect(_on_link_data)

	for i in range(upgrade_buttons.size()):
		upgrade_buttons[i].pressed.connect(_on_upgrade_chosen.bind(i))
	restart_button.pressed.connect(_on_restart_pressed)

	_set_state(GameState.WAITING)


func _process(delta: float) -> void:
	if debug_simulate_without_connection and not has_ever_connected and not GameLink.is_connected:
		_advance_simulation(delta)

	if game_state == GameState.RACE:
		_update_race(delta)

	_update_debug_label()


# ---- 玩家与操作 ----

func _spawn_player() -> void:
	player = ColorRect.new()
	player.size = PLAYER_SIZE
	player.color = Color(1.0, 0.85, 0.2)
	play_area.add_child(player)
	_reposition_player()


func _reposition_player() -> void:
	player.position = Vector2(lane_x[current_lane] - PLAYER_SIZE.x * 0.5, play_area_height - 280.0)


func _unhandled_input(event: InputEvent) -> void:
	if game_state != GameState.RACE:
		return
	var tap_x := -1.0
	if event is InputEventScreenTouch and event.pressed:
		tap_x = event.position.x
	elif event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_LEFT:
		tap_x = event.position.x
	if tap_x < 0.0:
		return
	if tap_x < play_area_width * 0.5:
		current_lane = max(0, current_lane - 1)
	else:
		current_lane = min(LANE_COUNT - 1, current_lane + 1)
	_reposition_player()


# ---- GameLink 数据 -> 状态机 ----

func _on_link_connected_changed(is_connected: bool) -> void:
	if is_connected:
		has_ever_connected = true
		waiting_overlay.visible = false
	elif has_ever_connected and game_state != GameState.SUMMARY:
		_show_summary()
	elif not has_ever_connected:
		waiting_overlay.visible = true


func _on_link_data(data: Dictionary) -> void:
	if game_state == GameState.SUMMARY:
		return
	var state: String = str(data.get("state", ""))
	var train_moving: bool = bool(data.get("train_moving", false))
	var accel_rms: float = float(data.get("accel_rms", 0.0))
	var gyro_rms: float = float(data.get("gyro_rms_deg_s", 0.0))
	var mic_ratio: float = clamp(float(data.get("mic_level_ratio", 0.0)), 0.0, 1.0)
	var player_active: bool = bool(data.get("player_active", false))

	current_intensity = _compute_intensity(accel_rms, gyro_rms)
	current_hazard_chance = mic_ratio * hazard_chance_scale * hazard_chance_multiplier
	if player_active:
		current_intensity *= 0.6  # 安全阀：玩家可能正在起身准备下车，放缓节奏

	last_reported_state = state

	if state == "校准中":
		_set_state(GameState.CALIBRATING)
	elif state == "停站":
		_set_state(GameState.STATION)
	elif train_moving or state == "运行":
		_set_state(GameState.RACE)


func _compute_intensity(accel_rms: float, gyro_rms: float) -> float:
	var a: float = clamp(inverse_lerp(accel_rms_min, accel_rms_max, accel_rms), 0.0, 1.0)
	var g: float = clamp(inverse_lerp(gyro_rms_min, gyro_rms_max, gyro_rms), 0.0, 1.0)
	return max(a, g)  # 两个信号取更极端的那个，任一轴异常都能触发紧张感


func _set_state(new_state: int) -> void:
	if new_state == game_state:
		if new_state == GameState.STATION:
			_show_upgrade_choice()
		return
	game_state = new_state
	match game_state:
		GameState.WAITING:
			state_label.text = "等待连接 MetroSensorTester…"
			waiting_overlay.visible = true
		GameState.CALIBRATING:
			state_label.text = "校准中 · 准备接单"
			waiting_overlay.visible = false
			upgrade_overlay.visible = false
		GameState.RACE:
			state_label.text = "运行中 · 送单!"
			waiting_overlay.visible = false
			upgrade_overlay.visible = false
			upgrade_shown_this_stop = false
		GameState.STATION:
			state_label.text = "停站 · 补给"
			for o in obstacles:
				o.queue_free()
			obstacles.clear()
			_show_upgrade_choice()
		GameState.SUMMARY:
			pass
	_update_stats_label()


# ---- 竞速关（运行态） ----

func _update_race(delta: float) -> void:
	spawn_timer -= delta
	var interval: float = lerp(base_spawn_interval, min_spawn_interval, current_intensity)
	if spawn_timer <= 0.0:
		_spawn_obstacle()
		spawn_timer = interval

	var speed: float = lerp(base_obstacle_speed, max_obstacle_speed, current_intensity) * speed_multiplier
	var i := obstacles.size() - 1
	while i >= 0:
		var obstacle: ColorRect = obstacles[i]
		obstacle.position.y += speed * delta
		if obstacle.position.y > play_area_height:
			obstacles.remove_at(i)
			obstacle.queue_free()
			orders_delivered += 1
			_update_stats_label()
		elif _check_collision(obstacle):
			obstacles.remove_at(i)
			obstacle.queue_free()
			_on_obstacle_hit()
		i -= 1


func _spawn_obstacle() -> void:
	var lane := randi() % LANE_COUNT
	var is_hazard := randf() < current_hazard_chance
	var rect := ColorRect.new()
	rect.size = OBSTACLE_SIZE
	rect.color = Color(0.9, 0.2, 0.2) if is_hazard else Color(0.3, 0.5, 0.9)
	rect.position = Vector2(lane_x[lane] - OBSTACLE_SIZE.x * 0.5, -OBSTACLE_SIZE.y)
	rect.set_meta("lane", lane)
	play_area.add_child(rect)
	obstacles.append(rect)


func _check_collision(obstacle: ColorRect) -> bool:
	if int(obstacle.get_meta("lane")) != current_lane:
		return false
	var player_top := player.position.y
	var player_bottom := player.position.y + PLAYER_SIZE.y
	var obstacle_bottom := obstacle.position.y + OBSTACLE_SIZE.y
	return obstacle_bottom >= player_top and obstacle.position.y <= player_bottom


func _on_obstacle_hit() -> void:
	if shield_charges > 0:
		shield_charges -= 1
		_update_stats_label()
		return
	hits_taken += 1
	reputation = max(0, reputation - 8)
	_update_stats_label()


func _update_stats_label() -> void:
	stats_label.text = "送达 %d  ·  差评 %d  ·  信誉分 %d" % [orders_delivered, hits_taken, reputation]


func _update_debug_label() -> void:
	debug_label.text = "intensity=%.2f  hazard=%.2f  lane=%d  state=%s" % [
		current_intensity, current_hazard_chance, current_lane, last_reported_state
	]


# ---- 停站补给：三选一（先用三张纯数值卡占位，正式美术/文案后补） ----

func _show_upgrade_choice() -> void:
	if upgrade_shown_this_stop:
		return
	upgrade_shown_this_stop = true
	upgrade_overlay.visible = true
	for i in range(upgrade_buttons.size()):
		var card = UPGRADE_POOL[i]
		upgrade_buttons[i].text = "%s\n%s" % [card["name"], card["desc"]]


func _on_upgrade_chosen(index: int) -> void:
	var card = UPGRADE_POOL[index]
	match card["apply"]:
		"slow":
			speed_multiplier *= 0.85
		"shield":
			shield_charges += 1
		"hazard_down":
			hazard_chance_multiplier *= 0.7
	upgrade_overlay.visible = false


# ---- 结算（socket 断开 = 下车） ----

func _show_summary() -> void:
	_set_state(GameState.SUMMARY)
	summary_overlay.visible = true
	summary_label.text = "本次通勤结算\n\n送达订单: %d\n差评次数: %d\n信誉分: %d" % [
		orders_delivered, hits_taken, reputation
	]


func _on_restart_pressed() -> void:
	orders_delivered = 0
	hits_taken = 0
	reputation = 100
	speed_multiplier = 1.0
	shield_charges = 0
	hazard_chance_multiplier = 1.0
	current_lane = 1
	_reposition_player()
	for o in obstacles:
		o.queue_free()
	obstacles.clear()
	summary_overlay.visible = false
	has_ever_connected = GameLink.is_connected
	_set_state(GameState.CALIBRATING if GameLink.is_connected else GameState.WAITING)


# ---- 编辑器里没连真机时，用假数据先跑一遍手感（只在从未连接过真机时生效） ----

func _advance_simulation(delta: float) -> void:
	sim_timer -= delta
	if sim_timer > 0.0:
		return
	sim_is_moving = not sim_is_moving
	if sim_is_moving:
		sim_timer = 8.0
		_on_link_data({
			"state": "运行", "train_moving": true,
			"accel_rms": randf_range(0.8, 2.6), "gyro_rms_deg_s": randf_range(8.0, 26.0),
			"mic_level_ratio": randf_range(0.2, 0.7), "player_active": false,
		})
	else:
		sim_timer = 4.0
		_on_link_data({
			"state": "停站", "train_moving": false,
			"accel_rms": 0.1, "gyro_rms_deg_s": 1.0,
			"mic_level_ratio": 0.2, "player_active": false,
		})
