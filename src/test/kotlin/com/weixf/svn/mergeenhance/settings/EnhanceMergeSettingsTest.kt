package com.weixf.svn.mergeenhance.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设置层（[EnhanceMergeSettings]）的纯逻辑回归。
 *
 * 第五轮收敛后设置只剩两项：每页条数、计算已合并状态。
 * 沿革：`defaultDateRangeDays` / `dateRangeEnabled` / `filterFields` / `useRegex` /
 * `caseSensitive` / `bugIdPattern` 随过滤栏（By/日期/正则/大小写）整体删除，
 * 对应用例一并移除（勿再恢复，见 `LogFilter` 的沿革说明）。
 *
 * 本类直接 `new EnhanceMergeSettings()`：只有 `getInstance()` 走平台的 `service()`，
 * 实例本身不依赖 IDE。
 */
class EnhanceMergeSettingsTest {

    private fun settings(): EnhanceMergeSettings = EnhanceMergeSettings()

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
    fun `merge status defaults to on and round trips`() {
        val s = settings()
        assertEquals(true, s.computeMergeStatus)

        s.computeMergeStatus = false
        assertEquals(false, s.computeMergeStatus)
        assertEquals(false, s.getState().computeMergeStatus)
    }

    @Test
    fun `legacy state with removed fields still loads`() {
        // 旧版 svnEnhanceMerge.xml 里带有已删除字段（dateRangeEnabled/filterFields/...）时，
        // 平台会直接忽略未知字段——这里用 loadState 走一遍正常路径，确认不抛异常且保留项完好。
        val s = settings()
        s.loadState(EnhanceMergeSettings.State(pageSize = 30, computeMergeStatus = false))
        assertEquals(30, s.pageSize)
        assertEquals(false, s.computeMergeStatus)
    }
}
