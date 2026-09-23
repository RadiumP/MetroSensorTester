extends Node

# 极简的场景间传参 autoload。change_scene_to_file() 没法直接带参数，
# 所以 Menu.gd 在切到 Game.tscn 之前把这一个字段设好，Game.gd 的
# _ready() 里读一次就够了——目前只有这一个开关，不需要更复杂的东西。

var standalone_mode := false
