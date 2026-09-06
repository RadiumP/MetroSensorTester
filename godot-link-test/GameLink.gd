extends Node

# Singleton (autoload as "GameLink") that owns the TCP connection to
# MetroSensorTester and re-broadcasts each parsed JSON line as a signal.
#
# Extracted from the original verification client (Main.gd) so any scene —
# the real game, or a future debug scene — can share one connection instead
# of each opening its own socket.

signal connected_changed(is_connected: bool)
signal data_received(data: Dictionary)

const HOST := "127.0.0.1"
const PORT := 8765
const RECONNECT_INTERVAL_SEC := 2.0

var tcp := StreamPeerTCP.new()
var is_connected := false
var reconnect_timer := 0.0
var line_buffer := PackedByteArray()

# Last parsed payload, so a freshly-opened scene doesn't have to wait for
# the next tick to know the current state.
var last_data := {}


func _ready() -> void:
	_try_connect()


func _process(delta: float) -> void:
	tcp.poll()
	var status := tcp.get_status()
	match status:
		StreamPeerTCP.STATUS_CONNECTED:
			if not is_connected:
				is_connected = true
				connected_changed.emit(true)
			_read_available()
		StreamPeerTCP.STATUS_CONNECTING:
			pass
		StreamPeerTCP.STATUS_NONE, StreamPeerTCP.STATUS_ERROR:
			if is_connected:
				is_connected = false
				connected_changed.emit(false)
			reconnect_timer -= delta
			if reconnect_timer <= 0.0:
				_try_connect()


func _try_connect() -> void:
	tcp = StreamPeerTCP.new()
	tcp.connect_to_host(HOST, PORT)
	reconnect_timer = RECONNECT_INTERVAL_SEC


func _read_available() -> void:
	var available := tcp.get_available_bytes()
	if available <= 0:
		return
	var chunk = tcp.get_data(available)
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
		return
	last_data = parsed
	data_received.emit(parsed)
