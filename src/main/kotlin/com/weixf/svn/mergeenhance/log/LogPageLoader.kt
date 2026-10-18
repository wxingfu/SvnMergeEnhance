package com.weixf.svn.mergeenhance.log

import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.history.SvnCommittedChangesProvider
import org.jetbrains.idea.svn.history.SvnRepositoryLocation
import org.jetbrains.idea.svn.integrate.MergeCalculatorTask
import org.jetbrains.idea.svn.integrate.MergeContext

/**
 * 修订日志的**分页实时加载器**（TortoiseSVN 式过滤 + 服务端/客户端分层）。
 *
 * ## 两种加载模式
 *
 * | 模式 | 触发条件 | 服务端查询 | 分页方式 |
 * |---|---|---|---|
 * | A 日期模式 | 填写了日期「从/到」任一 | `svn log -r {dateTo}:{dateFrom}`，`limit=0` 一次拉完该区间 | **内存分页** |
 * | B 游标模式 | 日期均空 | `MergeCalculatorTask.loadChangeLists(context, cursor, size)` | **服务端分页 + 循环补页** |
 *
 * 模式 A 用 `SvnCommittedChangesProvider.getCommittedChanges(settings, location, 0)`：
 * 内部把 `DATE_BEFORE/DATE_AFTER` 翻译成 `svn log -r {date}:{date}`（真正的服务端按日期裁剪），
 * 且不带 `-g`，比 `loadChangeLists` 更快。
 *
 * 模式 B 的版本区间：
 * - 「版本 到」→ 起始游标 `revTo + 1`（原版会**排除**与 `beforeRevision` 相等的修订号，故必须 +1）；
 * - 「版本 从」→ 客户端过滤，扫描到 `number < revFrom` 时判定到底（早停），避免一路扫到 r1。
 *
 * 客户端过滤可能让一页命中不足，故自动循环补页，最多 [MAX_BATCHES] 批，
 * 避免"明明有匹配却显示为空"。
 *
 * ⚠️ 所有方法都必须在**后台线程**调用。
 */
