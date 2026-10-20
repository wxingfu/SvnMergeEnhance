package com.weixf.svn.mergeenhance.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置层（[EnhanceMergeSettings]）的纯逻辑回归。
 *
 * ## 为什么只测这一层
 *
 * 过滤栏 `LogFilterPanel` 依赖 Swing 组件与应用级 service，无法脱离 IDE 实例单测；
 * 而"设置 → 过滤栏初始状态"的判定已被收敛成纯函数 [EnhanceMergeSettings.dateRangeEnabledOnOpen]
 * 与 [EnhanceMergeSettings.defaultFromDate]，因此把回归钉在这一层即可。
 *
 * ## 钉住的那个 bug
 *
 * 「默认日期范围（天）> 0」曾经**完全不生效**：对话框只调 `initDateRange()` 填日历，
 * 没打开「日期区间」开关，而过滤栏只在开关打开时才把起止值交给 `LogFilter`，
 * 于是设置项静默失效（界面既看不到日期面板，也不会按日期重拉，且无任何反馈）。
 *
 * 现在的语义已经变了：日期区间是**纯客户端**过滤（TortoiseSVN 亦然），
 * 开关默认开启、默认值恒等于已加载数据的日期范围，因此"配了默认范围就必须强制开启"
 * 这条补丁不再需要——[EnhanceMergeSettings.dateRangeEnabledOnOpen] 只表达
 * "默认开启 + 沿用用户上次的开关选择"，下面的
 * `date range is enabled on open by default and follows the last user choice` 即其回归。
 *
 * 本类直接 `new EnhanceMergeSettings()`：只有 `getInstance()` 走平台的 `service()`，
 * 实例本身不依赖 IDE。
 */
class EnhanceMergeSettingsTest {

    private fun settings(): EnhanceMergeSettings = EnhanceMergeSettings()

    @Test
    fun `default date range returns null when disabled`() {
        val s = settings()
        assertEquals(0, s.defaultDateRangeDays)
        assertNull(s.defaultFromDate(NOW))
    }

    @Test
    fun `default date range starts N days before now`() {
        val s = settings()
        s.defaultDateRangeDays = 7
        val from = s.defaultFromDate(NOW)
        assertNotNull(from)
        assertEquals(NOW - 7 * DAY_MILLIS, from.time)
    }

    @Test
    fun `negative default date range is normalized to zero on load`() {
        val s = settings()
        // 配置文件可能被手改：负数天数不该产出"未来"的起始日期
        s.loadState(EnhanceMergeSettings.State(pageSize = 1, defaultDateRangeDays = -5))
        assertEquals(0, s.defaultDateRangeDays)
        assertNull(s.defaultFromDate(NOW))
    }

    @Test
    fun `date range is enabled on open by default and follows the last user choice`() {
        val s = settings()

        // 默认值 true（对齐 TortoiseSVN：日期区间位默认就在 LOGFILTER_ALL 里）。
        // 安全的前提是日期区间已改为**纯客户端过滤**、且默认值恒等于已加载数据的日期范围。
        assertEquals(true, s.dateRangeEnabled)
        assertEquals(true, s.dateRangeEnabledOnOpen())

        // 用户手动关过时，重开对话框尊重该偏好（此时对话框会用过滤栏当前状态构建
        // currentFilter，控件状态与实际过滤仍然一致）
        assertEquals(false, s.dateRangeEnabledOnOpen(lastUsed = false))
        assertEquals(true, s.dateRangeEnabledOnOpen(lastUsed = true))

        // 配了「默认日期范围（天）」时：起止值会被"最近 N 天"覆盖
        // （LogFilterPanel.applyDefaultDateRange 内部会打开开关），因此这里仍尊重
        // 用户上次的选择，不需要再像早期实现那样"强制开启"——那条补丁是为了绕开
        // "日期区间 → 服务端查询通道"的耦合，而该耦合已删除。
        s.defaultDateRangeDays = 30
        assertEquals(false, s.dateRangeEnabledOnOpen(lastUsed = false))
        assertEquals(true, s.dateRangeEnabledOnOpen(lastUsed = true))
    }

    @Test
    fun `page size is clamped into the supported range`() {
        val s = settings()
        s.pageSize = 1
        assertEquals(EnhanceMergeSettings.MIN_PAGE_SIZE, s.pageSize)
        s.pageSize = 10_000
        assertEquals(EnhanceMergeSettings.MAX_PAGE_SIZE, s.pageSize)
    }

    @Test
    fun `out of range page size from storage is normalized`() {
        val s = settings()
        s.loadState(EnhanceMergeSettings.State(pageSize = 999_999))
        assertEquals(EnhanceMergeSettings.MAX_PAGE_SIZE, s.pageSize)
    }

    @Test
    fun `invalid bug id pattern falls back to null so callers can use the default`() {
        val s = settings()
        assertNotNull(s.bugIdRegex)
        // 默认正则 [A-Z][A-Z0-9]+-\d+：首字母 + 至少一个字母/数字 + 连字符 + 数字，
        // 故 "XY-9"（两段都有）与 "ABC-123" 都算 Bug-ID，而单独的 "X-9"（连字符前只有一个字符）不算
        assertEquals(
            listOf("ABC-123", "XY-9"),
            s.bugIdRegex?.findAll("fix ABC-123 and XY-9, not X-9")?.map { it.value }?.toList(),
        )

        s.bugIdPattern = "[unclosed"
        assertNull(s.bugIdRegex, "用户把正则改坏时应返回 null，由调用方回退默认值而不是让过滤崩掉")
    }

    @Test
    fun `filter field ids round trip`() {
        val s = settings()
        assertTrue(s.filterFieldIds.isEmpty(), "默认未记录过，界面回退到全选")

        s.filterFieldIds = listOf("message", "author")
        assertEquals(listOf("message", "author"), s.filterFieldIds)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
