package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogFilterField
import com.weixf.svn.mergeenhance.log.LogPageLoader
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.MergeContext
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private fun LogPageLoader.fetch(filter: LogFilter = LogFilter(), size: Int): List<SvnChangeList> =
        loadNextPageRaw(filter, size, snapshot())

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
    fun `reset applies the revision upper bound with plus one`() {
        val seen = mutableListOf<Long>()
        val loader = LogPageLoader(context, cursorSource = { cursor, _ ->
            seen += cursor
            emptyList<SvnChangeList>() to true
        })
        // 版本区间 [空, 20] → 游标应为 20 + 1（原版 loadChangeLists 排除等于 beforeRevision 的条目）
        loader.reset(LogFilter(revTo = 20L))
        assertEquals(21, loader.cursor)

        loader.fetch(size = 10)
        assertEquals(listOf(21L), seen)
    }

    @Test
    fun `reset without a range starts from head`() {
        val seen = mutableListOf<Long>()
        val loader = LogPageLoader(context, cursorSource = { cursor, _ ->
            seen += cursor
            emptyList<SvnChangeList>() to true
        })
        loader.reset(LogFilter(searchText = "login"))
        assertEquals(-1L, loader.cursor)
        loader.fetch(size = 10)
        assertEquals(listOf(-1L), seen)
    }

    @Test
    fun `cursor mode stops early at the revision lower bound`() {
        // 下界 15：遇到 < 15 的修订立即停止，不再继续取
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            page(20, 19, 18, 17, 16, 15, 14, 13, 12, 11) to false
        })
        val result = loader.fetch(LogFilter(revFrom = 15L), 10)
        // 只保留 >= 15 的 6 条
        assertEquals(listOf(20L, 19L, 18L, 17L, 16L, 15L), result.map { it.number })
        assertTrue(loader.allLoaded)
        assertEquals(1, calls)
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
        loader.reset(LogFilter(revTo = 10L))
        assertTrue(loader.snapshot() > before)
    }

    @Test
    fun `stale generation yields nothing instead of polluting the loader`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> page(10, 9, 8, 7, 6, 5, 4, 3, 2, 1) to false })
        val stale = loader.snapshot()
        // 期间条件变了（版本区间改动 → reset）
        loader.reset(LogFilter(revFrom = 3L))
        // 在飞任务用旧世代号继续取：必须空手而归，不得把旧条件的数据塞进新缓冲
        assertTrue(loader.loadNextPageRaw(LogFilter(), 10, stale).isEmpty())
        // 新世代号正常工作，且只拿到 >= 下界的条目
        val fresh = loader.fetch(LogFilter(revFrom = 3L), 10)
        assertEquals(listOf(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L), fresh.map { it.number })
    }

    @Test
    fun `a stale generation issues no request at all`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            page(50, 49) to false
        })
        val generation = loader.snapshot()
        // 条件变了（reset）后，在飞任务连一次请求都不该发出——发出去就是拿旧条件污染服务端往返
        loader.reset(LogFilter(revTo = 1L))
        assertTrue(loader.loadNextPageRaw(LogFilter(), 10, generation).isEmpty())
        assertEquals(0, call)
    }

    @Test
    fun `an invalid range falls back to head instead of an unreachable cursor`() {
        // revFrom > revTo（理论上 UI 已拦）：不能把游标设到下界之下，否则首屏直接被判到底
        val loader = LogPageLoader(context, cursorSource = { _, _ -> emptyList<SvnChangeList>() to false })
        loader.reset(LogFilter(revFrom = 100L, revTo = 10L))
        assertEquals(-1L, loader.cursor)
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
    fun `the loader ignores dates and search text entirely`() {
        // 关键契约：加载器只认版本区间。日期/文本由对话框在内存里过滤，
        // 因此带日期的 filter 不会让加载器多拉或少拉任何数据（对齐 TortoiseSVN）。
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            page(30, 29, 28, 27, 26, 25, 24, 23, 22, 21) to false
        })
        val withDate = LogFilter(
            dateRangeEnabled = true,
            fromDate = Date(0),
            toDate = Date(4102444800000L),
            searchText = "login",
            fields = setOf(LogFilterField.MESSAGE),
        )
        val raw = loader.fetch(withDate, 10)
        assertEquals(10, raw.size)
        assertEquals(30L, raw.first().number)
        assertEquals(21L, raw.last().number)
        assertEquals(1, calls)
        // 加载器返回的是**原始**条目：带 searchText 的 filter 一条都不命中，
        // 证明"过滤是调用方（对话框在内存里）的职责"，加载器完全不参与
        assertEquals(0, raw.count { withDate.matches(it) })
    }
}
