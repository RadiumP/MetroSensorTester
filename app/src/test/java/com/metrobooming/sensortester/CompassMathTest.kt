package com.metrobooming.sensortester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassMathTest {
    @Test
    fun normalizesAnglesAndMapsEightDirections() {
        assertEquals(350.0, CompassMath.normalizeDegrees(-10.0), 0.0001)
        assertEquals(5.0, CompassMath.normalizeDegrees(365.0), 0.0001)
        assertEquals("北", CompassMath.directionName(0.0))
        assertEquals("东北", CompassMath.directionName(45.0))
        assertEquals("南", CompassMath.directionName(180.0))
        assertEquals("西北", CompassMath.directionName(315.0))
        assertEquals("北", CompassMath.directionName(359.9))
    }

    @Test
    fun convertsMagneticNorthToTrueNorth() {
        assertEquals(12.5, CompassMath.trueNorthDegrees(10.0, 2.5), 0.0001)
        assertEquals(1.0, CompassMath.trueNorthDegrees(359.0, 2.0), 0.0001)
    }

    @Test
    fun smoothingTakesShortestPathAcrossNorth() {
        assertEquals(0.0, CompassMath.smoothDegrees(350.0, 10.0, 0.5), 0.0001)
        assertEquals(0.0, CompassMath.smoothDegrees(10.0, 350.0, 0.5), 0.0001)
    }

    @Test
    fun reportsAccuracyAndCalibration() {
        assertEquals("高", CompassMath.accuracyLabel(3))
        assertEquals("低", CompassMath.accuracyLabel(1))
        assertEquals("已校准", CompassMath.calibrationLabel(3))
        assertEquals("需校准", CompassMath.calibrationLabel(1))
        assertEquals("未知", CompassMath.calibrationLabel(null))
    }

    @Test
    fun flagsImplausibleSubwayMagneticFields() {
        assertTrue(CompassMath.isMagneticFieldPlausible(50.0))
        assertFalse(CompassMath.isMagneticFieldPlausible(150.0))
        assertEquals("正常范围", CompassMath.magneticDisturbanceLabel(50.0))
        assertEquals("可能受干扰", CompassMath.magneticDisturbanceLabel(150.0))
        assertEquals("未知", CompassMath.magneticDisturbanceLabel(null))
    }
}
