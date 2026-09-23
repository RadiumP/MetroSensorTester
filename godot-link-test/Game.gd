extends Control

# Rice Rider — 基础主循环 v0.2（竖版清版射击改版）
#
# 状态机：校准中(CALIBRATING) -> 运行(RACE，竖版清版躲避+射击) -> 停站(STATION，三选一补给)
# -> ... 循环 ... -> 断线/下车(SUMMARY，结算)。
#
# v0.1 -> v0.2 的改动：从"三车道躲避"换成"自由横移 + 自动开火"的竖版清版射击
# （类似雷电，但在地面街景上跑）。操作改成单手拖动跟手移动，符合地铁上单手
# 扶把手的场景；武器/敌人先用色块占位，正式美术和"花生米/肥宅水"等食物梗
# 武器视觉留到下一步。完整设计见仓库根目录 RICE_RIDER_DESIGN.md。
#
# 操作：手指按住屏幕任意位置拖动 = 车身跟手左右移动。武器自动开火，不用按键。

# ==== 实地测试后需要回来调的常量 ====
# accel_rms / gyro_rms_deg_s 的真实取值范围目前是拍的，先跑几趟地铁，对照
# DebugLabel 里显示的原始数值和 intensity，再回来改这四个 min/max。
@export var accel_rms_min: float = 0.0
@export var accel_rms_max: float = 3.0
@export var gyro_rms_min: float = 0.0
@export var gyro_rms_max: float = 30.0

@export var base_obstacle_speed: float = 260.0  # px/sec，intensity=0 时（敌人/路障下压速度）
@export var max_obstacle_speed: float = 820.0   # px/sec，intensity=1 时
@export var base_spawn_interval: float = 1.0    # 秒，intensity=0 时
@export var min_spawn_interval: float = 0.32    # 秒，intensity=1 时
@export var hazard_chance_scale: float = 0.55   # mic_level_ratio=1 时"举报"路障占比上限
@export var rider_chance: float = 0.35          # 非路障敌人里，"对手骑手"（会开枪）占比，其余是路人

# 没有真实连接时，用假数据在编辑器里先跑一遍手感；一旦真的连上过 MetroSensorTester
# 就再也不会用假数据（避免真机测试时和真实数据打架）。
@export var debug_simulate_without_connection: bool = true

# 独立试玩模式：完全不管 GameLink 有没有连上 MetroSensorTester，开局直接用固定的
# 运行/停站节奏循环，方便没带着采集 app 也能玩、给别人演示、或者单独调数值手感。
# 只有 Menu.tscn（开局菜单，玩家自己选"联机"还是"独立试玩"）通过 GameConfig
# 这个 autoload 单例在切场景前把它设好，_ready() 里读一次；这里的 @export 默认值
# 只在编辑器里对 Game.tscn 单独按 F6 跳过菜单测试时生效。
@export var standalone_mode: bool = false
@export var standalone_run_seconds: float = 60.0
@export var standalone_station_seconds: float = 20.0

enum GameState { WAITING, CALIBRATING, RACE, STATION, SUMMARY }
enum EnemyType { HAZARD, PEDESTRIAN, RIDER }

const HAZARD_SIZE := Vector2(140, 100)
const PED_SIZE := Vector2(90, 90)
const RIDER_SIZE := Vector2(140, 130)
const PLAYER_SIZE := Vector2(120, 160)
const PLAYER_BULLET_SIZE := Vector2(14, 34)
const ENEMY_BULLET_SIZE := Vector2(20, 20)

const PLAYER_BULLET_SPEED := 1100.0   # px/sec，向上
const ENEMY_BULLET_SPEED := 480.0     # px/sec，向下
const RIDER_FIRE_INTERVAL_MIN := 1.0
const RIDER_FIRE_INTERVAL_MAX := 1.7

