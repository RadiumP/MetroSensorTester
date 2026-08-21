package com.metrobooming.sensortester

enum class TrainState(val label: String) {
    CALIBRATING("校准中"),
    MOVING("运行"),
    STOPPED("停站");

    companion object {
        fun fromLabel(label: String): TrainState? = entries.find { it.label == label }
    }
}

enum class PlayerState(val label: String) {
    ACTIVE("活动"),
    STILL("静止"),
}

enum class MicQuality(val label: String) {
    NORMAL("正常"),
    RECOVERING("恢复中"),
    ZERO_ABNORMAL("全零异常"),
}

enum class ThresholdMode(val label: String) {
    FIXED("固定"),
    DYNAMIC("动态"),
}
