package xyz.chouxuewei.mobile_agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun comparesReleaseTagsAsSemanticVersions() {
        assertTrue(isNewerReleaseVersion("v0.1.2", "0.1.1"))
        assertTrue(isNewerReleaseVersion("v0.10.0", "0.9.9"))
        assertTrue(isNewerReleaseVersion("1.0.0", "1.0.0-rc.2"))
        assertFalse(isNewerReleaseVersion("v0.1.1", "0.1.1"))
        assertFalse(isNewerReleaseVersion("v1.0.0-beta.2", "1.0.0-beta.11"))
    }

    @Test
    fun parsesLatestReleaseAndDigest() {
        val release = parseGitHubRelease(
            """
            {
              "tag_name": "v0.2.0",
              "name": "Mobile Agent 0.2.0",
              "body": "Bug fixes",
              "html_url": "https://github.com/looper42/mobile_agent/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "mobile-agent-release.apk",
                  "browser_download_url": "https://example.test/mobile-agent-release.apk",
                  "size": 1234,
                  "digest": "sha256:abcdef"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("v0.2.0", release.tagName)
        assertEquals("Bug fixes", release.notes)
        assertEquals(1, release.assets.size)
        assertEquals("sha256:abcdef", release.assets.single().digest)
    }

    @Test
    fun prefersUniversalReleaseApkAndRejectsUnsafeBuilds() {
        val assets = listOf(
            asset("mobile-agent-debug.apk"),
            asset("mobile-agent-arm64-v8a-release.apk"),
            asset("mobile-agent-universal-release.apk"),
            asset("checksums.txt"),
        )

        assertEquals(
            "mobile-agent-universal-release.apk",
            selectApkAsset(assets, listOf("arm64-v8a"))?.name,
        )
        assertNull(selectApkAsset(listOf(asset("app-debug.apk"), asset("app-unsigned.apk")), emptyList()))
    }

    private fun asset(name: String) = ReleaseAsset(
        name = name,
        downloadUrl = "https://example.test/$name",
        sizeBytes = 100L,
        digest = null,
    )
}