# 占位美术阶段先用英文短标签认清楚方块是什么，正式美术替换掉色块之后可以去掉。
const ENEMY_LABEL := {
	EnemyType.HAZARD: "HAZARD",
	EnemyType.PEDESTRIAN: "PED",
	EnemyType.RIDER: "RIDER",
}

# 三选一卡池：花生米/肥宅水是武器升级，后三张是延续 v0.1 的防御类卡片。
# 后续要扩到"辣条鞭""奶茶炸弹"等主动技能，先把数值骨架跑通。
const UPGRADE_POOL := [
	{"name": "花生米连发", "desc": "开火间隔 -15%", "apply": "fire_rate"},
	{"name": "花生米扇形", "desc": "子弹数 +1（并排扩散）", "apply": "spread"},
	{"name": "肥宅水加浓", "desc": "单发伤害 +1", "apply": "damage"},
	{"name": "改装排气", "desc": "接下来这段路，敌人下压速度 -15%", "apply": "slow"},
	{"name": "熟客免检", "desc": "获得 1 次撞击免疫", "apply": "shield"},
	{"name": "老江湖", "desc": "举报事件概率 -30%", "apply": "hazard_down"},
]

var player: Control
var player_sprite: AnimatedSprite2D
var player_sprite_frames: SpriteFrames
var player_x := 0.0
var dragging := false
var play_area_width := 1080.0
var play_area_height := 1920.0

var game_state: int = GameState.WAITING
var last_reported_state := ""
var has_ever_connected := false

var enemies: Array = []          # 路人/骑手/举报路障，统一放这个数组，靠 meta 区分类型
var player_bullets: Array = []   # 花生米子弹，往上飞
var enemy_bullets: Array = []    # 对手骑手打回来的子弹，往下飞

var spawn_timer := 0.0
var fire_timer := 0.0
var current_intensity := 0.0
var current_hazard_chance := 0.0

# 武器状态（三选一升级会改这几个数）
var fire_interval := 0.45
var bullet_count := 1
var bullet_damage := 1

var orders_delivered := 0   # 击落数（沿用旧字段名，含义从"送达"改成"清场击落"）
var hits_taken := 0
var reputation := 100
var speed_multiplier := 1.0
var shield_charges := 0
var hazard_chance_multiplier := 1.0
var upgrade_shown_this_stop := false
var current_cards: Array = []

var sim_timer := 2.0
var sim_is_moving := false

var standalone_timer := 0.0

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
	# Menu.tscn 在切场景过来之前会把这个设好；直接在编辑器里对 Game.tscn 按
	# F6 单独跑的话，GameConfig 是默认值 false，等于走联机模式。
	standalone_mode = GameConfig.standalone_mode

	play_area_width = get_viewport_rect().size.x
	play_area_height = get_viewport_rect().size.y
	player_x = play_area_width * 0.5

	_spawn_player()

	GameLink.connected_changed.connect(_on_link_connected_changed)
	GameLink.data_received.connect(_on_link_data)

	for i in range(upgrade_buttons.size()):
		upgrade_buttons[i].pressed.connect(_on_upgrade_chosen.bind(i))
	restart_button.pressed.connect(_on_restart_pressed)

	if standalone_mode:
		# 独立版不等连接、没有"下车"结算，开局直接进短暂校准然后开跑。
		has_ever_connected = true
		waiting_overlay.visible = false
		standalone_timer = 1.5
		_set_state(GameState.CALIBRATING)
	else:
		_set_state(GameState.WAITING)


func _process(delta: float) -> void:
	if standalone_mode:
		_update_standalone_simulation(delta)
	elif debug_simulate_without_connection and not has_ever_connected and not GameLink.is_connected:
		_advance_simulation(delta)

	if game_state == GameState.RACE:
		_update_race(delta)

	_update_debug_label()


# ==== 玩家与操作（单手拖动跟手，不用车道/按键） ====