class LogPageLoader(
    private val context: MergeContext,
    /**
     * 模式 B 数据源（**注入点，测试用**）。为 null 时走原版 `MergeCalculatorTask.loadChangeLists`。
     * @return 原始页 + 平台「是否还有更多」判定
     */
    private val cursorSource: ((cursor: Long, size: Int) -> Pair<List<SvnChangeList>, Boolean>)? = null,
    /** 模式 A 数据源（**注入点，测试用**）。为 null 时走原版 `SvnCommittedChangesProvider`。 */
    private val dateRangeSource: ((filter: LogFilter) -> List<SvnChangeList>)? = null,
) {

    /** 模式 B 游标：已加载到的最小修订号；`-1` 表示从 HEAD 开始。 */
    var cursor: Long = -1L
        private set

    /** 是否已取完（模式 B 到底 / 模式 A 已整段拉完）。 */
    var allLoaded: Boolean = false
        private set

    private var buffer: List<SvnChangeList> = emptyList()
    private var bufferPos = 0
    private var bufferReady = false

    /** 重置加载状态；修改任意过滤条件后必须调用。 */
    fun reset(filter: LogFilter) {
        cursor = filter.revisionRange().second?.plus(1) ?: -1L
        allLoaded = false
        buffer = emptyList()
        bufferPos = 0
        bufferReady = false
    }

    /** 取下一页（至多 `pageSize` 条满足全部过滤条件的修订）。 */
    fun loadNextPage(filter: LogFilter, pageSize: Int): List<SvnChangeList> =
        if (filter.hasDateRange()) loadFromDateRange(filter, pageSize) else loadByCursor(filter, pageSize)

    // ------------------------------------------------------------ 模式 A

    private fun loadFromDateRange(filter: LogFilter, pageSize: Int): List<SvnChangeList> {
        if (!bufferReady) {
            buffer = dateRangeSource?.invoke(filter) ?: fetchByDateRange(filter)
            bufferReady = true
            allLoaded = true
        }
        val matched = if (!filter.isFiltering()) buffer else buffer.filter { filter.matchesClientSide(it) }
        val page = matched.drop(bufferPos).take(pageSize)
        bufferPos += page.size
        return page
    }

    /** 一次拉取日期区间内的全部修订（服务端按日期裁剪，`limit=0` 不限条数）。 */
    private fun fetchByDateRange(filter: LogFilter): List<SvnChangeList> {
        val settings = ChangeBrowserSettings()
        filter.fromDate?.let {
            settings.USE_DATE_AFTER_FILTER = true
            settings.dateAfter = it
        }
        filter.toDate?.let {
            settings.USE_DATE_BEFORE_FILTER = true
            settings.dateBefore = it
        }
        val provider = context.vcs.committedChangesProvider as? SvnCommittedChangesProvider
        ?: throw IllegalStateException(
            EnhanceMergeBundle.message(
                "error.unexpected.changes.provider",
                context.vcs.committedChangesProvider,
            )
        )
        // limit = NO_LIMIT：日期模式要**一次拉完整个区间**（服务端按日期裁剪），分页在内存里做
        return provider.getCommittedChanges(settings, SvnRepositoryLocation(context.sourceUrl), NO_LIMIT)
    }

    // ------------------------------------------------------------ 模式 B

    private fun loadByCursor(filter: LogFilter, pageSize: Int): List<SvnChangeList> {
        if (allLoaded) return emptyList()

        val matched = ArrayList<SvnChangeList>()
        var batches = 0

        while (matched.size < pageSize && !allLoaded && batches < MAX_BATCHES) {
            batches++
            // 注入数据源（测试）优先；否则走原版静态方法（IDEA Pair → Kotlin Pair）
            val result = cursorSource?.invoke(cursor, pageSize)
                ?: MergeCalculatorTask.loadChangeLists(context, cursor, pageSize).let { it.first to it.second }
            val raw = result.first
            if (raw.isEmpty()) {
                allLoaded = true
                break
            }
            cursor = raw.last().number
            // 平台以 (changeLists.size() < size + 1) 判定是否到底；当恰好返回一整页时
            // 该判定可能误报「已全部加载」（合并层级会折叠部分条目，使计数偏小）。
            // 一整页时保守视为「可能还有」，留待下一次请求确认为空再停。
            allLoaded = result.second && raw.size < pageSize

            var reachedLowerBound = false
            val lowerBound = filter.revisionRange().first
            for (item in raw) {
                if (lowerBound != null && item.number < lowerBound) {
                    reachedLowerBound = true
                    break
                }
                if (!filter.isFiltering() || filter.matchesClientSide(item)) matched.add(item)
            }
            if (reachedLowerBound) allLoaded = true
        }
        return matched
    }

    /**
     * 「全部显示」被显式触发时调用：即使此前已判定到底，也允许再发一次请求确认。
     *
     * 为什么需要：`allLoaded` 的判定带启发式（见 [loadByCursor] 里"整页时保守视为可能还有"），
     * 极端情况下会误报"已到底"让按钮置灰；用户显式点「全部显示」时应当无条件再探测一次，
     * 而不是让按钮变成死的。
     */
    fun reopenIfExhausted() {
        if (allLoaded) allLoaded = false
    }

    companion object {
        /**
         * `getCommittedChanges` 的 limit=**不限条数**。
         *
         * 日期模式必须一次拉完整个区间（服务端按日期裁剪，分页在内存做），
         * 因此这里不能用任何具体数字——平台约定 0 = 无限制。
         */
        const val NO_LIMIT = 0

        /**
         * 单次翻页最多补几批原始数据（防止极端情况下无休止扫描）。
         *
         * 为什么必须设上限：过滤条件很窄时（例如只匹配 1 条），每一批 100 条原始数据可能
         * 只产出 0–1 条命中；不设上限会一路扫到 r1，把"没有匹配"变成一次全仓库遍历。
         * 10 批 × 100 条 = 最多扫 1000 条原始日志，是可接受的最坏情况。
         */
        const val MAX_BATCHES = 10
    }
}