package com.weixf.svn.mergeenhance.log

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.history.SvnRepositoryLocation
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
 * ## ⚠️ 只取「源分支自身」的修订（第九轮修复，**不照抄原版**）
 *
 * 原版合并对话框走 `MergeCalculatorTask.loadChangeLists` →
 * `SvnCommittedChangesProvider.getCommittedChangesWithMergedRevisons`
 * （内部 `includeMergedRevisions = true`），会把**合并源分支**的修订也塞进清单
 * （「同一个需求在别的分支上的副本」，路径相对源分支）。
 * 这些副本在目标上并不存在 → 合并不进去，且 mergeinfo 检查一律 `NOT_EXISTS`
 * → 状态图标为空 → 用户看到「版本缺失 / 行首无图标」。**这是原版自身的缺陷。**
 *
 * 本加载器改用同一 provider 的 `getCommittedChanges(...)`
 * （`includeMergedRevisions = false`，且直连实现**不走缓存**、实时查仓库），
 * 游标语义与原版完全一致。详见 [loadOwnBranchRevisions]。
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
     * 数据源（**注入点，测试用**）。为 null 时走 `SvnCommittedChangesProvider.getCommittedChanges`
     * （只含源分支自身修订、不走缓存，见 [loadOwnBranchRevisions]）。
     * @return 原始页 + 「是否**已取完**」判定（没有更多时为 `true`；注意语义是"已取完"而非"还有更多"）
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
     * @param onBatchLoaded 可选的进度回调：每取回一批就被调用一次，参数是**累计已取回条数**。
     *   官方要求「总量未知时用不确定进度 + 计数文本说明进度」，调用方据此更新 `indicator.text2`。
     *   在**后台线程**被调用。
     */
    fun loadNextPageRaw(
        pageSize: Int,
        expectedGeneration: Long,
        indicator: ProgressIndicator? = null,
        onBatchLoaded: ((Int) -> Unit)? = null,
    ): List<SvnChangeList> {
        if (expectedGeneration != generation) return emptyList()
        return loadByCursorRaw(pageSize, expectedGeneration, indicator, onBatchLoaded)
    }

    private fun loadByCursorRaw(
        pageSize: Int,
        expectedGeneration: Long,
        indicator: ProgressIndicator?,
        onBatchLoaded: ((Int) -> Unit)?,
    ): List<SvnChangeList> {
        if (allLoaded) return emptyList()
        val raw = ArrayList<SvnChangeList>()
        // 事务性状态：只有本次调用**正常返回**（未被取消、世代号未变）才写回字段。
        //
        // ⚠️ 为什么不能边取边写字段（2026-11-01 修复）：原实现取完一批就立刻推进 `cursor`，
        // 而这批数据要等方法**正常返回**才交给调用方。若此时被取消（ProcessCanceledException），
        // 该批数据被丢弃、但游标已越过它 → 那一批修订此后**永远不会被取回**（静默丢修订）；
        // 若该批恰好同时把 `allLoaded` 置真，则这批数据永久缺失且不再有任何加载机会。
        var newCursor = cursor
        var newAllLoaded = false
        var batches = 0
        while (raw.size < pageSize && !newAllLoaded && batches < MAX_BATCHES) {
            // 每轮检查一次世代号：reset 发生后立即停止，**返回空**（与类文档承诺一致：
            // 旧世代的数据一律作废，不得混进新缓冲）
            if (expectedGeneration != generation) return emptyList()
            // 取消点：抛 ProcessCanceledException → 平台的 Task 走 onCancel/onThrowable，不再写回数据
            indicator?.checkCanceled()
            batches++
            val result = cursorSource?.invoke(newCursor, pageSize) ?: loadOwnBranchRevisions(newCursor, pageSize)
            val page = result.first
            if (page.isEmpty()) {
                newAllLoaded = true
                break
            }
            newCursor = page.last().number
            newAllLoaded = result.second && page.size < pageSize
            raw.addAll(page)
            // 进度回调（后台线程）：让调用方能显示「已取回 N 条」
            onBatchLoaded?.invoke(raw.size)
        }
        // 正常返回 → 一次性提交游标与结束标志
        cursor = newCursor
        allLoaded = newAllLoaded
        return raw
    }

    /**
     * 取「**源分支自身**」的修订（**不含合并修订 / 外来副本**，且**不走缓存**）。
     *
     * ## 为什么不用原版的 `MergeCalculatorTask.loadChangeLists`
     *
     * 原版走 `SvnCommittedChangesProvider.getCommittedChangesWithMergedRevisons`
     * （内部 `includeMergedRevisions = true`）：它经 `SvnMergeSourceTracker` +
     * `MergeSourceHierarchyBuilder` 把**合并源分支**的修订也塞进清单——即
     * 「同一个需求在别的分支上的副本」（路径相对源分支，提交说明与目标分支上的那条重复）。
     *
     * 这些副本是原版的**已知缺陷**：
     * - 相对目标路径做 mergeinfo 检查一律 `NOT_EXISTS` → 状态 `ALIEN`（**图标为 null**），
     *   列表里出现「行首没有状态图标」的条目；
     * - 它们在目标上并不存在 → **合并不进去**（原版照样让用户勾选），
     *   表现为「选了这个版本，合并后却什么都没发生」（版本缺失）。
     *
     * 这里改用同一 provider 的 `getCommittedChanges(...)`：其内部
     * `getCommittedChangesImpl(..., includeMergedRevisions = false, ...)`，
     * **只返回源分支自身的修订**，并**直连实现不走缓存**（实时查仓库），
     * 游标（`CHANGE_BEFORE`）语义与原版完全一致。
     *
     * @param cursor 分页游标（`> 0` 时作为「该修订之前」的过滤条件）；`-1` = 从 HEAD 开始。
     * @param size 本次最多取多少条。
     * @return 取到的修订 + 「是否已取完」判定（多取一条判断）。
     */
    private fun loadOwnBranchRevisions(cursor: Long, size: Int): Pair<List<SvnChangeList>, Boolean> {
        val settings = ChangeBrowserSettings()
        if (cursor > 0) {
            settings.CHANGE_BEFORE = cursor.toString()
            settings.USE_CHANGE_BEFORE_FILTER = true
        }
        val provider = context.vcs.committedChangesProvider
        // ⚠️ **「CHANGE_BEFORE」的区间含端点**（2026-11-01 修复 P0-1）：
        // 带 `CHANGE_BEFORE=cursor` 请求时，游标那条修订会**再次**出现在结果里。
        // 原版对此有明确处理——`MergeCalculatorTask.getChangeLists` 请求 `size + 2` 并**显式剔除**
        // `revisionToExclude`（= 上一页最旧的修订）：
        //   size > 0 ? size + (revisionToExclude > 0 ? 2 : 1) : 0
        //   if (revisionToExclude != changeList.getNumber()) { result.add(...) }
        //
        // 不剔除的后果（**静默丢修订**）：重复条目占掉了 `take(size)` 的一个名额，
        // 于是每翻一页就有一条**更新的新修订**被挤掉；而游标已越过它，
        // 那条修订此后既不会出现在列表里、也不会进入合并集合——用户永远看不到它。
        val extra = if (cursor > 0) EXTRA_FOR_CURSOR_BOUNDARY else 1
        val fetched = provider.getCommittedChanges(settings, SvnRepositoryLocation(context.sourceUrl), size + extra)
        val fresh = if (cursor > 0) fetched.filter { it.number != cursor } else fetched
        // 「是否已取完」：剔除重复后不足一页即到底（对齐原版 `changeLists.size() < size + 1`）
        return fresh.take(size) to (fresh.size <= size)
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
         * 首屏加载的修订条数（**代码内固定值**，插件不提供设置项）。
         *
         * 对齐 TortoiseSVN 的 `NumberOfLogs` 默认 100
         * （`CRegDWORD("Software\\TortoiseSVN\\NumberOfLogs", 100)`）。
         *
         * ⚠️ 只作用于**首屏**；工具栏「Load +100 / +500」固定每次追加对应条数（原版语义）。
         */
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * 单次翻页最多补几批原始数据（防止极端情况下无休止扫描）。
         *
         * 为什么必须设上限：服务端单批可能只返回很少几条（分支上匹配项稀疏），
         * 不设上限会一路向后翻，把"取一页"变成一次长扫描。
         * 10 批 × pageSize 是可接受的最坏情况。
         */
        const val MAX_BATCHES = 10

        /**
         * 翻页时**多取**的条数（游标 > 0 时）。
         *
         * 「CHANGE_BEFORE」区间含端点 → 游标那条修订会重复出现 → 必须多取一条并剔除它，
         * 否则 `take(size)` 会挤掉一条**更新的新修订**（它比游标新，此后永不再取回）。
         * 数字与原版 `MergeCalculatorTask.getChangeLists` 的 `size + 2` 一致。
         */
        const val EXTRA_FOR_CURSOR_BOUNDARY = 2
    }
}