func _spawn_player() -> void:
	# 判定框还是原来那个 120x160 的透明 Control（碰撞逻辑全靠 player.position 是
	# 左上角这一点，不能变），真正看得见的是里面挂的 AnimatedSprite2D。
	player = Control.new()
	player.size = PLAYER_SIZE
	player.mouse_filter = Control.MOUSE_FILTER_IGNORE
	play_area.add_child(player)

	player_sprite_frames = _build_player_sprite_frames()
	player_sprite = AnimatedSprite2D.new()
	player_sprite.sprite_frames = player_sprite_frames
	player_sprite.animation_finished.connect(_on_player_sprite_animation_finished)
	# PixelLab 出的素材是 92px(待机)/108px(开火，手臂甩出去比 idle 帧宽) 的正方形
	# 贴图，跟判定框宽度对齐做个统一缩放，位置往判定框上半部分放（车头朝前）。
	var scale_factor: float = (PLAYER_SIZE.x * 1.05) / 92.0
	player_sprite.scale = Vector2(scale_factor, scale_factor)
	player_sprite.position = Vector2(PLAYER_SIZE.x * 0.5, PLAYER_SIZE.y * 0.42)
	player_sprite.play("idle")
	player.add_child(player_sprite)

	_reposition_player()


func _build_player_sprite_frames() -> SpriteFrames:
	var frames := SpriteFrames.new()
	frames.remove_animation("default")

	frames.add_animation("idle")
	frames.set_animation_loop("idle", true)
	frames.set_animation_speed("idle", 1.0)
	frames.add_frame("idle", load("res://art/player/rotations/north.png"))

	frames.add_animation("fire")
	frames.set_animation_loop("fire", false)
	frames.set_animation_speed("fire", 14.0)  # 9 帧跑完大约 0.64 秒，配合默认开火间隔 0.45s 看着还是连贯的
	for i in range(9):
		var path := "res://art/player/animations/peanut_shot/frame_%03d.png" % i
		frames.add_frame("fire", load(path))

	return frames


func _on_player_sprite_animation_finished() -> void:
	if player_sprite.animation == "fire":
		player_sprite.play("idle")


func _reposition_player() -> void:
	player.position = Vector2(player_x - PLAYER_SIZE.x * 0.5, play_area_height - 280.0)


func _set_player_x(x: float) -> void:
	var half := PLAYER_SIZE.x * 0.5
	player_x = clamp(x, half, play_area_width - half)
	_reposition_player()


func _unhandled_input(event: InputEvent) -> void:
	if game_state != GameState.RACE:
		return
	if event is InputEventScreenTouch:
		dragging = event.pressed
		if event.pressed:
			_set_player_x(event.position.x)
	elif event is InputEventScreenDrag:
		if dragging:
			_set_player_x(event.position.x)
	elif event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		dragging = event.pressed
		if event.pressed:
			_set_player_x(event.position.x)
	elif event is InputEventMouseMotion:
		if dragging:
			_set_player_x(event.position.x)


# ==== GameLink 数据 -> 状态机 ====

func _on_link_connected_changed(is_connected: bool) -> void:
	if standalone_mode:
		return  # 独立版不理会真实连接状态，GameLink 在背后怎么连都不影响它
	if is_connected:
		has_ever_connected = true
		waiting_overlay.visible = false
	elif has_ever_connected and game_state != GameState.SUMMARY:
		_show_summary()
	elif not has_ever_connected:
		waiting_overlay.visible = true


func _on_link_data(data: Dictionary) -> void:
	if standalone_mode:
		return  # 独立版的节奏由 _update_standalone_simulation() 驱动，忽略真实数据
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
			_clear_battlefield()
			_show_upgrade_choice()
		GameState.SUMMARY:
			pass
	_update_stats_label()


func _clear_battlefield() -> void:
	for e in enemies:
		e.node.queue_free()
	enemies.clear()
	for b in player_bullets:
		b.queue_free()
	player_bullets.clear()
	for b in enemy_bullets:
		b.queue_free()
	enemy_bullets.clear()


