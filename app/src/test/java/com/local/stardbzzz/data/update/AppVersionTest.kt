package com.local.stardbzzz.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun oldCiReleasesCompareByRunNumber() {
        assertTrue(AppVersion.isNewer("v1.1.66", "1.1.64"))
        assertTrue(AppVersion.isNewer("v1.1.100", "1.1.99"))
        assertFalse(AppVersion.isNewer("v1.1.66", "1.1.66"))
        assertFalse(AppVersion.isNewer("v1.1.62", "1.1.66"))
    }

    @Test
    fun newVersionsComeAfterTheOldOnes() {
        assertTrue(AppVersion.isNewer("v2.1.0", "1.1.66"))
        assertTrue(AppVersion.isNewer("v2.1.0", "1.1.999"))
        assertFalse(AppVersion.isNewer("v1.1.70", "2.1.0"))
    }

    @Test
    fun newVersionsCompareByNumber() {
        assertTrue(AppVersion.isNewer("v2.1.1", "2.1.0"))
        assertTrue(AppVersion.isNewer("v2.2.0", "2.1.12"))
        assertTrue(AppVersion.isNewer("v2.1.10", "2.1.9"))
        assertTrue(AppVersion.isNewer("v3.0.0", "2.99.99"))
        assertFalse(AppVersion.isNewer("v2.1.0", "2.1.0"))
    }

    @Test
    fun localBuildsAndOddTags() {
        // A local build reports the base version ("1.1") and is older than any release
        assertTrue(AppVersion.isNewer("v1.1.5", "1.1"))
        assertFalse(AppVersion.isNewer("nightly", "1.1.66"))
        assertTrue(AppVersion.isNewer("v1.1.66", "1.1-debug"))
    }
}
