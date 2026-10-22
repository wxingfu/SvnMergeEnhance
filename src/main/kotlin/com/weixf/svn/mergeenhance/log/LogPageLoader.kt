package com.weixf.svn.mergeenhance.log

import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.MergeCalculatorTask
import org.jetbrains.idea.svn.integrate.MergeContext

/**
 * 修订日志的**分页实时加载器**：直连仓库、服务端分页。
 *
 * ## 只有一条查询通道（对齐 TortoiseSVN）
 *
 * 服务端查询参数只有 `cursor`（起始修订游标）与 `pageSize`，对应 TortoiseSVN 的
 * `m_startRev`/`m_limit`（`LogDlg.cpp` 的 `OnBnClickedNexthundred`：`m_startRev = 最后一条的修订号`，
 * `m_limit = NumberOfLogs + 1`）。**日期区间与搜索词都不参与查询**——
 * 前者是纯客户端过滤（与 TSVN 的 `m_tFrom`/`m_tTo` 一致），
 * 后者由对话框在内存全集上过滤。
 *
 * 早期版本存在「模式 A（有日期）→ `limit=0` 一次拉完整个日期区间」的第二条通道，
 * 后果是：同一对话框里勾/不勾日期区间会得到**不同的结果集**（前者不带 `-g`、带 peg），
 * 且搜索词一变就隐式切换通道。已删除。
 *
 * ## 版本区间
 *
 * ⚠️ 版本区间维度已整体删除（2026-10-20 第四轮，用户决策「不需要版本区间」）：
 * 原先的「版本 到 → 起始游标 `revTo + 1`」与「版本 从 → 客户端早停」随之移除，
 * 游标恒从 HEAD 开始（`-1`）向后翻页。
 *
 * ## 世代号（generation）
 *
 * 重新开始加载时（[reset]）对话框会调用它，它会递增世代号。所有加载调用都必须带上
 * **调用时刻**的世代号（[snapshot]）；一旦 [reset] 发生，在飞的后台任务下一轮立即返回空并退出，
 * 从而杜绝"旧任务把旧条件的数据写进新缓冲"这类串数据（纯 token 校验挡不住这一层：
 * token 只能阻止回调写回，阻止不了 loader 内部状态被旧任务污染）。
 *
 * ⚠️ 所有方法都必须在**后台线程**调用。
 */
class LogPageLoader(
    private val context: MergeContext,
    /**
     * 数据源（**注入点，测试用**）。为 null 时走原版 `MergeCalculatorTask.loadChangeLists`。
     * @return 原始页 + 平台「是否还有更多」判定
     */
    private val cursorSource: ((cursor: Long, size: Int) -> Pair<List<SvnChangeList>, Boolean>)? = null,
) {

    /**
     * 已加载到的最小修订号；`-1` 表示从 HEAD 开始。
     *
     * `@Volatile`：由后台分页任务写、EDT 读（工具栏 `+N` 的 `isVisible/isEnabled`、
     * `loadMore()` 的提前返回），没有它时 EDT 可能读到旧值，表现为按钮显隐滞后一拍。
     */
    @Volatile
    var cursor: Long = -1L
        private set

    /**
     * 是否已取完（游标已到底）。
     *
     * 同上：`@Volatile` 保证后台写入对 EDT 立即可见（否则「+N」按钮会在到底后仍可点，点了没反应）。
     */
    @Volatile
    var allLoaded: Boolean = false
        private set

    /**
     * 加载世代号。仅由 [reset] 递增；调用方用 [snapshot] 取值并在每次加载时回传。
     *
     * `Long` 而非 `Int`：它是纯内存计数器，溢出并不危险，但用 `Long` 可以彻底排除
     * "跑了几十亿次过滤后回绕、与在飞任务的旧值相等" 这种理论上的一致性破坏。
     */
    @Volatile
    private var generation: Long = 0L

    /** 取当前世代号，供调用方在启动后台任务前记录。 */
    fun snapshot(): Long = generation

    /**
     * 重置加载状态：游标回到 HEAD、世代号递增（作废在飞任务）。
     *
     * 语义上只在**重新开始加载**时调用（历史上的 `Refresh`：清空已加载集合并从 HEAD
     * 重查第一页）。⚠️ **Refresh 按钮已随第五轮删除，当前生产代码无调用方**，
     * 仅测试与未来的"清空重拉"入口使用——保留它是为了游标/世代号语义有测试看守，
     * 勿因"没人调"就删掉测试覆盖面。
     */
    @VisibleForTesting
    fun reset() {
        generation++
        cursor = -1L
        allLoaded = false
    }

    /**
     * 取下一页**原始（未客户端过滤）**修订，供对话框在内存全集上自行过滤。
     *
     * @param expectedGeneration 调用时刻的世代号（[snapshot]）；与当前不一致说明期间发生了 [reset]，
     *   本次调用直接返回空，调用方应据此放弃本轮工作。
     * @param indicator 可选的进度指示器：**每批之间**检查一次取消点，
     *   使进度条上的「取消」能真正中止分页（否则按了取消也停不下来——分页循环本身不感知 indicator）。
     */
    fun loadNextPageRaw(
        pageSize: Int,
        expectedGeneration: Long,
        indicator: ProgressIndicator? = null,
    ): List<SvnChangeList> {
        if (expectedGeneration != generation) return emptyList()
        return loadByCursorRaw(pageSize, expectedGeneration, indicator)
    }

    private fun loadByCursorRaw(
        pageSize: Int,
        expectedGeneration: Long,
        indicator: ProgressIndicator?,
    ): List<SvnChangeList> {
        if (allLoaded) return emptyList()
        val raw = ArrayList<SvnChangeList>()
        var batches = 0
        while (raw.size < pageSize && !allLoaded && batches < MAX_BATCHES) {
            // 每轮检查一次世代号：reset 发生后立即停止，避免把旧数据继续塞进来
            if (expectedGeneration != generation) return raw
            // 取消点：抛 ProcessCanceledException → 平台的 Task 走 onCancel/onThrowable，不再写回数据
            indicator?.checkCanceled()
            batches++
            val result = cursorSource?.invoke(cursor, pageSize)
                ?: MergeCalculatorTask.loadChangeLists(context, cursor, pageSize).let { it.first to it.second }
            val page = result.first
            if (page.isEmpty()) {
                allLoaded = true
                break
            }
            cursor = page.last().number
            allLoaded = result.second && page.size < pageSize
            raw.addAll(page)
        }
        return raw
    }

    /**
     * 允许对「已判定到底」的游标再发一次确认请求（即使此前 `allLoaded`）。
     *
     * 为什么需要：`allLoaded` 的判定带启发式（见 [loadByCursorRaw] 里"整页时保守视为
     * 可能还有"），极端情况下会误报"已到底"；历史上的「全部显示」按钮会显式调用它
     * 再探测一次，避免按钮变成死的。⚠️ **Show All 已随第五轮删除，生产代码无调用方**，
     * 仅测试使用；将来若重增同类"强制加载全部"入口应恢复调用。
     */
    @VisibleForTesting
    fun reopenIfExhausted() {
        if (allLoaded) allLoaded = false
    }

    companion object {
        /**
         * 单次翻页最多补几批原始数据（防止极端情况下无休止扫描）。
         *
         * 为什么必须设上限：服务端单批可能只返回很少几条（分支上匹配项稀疏），
         * 不设上限会一路向后翻，把"取一页"变成一次长扫描。
         * 10 批 × pageSize 是可接受的最坏情况。
         */
        const val MAX_BATCHES = 10
    }
}