# ==== 竞速关（运行态）：自由横移躲避 + 自动开火清场 ====

func _update_race(delta: float) -> void:
	var speed: float = lerp(base_obstacle_speed, max_obstacle_speed, current_intensity) * speed_multiplier

	_update_spawning(delta, speed)
	_update_firing(delta)
	_update_player_bullets(delta)
	_update_enemy_bullets(delta)
	_update_enemies(delta, speed)


func _update_spawning(delta: float, speed: float) -> void:
	spawn_timer -= delta
	var interval: float = lerp(base_spawn_interval, min_spawn_interval, current_intensity)
	if spawn_timer <= 0.0:
		_spawn_enemy()
		spawn_timer = interval


func _spawn_enemy() -> void:
	var x: float = randf_range(PED_SIZE.x, play_area_width - PED_SIZE.x)
	var is_hazard := randf() < current_hazard_chance
	var type: int
	var size: Vector2
	var hp: int

	if is_hazard:
		type = EnemyType.HAZARD
		size = HAZARD_SIZE
		hp = -1  # 打不掉，只能躲，跟 v0.1 的"举报路障"一致
	elif randf() < rider_chance:
		type = EnemyType.RIDER
		size = RIDER_SIZE
		hp = 2
	else:
		type = EnemyType.PEDESTRIAN
		size = PED_SIZE
		hp = 1

	var spawn_x: float = clamp(x - size.x * 0.5, 0.0, play_area_width - size.x)
	var node: Control = _build_enemy_visual(type, size)
	node.position = Vector2(spawn_x, -size.y)
	play_area.add_child(node)

	var entry := {
		"node": node,
		"type": type,
		"size": size,
		"hp": hp,
		"fire_timer": randf_range(RIDER_FIRE_INTERVAL_MIN, RIDER_FIRE_INTERVAL_MAX),
		"age": 0.0,
	}
	if type == EnemyType.PEDESTRIAN:
		# 路人不是笔直下压，左右晃一点，别老像"卡在车道里的方块"。
		entry["spawn_x"] = spawn_x
		entry["weave_phase"] = randf() * TAU
		entry["weave_freq"] = randf_range(1.4, 2.4)
		entry["weave_amp"] = randf_range(18.0, 42.0)
	elif type == EnemyType.RIDER:
		# 对手骑手会慢慢往玩家所在的 x 位置靠，制造"在追你"的压迫感，
		# 不快到能贴脸堵死，但比纯直线下压更有威胁感。
		entry["chase_speed"] = randf_range(70.0, 130.0)
	enemies.append(entry)


# ==== 敌人占位视觉：不再是清一色的纯色方块，用几个叠起来的色块拼出
# 大致轮廓（人形/双轮车/警示牌），正式美术进来之前先有点辨识度。 ====

func _build_enemy_visual(type: int, size: Vector2) -> Control:
	var container := Control.new()
	container.size = size
	container.mouse_filter = Control.MOUSE_FILTER_IGNORE
	match type:
		EnemyType.PEDESTRIAN:
			_build_pedestrian_visual(container, size)
			_add_center_label(container, ENEMY_LABEL[type])
		EnemyType.RIDER:
			_build_rider_visual(container, size)
			_add_center_label(container, ENEMY_LABEL[type])
		EnemyType.HAZARD:
			_build_hazard_visual(container, size)
	return container


func _build_pedestrian_visual(container: Control, size: Vector2) -> void:
	var head_size := Vector2(size.x * 0.42, size.x * 0.42)
	var head := ColorRect.new()
	head.size = head_size
	head.color = Color(0.6, 0.78, 1.0)
	head.position = Vector2((size.x - head_size.x) * 0.5, 0.0)
	container.add_child(head)

	var body := ColorRect.new()
	body.size = Vector2(size.x * 0.82, size.y - head_size.y * 0.7)
	body.color = Color(0.3, 0.5, 0.9)
	body.position = Vector2((size.x - body.size.x) * 0.5, head_size.y * 0.7)
	container.add_child(body)


