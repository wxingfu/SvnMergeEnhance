package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogPageLoader
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.api.Url
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.history.SvnCommittedChangesProvider
import org.jetbrains.idea.svn.integrate.MergeContext
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分页加载器测试（游标单通道 + 世代号）。
 *
 * 通过 `cursorSource` 注入假数据，不连仓库。
 *
 * ## 为什么只有一种加载模式
 *
 * 早期实现有两条通道：有日期区间时 `limit=0` 一次拉完整个区间（模式 A），无日期时走游标分页（模式 B）。
 * 两条通道请求的**根本不是同一份数据**（前者不带 `-g`、Target 带 peg），于是勾/不勾日期区间
 * 会得到不同结果集，且"改搜索词"会隐式切换通道。现已按 TortoiseSVN 的做法收敛为单通道
 * （日期区间只做客户端过滤），因此本类不再有模式 A 用例。
 *
 * 版本区间维度已在第四轮整体删除（用户决策「不需要版本区间」）：游标恒从 HEAD 开始，
 * `reset()` 不再接受任何参数。
 *
 * `MergeContext` 用 relaxed mock：加载器只在默认数据源路径上读它，注入路径完全不碰。
 */
class LogPageLoaderTest {

    private val context: MergeContext = mockk(relaxed = true)

    private fun list(number: Long, comment: String = "c$number", paths: Set<String> = setOf("/a.java")): SvnChangeList =
        mockk<SvnChangeList>().apply {
            every { this@apply.number } returns number
            every { this@apply.comment } returns comment
            every { this@apply.committerName } returns "alice"
            every { this@apply.commitDate } returns Date(1579084800000L)
            every { this@apply.affectedPaths } returns paths
        }

    private fun page(vararg numbers: Long) = numbers.map { list(it) }

    /** 取一页：自动带上当前世代号，模拟"调用方在启动任务前抓到快照"。 */
    private fun LogPageLoader.fetch(size: Int): List<SvnChangeList> =
        loadNextPageRaw(size, snapshot())

    // -------------------------------------------------- 游标推进

    @Test
    fun `cursor mode returns pages and advances the cursor`() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val loader = LogPageLoader(context, cursorSource = { cursor, size ->
            calls += cursor to size
            when (cursor) {
                -1L -> page(30, 29, 28, 27, 26, 25, 24, 23, 22, 21) to false
                else -> page(20, 19, 18, 17, 16, 15, 14, 13, 12, 11) to false
            }
        })

        val first = loader.fetch(size = 10)
        assertEquals(10, first.size)
        assertEquals(30, first.first().number)
        assertEquals(21, first.last().number)
        assertEquals(21, loader.cursor)
        assertFalse(loader.allLoaded)
        assertEquals(-1L to 10, calls.single())

