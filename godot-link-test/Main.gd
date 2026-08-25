extends Control

# Minimal verification client for GameLinkServer.
#
# Connects to MetroSensorTester's local loopback socket and shows whatever
# it receives. This is NOT the real game -- it's just proof that the pipe
# (RecordingService -> GameLinkServer -> TCP -> Godot) actually works before
# building anything on top of it.
#
# Two ways to run this:
# 1. On the same phone: export this project as an Android APK, install it
#    next to MetroSensorTester, start recording in MetroSensorTester first,
#    then open this app.
# 2. Faster dev loop, from a PC: connect the phone over USB with MetroSensorTester
#    running and recording started, then run
#        adb forward tcp:8765 tcp:8765
#    and just hit Play in the Godot editor -- 127.0.0.1:8765 on the PC gets
#    forwarded to the phone's loopback socket.

const HOST := "127.0.0.1"
const PORT := 8765
const RECONNECT_INTERVAL_SEC := 2.0

@onready var label: Label = $Label

var tcp := StreamPeerTCP.new()
var connected := false
var reconnect_timer := 0.0
var line_buffer := PackedByteArray()
var last_logged_status := -1


func _ready() -> void:
	_try_connect()


func _process(delta: float) -> void:
	tcp.poll()
	var status := tcp.get_status()
	if status != last_logged_status:
		print("[link] status -> ", status, " (0=NONE 1=CONNECTING 2=CONNECTED 3=ERROR)")
		last_logged_status = status

	match status:
		StreamPeerTCP.STATUS_CONNECTED:
			if not connected:
				connected = true
				_set_status("已连接 MetroSensorTester，等待数据…")
			_read_available()
		StreamPeerTCP.STATUS_CONNECTING:
			_set_status("连接中…")
		StreamPeerTCP.STATUS_NONE, StreamPeerTCP.STATUS_ERROR:
			connected = false
			reconnect_timer -= delta
			if reconnect_timer <= 0.0:
				_set_status(
					"未连接 127.0.0.1:%d，%.0f 秒后重试…\n（确认 MetroSensorTester 正在采集，或先跑 adb forward tcp:%d tcp:%d）"
					% [PORT, RECONNECT_INTERVAL_SEC, PORT, PORT]
				)
				_try_connect()


func _try_connect() -> void:
	tcp = StreamPeerTCP.new()
	var err := tcp.connect_to_host(HOST, PORT)
	print("[link] connect_to_host(", HOST, ", ", PORT, ") -> ", err, " (0=OK)")
	last_logged_status = -1
	reconnect_timer = RECONNECT_INTERVAL_SEC


func _read_available() -> void:
	var available := tcp.get_available_bytes()
	if available <= 0:
		return
	var chunk = tcp.get_data(available)
	# chunk[0] is the Error code, chunk[1] is the PackedByteArray payload.
	if chunk[0] != OK:
		return
	line_buffer.append_array(chunk[1])
	_extract_lines()


func _extract_lines() -> void:
	while true:
		var newline_index := line_buffer.find(10) # '\n'
		if newline_index < 0:
			break
		var line_bytes := line_buffer.slice(0, newline_index)
		line_buffer = line_buffer.slice(newline_index + 1)
		_handle_line(line_bytes.get_string_from_utf8())


func _handle_line(line: String) -> void:
	if line.is_empty():
		return
	var parsed = JSON.parse_string(line)
	if typeof(parsed) != TYPE_DICTIONARY:
		print("无法解析的行: ", line)
		return
	var text := (
		"train: %s (moving=%s)\nplayer_active=%s\nmic_level_ratio=%.2f  accel_rms=%.3f  gyro_rms_deg_s=%.1f\nt=%sms"
		% [
			parsed.get("state", "?"),
			parsed.get("train_moving", "?"),
			parsed.get("player_active", "?"),
			parsed.get("mic_level_ratio", 0.0),
			parsed.get("accel_rms", 0.0),
			parsed.get("gyro_rms_deg_s", 0.0),
			parsed.get("t", "?"),
		]
	)
	_set_status(text)
	print(line)


func _set_status(text: String) -> void:
	label.text = text