func _build_rider_visual(container: Control, size: Vector2) -> void:
	var body := ColorRect.new()
	body.size = size
	body.color = Color(0.75, 0.25, 0.85)
	container.add_child(body)

	var head_size := Vector2(size.x * 0.3, size.x * 0.3)
	var head := ColorRect.new()
	head.size = head_size
	head.color = Color(0.9, 0.65, 0.95)
	head.position = Vector2((size.x - head_size.x) * 0.5, size.y * 0.06)
	container.add_child(head)

	var wheel_size := Vector2(size.x * 0.22, size.x * 0.22)
	var wheel_y := size.y - wheel_size.y * 0.65
	for side in [0.06, 1.0 - 0.06 - 0.22]:
		var wheel := ColorRect.new()
		wheel.size = wheel_size
		wheel.color = Color(0.12, 0.05, 0.18)
		wheel.position = Vector2(size.x * side, wheel_y)
		container.add_child(wheel)


func _build_hazard_visual(container: Control, size: Vector2) -> void:
	var bg := ColorRect.new()
	bg.size = size
	bg.color = Color(0.9, 0.2, 0.2)
	container.add_child(bg)

	var bang := Label.new()
	bang.text = "!"
	bang.position = Vector2(0.0, size.y * 0.04)
	bang.size = Vector2(size.x, size.y * 0.6)
	bang.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	bang.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	bang.add_theme_font_size_override("font_size", int(size.y * 0.55))
	bang.add_theme_color_override("font_color", Color(1, 1, 1))
	bang.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	bang.add_theme_constant_override("outline_size", 5)
	container.add_child(bang)

	_add_center_label(container, ENEMY_LABEL[EnemyType.HAZARD], size.y * 0.68, size.y * 0.3)


func _add_center_label(container: Control, text: String, label_top: float = 0.0, label_height: float = -1.0) -> void:
	var label := Label.new()
	label.text = text
	label.position = Vector2(0.0, label_top)
	label.size = Vector2(container.size.x, label_height if label_height > 0.0 else container.size.y - label_top)
	label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	label.add_theme_font_size_override("font_size", 20)
	label.add_theme_color_override("font_color", Color(1, 1, 1))
	label.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	label.add_theme_constant_override("outline_size", 4)
	container.add_child(label)


func _update_enemies(delta: float, speed: float) -> void:
	var i := enemies.size() - 1
	while i >= 0:
		var e: Dictionary = enemies[i]
		var node: Control = e.node
		node.position.y += speed * delta
		e.age += delta

		match e.type:
			EnemyType.PEDESTRIAN:
				var wobble: float = sin(e.age * e.weave_freq + e.weave_phase) * e.weave_amp
				node.position.x = clamp(e.spawn_x + wobble, 0.0, play_area_width - e.size.x)
			EnemyType.RIDER:
				var target_x: float = clamp(player_x - e.size.x * 0.5, 0.0, play_area_width - e.size.x)
				node.position.x = move_toward(node.position.x, target_x, e.chase_speed * delta)

		if e.type == EnemyType.RIDER:
			e.fire_timer -= delta
			if e.fire_timer <= 0.0:
				_spawn_enemy_bullet(node.position + e.size * 0.5)
				e.fire_timer = randf_range(RIDER_FIRE_INTERVAL_MIN, RIDER_FIRE_INTERVAL_MAX)

		if node.position.y > play_area_height:
			enemies.remove_at(i)
			node.queue_free()
			if e.type == EnemyType.HAZARD:
				orders_delivered += 1  # 硬路障躲过去了，按 v0.1 的口径算一单
				_update_stats_label()
		elif _rects_overlap(player.position, PLAYER_SIZE, node.position, e.size):
			enemies.remove_at(i)
			node.queue_free()
			_on_obstacle_hit()
		i -= 1