        val second = loader.fetch(size = 10)
        assertEquals(10, second.size)
        assertEquals(20, second.first().number)
        assertEquals(11, second.last().number)
        assertEquals(11, loader.cursor)
        assertEquals(21L to 10, calls.last())
    }

    @Test
    fun `cursor mode stops when platform reports last page and page is short`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> page(5, 4, 3) to true })
        assertEquals(3, loader.fetch(size = 10).size)
        assertTrue(loader.allLoaded)
        assertTrue(loader.fetch(size = 10).isEmpty())
    }

    @Test
    fun `cursor mode stays optimistic when a full page is the last one`() {
        // 平台判定 isLast=true 但恰好返回一整页：保守视为「可能还有」，下次请求确认为空再停
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            if (call == 1) page(10, 9, 8, 7, 6, 5, 4, 3, 2, 1) to true else emptyList<SvnChangeList>() to true
        })
        val first = loader.fetch(size = 10)
        assertEquals(10, first.size)
        assertFalse(loader.allLoaded)

        assertTrue(loader.fetch(size = 10).isEmpty())
        assertTrue(loader.allLoaded)
    }

    @Test
    fun `cursor mode marks all loaded on empty response`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> emptyList<SvnChangeList>() to false })
        assertTrue(loader.fetch(size = 10).isEmpty())
        assertTrue(loader.allLoaded)
    }


    @Test
    fun `reset starts from head`() {
        val seen = mutableListOf<Long>()
        val loader = LogPageLoader(context, cursorSource = { cursor, _ ->
            seen += cursor
            emptyList<SvnChangeList>() to true
        })
        loader.reset()
        assertEquals(-1L, loader.cursor)
        loader.fetch(size = 10)
        assertEquals(listOf(-1L), seen)
    }


    @Test
    fun `cursor mode refills when the first batch is short`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            if (call == 1) page(10, 9) to false else page(8, 7, 6, 5, 4) to false
        })
        val result = loader.fetch(size = 3)
        assertEquals(2, call)        // 第一批 2 条不足 3 → 触发补批
        assertEquals(7, result.size) // 2 + 5（第二批整批加入，故超过 pageSize）
        assertEquals(10, result.first().number)
        assertEquals(4, result.last().number)
    }

    @Test
    fun `cursor mode gives up after max batches`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            // 永远只有 1 条，永远凑不满 pageSize=100 → 触发 MAX_BATCHES 上限
            listOf(list(1000L - call)) to false
        })
        val result = loader.fetch(size = 100)
        assertEquals(LogPageLoader.MAX_BATCHES, call)
        assertEquals(LogPageLoader.MAX_BATCHES, result.size)
    }

    // -------------------------------------------------- 世代号（并发防串）

    @Test
    fun `reset bumps the generation`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> emptyList<SvnChangeList>() to false })
        val before = loader.snapshot()
        loader.reset()
        assertTrue(loader.snapshot() > before)
    }

    @Test
    fun `stale generation yields nothing instead of polluting the loader`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> page(10, 9, 8, 7, 6, 5, 4, 3, 2, 1) to false })
        val stale = loader.snapshot()
        // 期间条件变了（重新整理 → reset）
        loader.reset()
        // 在飞任务用旧世代号继续取：必须空手而归，不得把旧条件的数据塞进新缓冲
        assertTrue(loader.loadNextPageRaw(10, stale).isEmpty())
        // 新世代号正常工作：拿回完整的一页（版本区间维度已删除，无下界早停）
        val fresh = loader.fetch(10)
        assertEquals(listOf(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), fresh.map { it.number })
    }

    @Test
    fun `a stale generation issues no request at all`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            page(50, 49) to false
        })
        val generation = loader.snapshot()
        // reset 后，在飞任务连一次请求都不该发出——发出去就是拿旧条件污染服务端往返
        loader.reset()
        assertTrue(loader.loadNextPageRaw(10, generation).isEmpty())
        assertEquals(0, call)
    }


    @Test
    fun `reopenIfExhausted allows one more round trip`() {
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            if (calls == 1) emptyList<SvnChangeList>() to true else page(7, 6) to true
        })
        assertTrue(loader.fetch(size = 10).isEmpty())
        assertTrue(loader.allLoaded)

        loader.reopenIfExhausted()
        val second = loader.fetch(size = 10)
        assertEquals(2, second.size)
        assertTrue(loader.allLoaded)
    }

    // -------------------------------------------------- 与过滤条件的分工

    @Test
    fun `the loader ignores search text entirely`() {
        // 关键契约：加载器**只按修订号游标**取数。搜索词全部由对话框在内存里过滤，
        // 因此加载器不受任何过滤条件影响（对齐 TortoiseSVN：服务端只认 m_startRev/m_limit）。
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            page(30, 29, 28, 27, 26, 25, 24, 23, 22, 21) to false
        })
        val withSearch = LogFilter(searchText = "login")
        val raw = loader.fetch(10)
        assertEquals(10, raw.size)
        assertEquals(30L, raw.first().number)
        assertEquals(21L, raw.last().number)
        assertEquals(1, calls)
        // 加载器返回的是**原始**条目：过滤是调用方（对话框在内存里）的职责，加载器完全不参与
        assertEquals(0, raw.count { withSearch.matches(it) })
    }

    // -------------------------------------------------- 取消

    /**
     * 进度条上的「取消」必须能真正中止分页。
     *
     * 以前分页循环完全不感知 indicator：按了取消也停不下来，数据照样回来并写进表格
     * （用户以为取消了，其实又多等了一轮扫描）。
     */
    @Test
    fun `loadNextPageRaw stops when the indicator is cancelled`() {
        var calls = 0
        var checks = 0
        // 模拟用户按下进度条上的「取消」：第 2 次取消点检查时抛 ProcessCanceledException
        val indicator = mockk<ProgressIndicator>()
        every { indicator.checkCanceled() } answers {
            if (++checks > 1) throw ProcessCanceledException()
        }
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            // 每页只给 3 条（不足 pageSize）→ 分页循环会继续下一批，才有机会撞上第 2 个取消点
            page(30, 29, 28) to false
        })

        assertFailsWith<ProcessCanceledException> {
            loader.loadNextPageRaw(10, loader.snapshot(), indicator)
        }
        // 只跑了第一批就被取消（不会继续扫仓库）
        assertEquals(1, calls)
    }

    // -------------------------------------------------- 只取源分支自身

    /**
     * 回归看守（第九轮）：加载器**绝不能**再走 `getCommittedChangesWithMergedRevisons`。
     *
     * 该接口（`includeMergedRevisions = true`）会把**合并源分支**的修订也带进清单——
     * 即「同一个需求在别的分支上的副本」（路径相对源分支）。这些副本：
     * - 在目标上并不存在 → **合并不进去**（用户勾了却什么都没发生 = 版本缺失）；
     * - mergeinfo 检查一律 `NOT_EXISTS` → 状态 `ALIEN`（**图标为 null**）→ 行首无状态图标。
     *
     * 原版合并对话框正是这个缺陷的受害者；本插件改用 `getCommittedChanges`
     * （`includeMergedRevisions = false`，且不走缓存）。
     */
    @Test
    fun `loader never asks the provider for merged revisions`() {
        val provider = mockk<SvnCommittedChangesProvider>(relaxed = true)
        val vcs = mockk<SvnVcs>(relaxed = true)
        every { vcs.committedChangesProvider } returns provider
        val ctx = mockk<MergeContext>(relaxed = true)
        every { ctx.vcs } returns vcs
        every { ctx.sourceUrl } returns mockk<Url>(relaxed = true)
        every { provider.getCommittedChanges(any(), any(), any()) } returns emptyList()

        val loader = LogPageLoader(ctx)
        loader.loadNextPageRaw(10, loader.snapshot())

        verify(exactly = 0) { provider.getCommittedChangesWithMergedRevisons(any(), any(), any(), any()) }
        verify(atLeast = 1) { provider.getCommittedChanges(any(), any(), any()) }
    }

    // -------------------------------------------------- 翻页边界（P0-1）

    /**
     * 回归看守（P0-1，2026-11-01）：**「CHANGE_BEFORE」区间含端点**，
     * 游标那条修订会在下一页**重复出现**。
     *
     * 原版对此有明确处理（`MergeCalculatorTask.getChangeLists`）：请求 `size + 2` 并**显式剔除**
     * `revisionToExclude`。不剔除的后果是**静默丢修订**：重复条目占掉 `take(size)` 的一个名额，
     * 于是一条**更新的新修订**被挤掉，而游标已越过它 → 那条修订此后既不显示、也不进合并集合。
     *
     * 本测试用 mock provider 复刻服务端语义（按 `CHANGE_BEFORE` 过滤、**含端点**、降序、限条数）。
     */
    @Test
    fun `cursor boundary duplicate is dropped and no newer revision is lost`() {
        val provider = mockk<SvnCommittedChangesProvider>(relaxed = true)
        val vcs = mockk<SvnVcs>(relaxed = true)
        every { vcs.committedChangesProvider } returns provider
        val ctx = mockk<MergeContext>(relaxed = true)
        every { ctx.vcs } returns vcs
        every { ctx.sourceUrl } returns mockk<Url>(relaxed = true)

        // 仓库里共 20 条修订：r30 … r11（降序）
        val repository = (30L downTo 11L).toList()
        val requestedSizes = mutableListOf<Int>()
        every { provider.getCommittedChanges(any(), any(), any()) } answers {
            val maxCount = thirdArg<Int>()
            requestedSizes += maxCount
            val settings = firstArg<ChangeBrowserSettings>()
            // 「CHANGE_BEFORE」区间**含端点**：before 本身也在结果里
            val before = if (settings.USE_CHANGE_BEFORE_FILTER) settings.CHANGE_BEFORE.toLong() else Long.MAX_VALUE
            repository.filter { it <= before }.take(maxCount).map { list(it) }
        }

        val loader = LogPageLoader(ctx)
        val first = loader.loadNextPageRaw(10, loader.snapshot())
        assertEquals((30L downTo 21L).toList(), first.map { it.number })

        val second = loader.loadNextPageRaw(10, loader.snapshot())
        // 第二页必须**全是新修订**：既不含重复的 r21，也不丢 r20 / r11
        assertEquals((20L downTo 11L).toList(), second.map { it.number })

        // 首屏多取 1 条（判定还有更多）；翻页多取 2 条（1 条判定 + 1 条用于剔除游标条目）
        assertEquals(listOf(11, 12), requestedSizes)
    }

    // -------------------------------------------------- 取消/世代号与游标（P1-2）

    /**
     * 回归看守（P1-2，2026-11-01）：取消发生在「数据已取回、尚未交付」之间时，
     * **游标不得推进**。
     *
     * 原实现边取边写字段：取消后该批数据被调用方丢弃、游标却已越过它 →
     * 那批修订永远取不回来（静默丢修订）。现在游标与结束标志在**正常返回前**才提交。
     */
    @Test
    fun `cancellation does not advance the cursor`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> page(30, 29, 28) to false })
        val indicator = mockk<ProgressIndicator>()
        every { indicator.checkCanceled() } throws ProcessCanceledException()

        assertFailsWith<ProcessCanceledException> {
            loader.loadNextPageRaw(10, loader.snapshot(), indicator)
        }
        // 游标未推进 → 用户重试时这一批仍会被取回
        assertEquals(-1L, loader.cursor)
        assertFalse(loader.allLoaded)
    }

    /**
     * 回归看守：取数期间发生 `reset`（世代号变化）时，本轮**返回空**——
     * 半截旧数据不得混进新缓冲（与类文档承诺一致）。
     */
    @Test
    fun `a generation change during paging discards the half fetched page`() {
        lateinit var loader: LogPageLoader
        loader = LogPageLoader(context, cursorSource = { _, _ ->
            val fetched = page(30, 29, 28)
            loader.reset() // 模拟取数期间发生了 reset
            fetched to false
        })

        val result = loader.loadNextPageRaw(10, loader.snapshot())
        assertTrue(result.isEmpty())
        assertEquals(-1L, loader.cursor)
    }
}
