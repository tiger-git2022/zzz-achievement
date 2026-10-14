package com.local.stardbzzz.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun readsTheManifestTheReleaseWorkflowWrites() {
        // What `jq` in .github/workflows/release.yml produces.
        val manifest = """
            {"tag_name":"v1.1.36","body":"## 更新内容\n- 修复检查更新 (abc1234)\n","apk":{"name":"zzz-achievement-v1.1.36.apk","size":52258883,"sha256":"FB1E6F1DFA905AA7BAF0A1A2F9225BA302DD49B01085DC7AFD30F4FE2B6DCB35"}}
        """.trimIndent()

        val release = AppUpdater.parseUpdateManifest(manifest)

        assertEquals("v1.1.36", release.tagName)
        assertEquals(36, release.buildNumber)
        assertEquals("## 更新内容\n- 修复检查更新 (abc1234)", release.notes)
        assertEquals(
            "https://github.com/tiger-git2022/zzz-achievement/releases/download/v1.1.36/zzz-achievement-v1.1.36.apk",
            release.apkUrl,
        )
        assertEquals(52258883L, release.apkSize)
        assertEquals("fb1e6f1dfa905aa7baf0a1a2f9225ba302dd49b01085dc7afd30f4fe2b6dcb35", release.apkSha256)
    }
}
