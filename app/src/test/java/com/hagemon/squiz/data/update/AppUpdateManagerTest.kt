package com.hagemon.squiz.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun comparesSemanticVersionComponentsNumerically() {
        assertTrue(compareReleaseVersions("0.10.0", "0.9.9") > 0)
        assertTrue(compareReleaseVersions("v1.2.1", "1.2.0") > 0)
        assertTrue(compareReleaseVersions("1.2.0", "1.2.1") < 0)
    }

    @Test
    fun treatsMissingComponentsAsZero() {
        assertEquals(0, compareReleaseVersions("v1.2", "1.2.0"))
    }
}
