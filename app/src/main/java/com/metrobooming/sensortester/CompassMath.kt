package com.metrobooming.sensortester

import kotlin.math.abs

object CompassMath {
    private val directions = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

    fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    fun directionName(headingDegrees: Double): String {
        val index = ((normalizeDegrees(headingDegrees) + 22.5) / 45.0).toInt() % directions.size
        return directions[index]
    }

    fun trueNorthDegrees(magneticNorthDegrees: Double, declinationDegrees: Double): Double =
        normalizeDegrees(magneticNorthDegrees + declinationDegrees)

    fun smoothDegrees(previous: Double?, next: Double, alpha: Double = 0.25): Double {
        if (previous == null) return normalizeDegrees(next)
        val delta = ((next - previous + 540.0) % 360.0) - 180.0
        return normalizeDegrees(previous + alpha * delta)
    }

    fun accuracyLabel(accuracy: Int?): String = when (accuracy) {
        3 -> "高"
        2 -> "中"
        1 -> "低"
        0 -> "不可靠"
        else -> "未知"
    }

    fun calibrationLabel(accuracy: Int?): String = when (accuracy) {
        3 -> "已校准"
        2 -> "可用"
        1 -> "需校准"
        0 -> "不可靠"
        else -> "未知"
    }

    fun isMagneticFieldPlausible(magnitudeUt: Double?): Boolean =
        magnitudeUt != null && magnitudeUt.isFinite() && magnitudeUt in 20.0..100.0

    fun magneticDisturbanceLabel(magnitudeUt: Double?): String = when {
        magnitudeUt == null -> "未知"
        !magnitudeUt.isFinite() -> "异常"
        abs(magnitudeUt) !in 20.0..100.0 -> "可能受干扰"
        else -> "正常范围"
    }
}