func _update_firing(delta: float) -> void:
	fire_timer -= delta
	if fire_timer <= 0.0:
		_spawn_player_bullets()
		fire_timer = fire_interval


func _spawn_player_bullets() -> void:
	if player_sprite:
		player_sprite.play("fire")  # 每次开火都从头播一遍，animation_finished 里会自动切回 idle

	var spacing := 34.0
	var start_offset := -float(bullet_count - 1) * 0.5 * spacing
	for n in range(bullet_count):
		var bullet := ColorRect.new()
		bullet.size = PLAYER_BULLET_SIZE
		bullet.color = Color(1.0, 0.9, 0.3)
		var x: float = player.position.x + PLAYER_SIZE.x * 0.5 + start_offset + spacing * n - PLAYER_BULLET_SIZE.x * 0.5
		bullet.position = Vector2(x, player.position.y - PLAYER_BULLET_SIZE.y)
		play_area.add_child(bullet)
		player_bullets.append(bullet)


func _update_player_bullets(delta: float) -> void:
	var i := player_bullets.size() - 1
	while i >= 0:
		var bullet: ColorRect = player_bullets[i]
		bullet.position.y -= PLAYER_BULLET_SPEED * delta
		if bullet.position.y + PLAYER_BULLET_SIZE.y < 0.0:
			player_bullets.remove_at(i)
			bullet.queue_free()
			i -= 1
			continue

		var hit_index := _find_hittable_enemy(bullet.position, PLAYER_BULLET_SIZE)
		if hit_index >= 0:
			player_bullets.remove_at(i)
			bullet.queue_free()
			_apply_bullet_damage(hit_index)
		i -= 1


func _find_hittable_enemy(pos: Vector2, size: Vector2) -> int:
	for i in range(enemies.size()):
		var e: Dictionary = enemies[i]
		if e.type == EnemyType.HAZARD:
			continue  # 硬路障打不掉，只能躲
		if _rects_overlap(pos, size, e.node.position, e.size):
			return i
	return -1


func _apply_bullet_damage(index: int) -> void:
	var e: Dictionary = enemies[index]
	e.hp -= bullet_damage
	enemies[index] = e
	if e.hp <= 0:
		enemies.remove_at(index)
		e.node.queue_free()
		orders_delivered += 1  # 击落一个，算一单清场
		_update_stats_label()


func _spawn_enemy_bullet(from_center: Vector2) -> void:
	var bullet := ColorRect.new()
	bullet.size = ENEMY_BULLET_SIZE
	bullet.color = Color(0.95, 0.35, 0.15)
	bullet.position = from_center - ENEMY_BULLET_SIZE * 0.5
	play_area.add_child(bullet)
	enemy_bullets.append(bullet)


func _update_enemy_bullets(delta: float) -> void:
	var i := enemy_bullets.size() - 1
	while i >= 0:
		var bullet: ColorRect = enemy_bullets[i]
		bullet.position.y += ENEMY_BULLET_SPEED * delta
		if bullet.position.y > play_area_height:
			enemy_bullets.remove_at(i)
			bullet.queue_free()
		elif _rects_overlap(player.position, PLAYER_SIZE, bullet.position, ENEMY_BULLET_SIZE):
			enemy_bullets.remove_at(i)
			bullet.queue_free()
			_on_obstacle_hit()
		i -= 1


func _rects_overlap(pos_a: Vector2, size_a: Vector2, pos_b: Vector2, size_b: Vector2) -> bool:
	return Rect2(pos_a, size_a).intersects(Rect2(pos_b, size_b))


func _on_obstacle_hit() -> void:
	if shield_charges > 0:
		shield_charges -= 1
		_update_stats_label()
		return
	hits_taken += 1
	reputation = max(0, reputation - 8)
	_update_stats_label()


