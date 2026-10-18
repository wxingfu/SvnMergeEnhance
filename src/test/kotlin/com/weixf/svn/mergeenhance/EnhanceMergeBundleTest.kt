package com.weixf.svn.mergeenhance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EnhanceMergeBundle` 的跨版本语言包桥接回归（2026.x API 搬迁，见类文档）。
 *
 * 测试环境跑在 **2022.1 SDK** 的 classpath 上（`gradlew test` 由 IPGP 注入），
 * `com.intellij.l10n.LocalizationUtil` 不存在，因此这里钉死的是**回退路径**：
 * 反射探测必须静默回退到 `DynamicBundle.findLanguageBundle()`，不能抛异常、
 * 不能让 bundle 初始化失败（否则界面一调 `message()` 就崩）。
 *
 * 新平台路径（2026.x 的 LocalizationUtil）无法在本 classpath 上直接执行，
 * 由 `gradlew verifyOnIde -PideaVersion=2026.2.3` 的沙箱启动日志覆盖
 * （`EnhanceMerge i18n: languagePack=...` 行 + issues 0）。
 */
class EnhanceMergeBundleTest {

    @Test
    fun `bridge falls back silently when new l10n api is absent`() {
        // 2022.1 classpath 上无 LocalizationUtil：走旧路径且不抛异常即为通过
        val diagnostics = EnhanceMergeBundle.localizationDiagnostics()
        assertTrue(diagnostics.startsWith("languagePack="), "诊断信息应包含语言包判定: $diagnostics")
    }

    @Test
    fun `base bundle resolves english text without language pack`() {
        // 测试 IDE 未装语言包：必须拿到英文基包文案（而不是操作系统默认语言的中文）
        assertEquals("Select Branch", EnhanceMergeBundle.message("popup.title.select.branch"))
    }

    @Test
    fun `message formats parameters`() {
        // error.unexpected.changes.provider=Unexpected committed changes provider: {0}
        assertEquals(
            "Unexpected committed changes provider: test-provider",
            EnhanceMergeBundle.message("error.unexpected.changes.provider", "test-provider")
        )
    }
}
