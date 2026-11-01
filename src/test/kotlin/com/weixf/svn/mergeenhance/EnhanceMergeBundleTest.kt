package com.weixf.svn.mergeenhance

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EnhanceMergeBundle` 的 **locale 判定**回归（2026-11-01 零反射改造）。
 *
 * ## 背景
 *
 * 旧实现靠**反射**探测"平台是否装了语言包"来决定用 `_zh_CN` 还是英文基包。现改为
 * 直接读 IDE 当前界面语言（公开 API `DynamicBundle.getLocale()`，2022.1 ~ 2026.x 均有），
 * 因此测试不再依赖"平台状态"，而是**直接注入确定的 locale** 来验证决策逻辑。
 *
 * ## 这里钉死的三条行为
 *
 * 1. **英文 IDE → 英文基包**：即使操作系统是中文（测试机就是）也必须给英文——
 *    JDK `ResourceBundle` 的候选链含系统默认 locale，用 `Locale.ENGLISH` 请求会被
 *    `_zh_CN` 命中（串语种的老坑），故英文一律走 `Locale.ROOT`；
 * 2. **中文 IDE → 中文包**：装了中文语言包时取到 `_zh_CN`；
 * 3. **其它语言 → 英文基包**：装了日文语言包但本插件没有 `_ja` 时，
 *    JDK 会 fallback 到系统 locale（中文）→ 必须识别"语言不符"并回退，不能串语种。
 */
class EnhanceMergeBundleTest {

    @Test
    fun `english and undetermined ide locales select the base bundle`() {
        // 英文 → ROOT（只可能命中基包，绕开 JDK 的系统 locale fallback）
        assertEquals(Locale.ROOT, EnhanceMergeBundle.selectLocale(Locale.ENGLISH))
        // 无法判定（Locale.ROOT，language 为空）→ ROOT
        assertEquals(Locale.ROOT, EnhanceMergeBundle.selectLocale(Locale.ROOT))
    }

    @Test
    fun `non english ide locale is requested as is`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, EnhanceMergeBundle.selectLocale(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `locale mismatch is rejected to avoid mixed languages`() {
        // 请求日文、实际拿到中文（JDK fallback 到系统 locale）→ 语言不符，必须拒绝
        assertTrue(!EnhanceMergeBundle.isLocaleAccepted(Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE))
        // 请求中文、拿到中文 → 接受
        assertTrue(EnhanceMergeBundle.isLocaleAccepted(Locale.SIMPLIFIED_CHINESE, Locale.SIMPLIFIED_CHINESE))
        // 请求 ROOT（基包）、拿到基包 → 接受
        assertTrue(EnhanceMergeBundle.isLocaleAccepted(Locale.ROOT, Locale.ROOT))
    }

    @Test
    fun `chinese ide locale loads the chinese bundle`() {
        val bundle = EnhanceMergeBundle.loadBundleFor(Locale.SIMPLIFIED_CHINESE)
        assertEquals("zh", bundle.locale.language)
        assertEquals("选择分支", bundle.getString("popup.title.select.branch"))
    }

    @Test
    fun `english ide locale loads the base bundle even on a chinese system`() {
        // 关键回归：本测试机系统 locale 是 zh_CN，若实现用 Locale.ENGLISH 请求，
        // JDK 会把 _zh_CN 命中 → 英文 IDE 里出现中文文案
        val bundle = EnhanceMergeBundle.loadBundleFor(Locale.ENGLISH)
        assertEquals("", bundle.locale.language)
        assertEquals("Select Branch", bundle.getString("popup.title.select.branch"))
    }

    @Test
    fun `unknown ide locale falls back to the base bundle instead of mixed chinese`() {
        // 装了日文语言包但本插件没有 _ja：绝不能 fallback 成中文
        val bundle = EnhanceMergeBundle.loadBundleFor(Locale.JAPANESE)
        assertEquals("", bundle.locale.language)
        assertEquals("Select Branch", bundle.getString("popup.title.select.branch"))
    }

    @Test
    fun `message formats parameters`() {
        // 文案本体随 IDE 语言变化，故只断言"参数被正确代入"这一与语言无关的性质
        assertTrue(
            EnhanceMergeBundle.message("error.load.revisions", "test-provider").endsWith("test-provider"),
            "参数应被代入文案尾部",
        )
    }

    @Test
    fun `diagnostics report the ide locale without reflection`() {
        val diagnostics = EnhanceMergeBundle.localizationDiagnostics()
        assertTrue(diagnostics.contains("ideLocale="), "诊断信息应包含 IDE 语言: $diagnostics")
        assertTrue(diagnostics.contains("bundleLocale="), "诊断信息应包含生效文案包的语言: $diagnostics")
    }
}