func _update_stats_label() -> void:
	stats_label.text = "击落 %d  ·  差评 %d  ·  信誉分 %d" % [orders_delivered, hits_taken, reputation]


func _update_debug_label() -> void:
	debug_label.text = "intensity=%.2f  hazard=%.2f  fire=%.2fs x%d dmg%d  state=%s" % [
		current_intensity, current_hazard_chance, fire_interval, bullet_count, bullet_damage, last_reported_state
	]


# ==== 停站补给：三选一（每次从卡池随机抽 3 张，避免每次都一样） ====

func _show_upgrade_choice() -> void:
	if upgrade_shown_this_stop:
		return
	upgrade_shown_this_stop = true
	upgrade_overlay.visible = true
	var pool_copy: Array = UPGRADE_POOL.duplicate()
	pool_copy.shuffle()
	current_cards = pool_copy.slice(0, upgrade_buttons.size())
	for i in range(upgrade_buttons.size()):
		var card = current_cards[i]
		upgrade_buttons[i].text = "%s\n%s" % [card["name"], card["desc"]]


func _on_upgrade_chosen(index: int) -> void:
	var card = current_cards[index]
	match card["apply"]:
		"fire_rate":
			fire_interval = max(0.12, fire_interval * 0.85)
		"spread":
			bullet_count += 1
		"damage":
			bullet_damage += 1
		"slow":
			speed_multiplier *= 0.85
		"shield":
			shield_charges += 1
		"hazard_down":
			hazard_chance_multiplier *= 0.7
	upgrade_overlay.visible = false


# ==== 结算（socket 断开 = 下车） ====

func _show_summary() -> void:
	_set_state(GameState.SUMMARY)
	summary_overlay.visible = true
	summary_label.text = "本次通勤结算\n\n击落敌人: %d\n差评次数: %d\n信誉分: %d" % [
		orders_delivered, hits_taken, reputation
	]


func _on_restart_pressed() -> void:
	orders_delivered = 0
	hits_taken = 0
	reputation = 100
	speed_multiplier = 1.0
	shield_charges = 0
	hazard_chance_multiplier = 1.0
	fire_interval = 0.45
	bullet_count = 1
	bullet_damage = 1
	player_x = play_area_width * 0.5
	_reposition_player()
	_clear_battlefield()
	summary_overlay.visible = false
	has_ever_connected = GameLink.is_connected
	_set_state(GameState.CALIBRATING if GameLink.is_connected else GameState.WAITING)


# ==== 独立试玩模式：固定节奏循环，不依赖 GameLink/MetroSensorTester ====

func _update_standalone_simulation(delta: float) -> void:
	standalone_timer -= delta
	match game_state:
		GameState.CALIBRATING:
			if standalone_timer <= 0.0:
				_enter_standalone_race()
		GameState.RACE:
			# 60 秒里难度从平稳线性爬到最紧张，模拟"这段路越开越颠"；叠一点随机
			# 抖动避免观感太机械。到点直接切停站，跟真数据下"进站"的体验一致。
			var progress: float = 1.0 - clamp(standalone_timer / standalone_run_seconds, 0.0, 1.0)
			current_intensity = clamp(progress + randf_range(-0.08, 0.08), 0.0, 1.0)
			current_hazard_chance = current_intensity * hazard_chance_scale * hazard_chance_multiplier
			if standalone_timer <= 0.0:
				_set_state(GameState.STATION)
				standalone_timer = standalone_station_seconds
		GameState.STATION:
			if standalone_timer <= 0.0:
				_enter_standalone_race()


func _enter_standalone_race() -> void:
	standalone_timer = standalone_run_seconds
	_set_state(GameState.RACE)


# ==== 编辑器里没连真机时，用假数据先跑一遍手感（只在从未连接过真机时生效） ====

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
