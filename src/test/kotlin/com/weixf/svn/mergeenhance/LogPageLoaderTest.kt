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
 * 分页加载器双模式测试（T10.3）。
 *
 * 通过 `cursorSource` / `dateRangeSource` 注入假数据，不连仓库。
 *
 * 为什么这两个注入点必须存在：`LogPageLoader` 原本直接调 `MergeCalculatorTask.loadChangeLists`
 * （static，直连仓库）和 `SvnCommittedChangesProvider`，单元测试里无法 stub 静态方法，
 * 也无法真的连一个 SVN 仓库。抽成可空 lambda 后默认行为完全不变（null 即走原版路径），
 * 测试只覆盖"分页/游标/早停/补批"这些纯逻辑。
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

    // -------------------------------------------------- 模式 B：游标推进

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

        val first = loader.loadNextPage(LogFilter(), 10)
        assertEquals(10, first.size)
        assertEquals(30, first.first().number)
        assertEquals(21, first.last().number)
        assertEquals(21, loader.cursor)
        assertFalse(loader.allLoaded)
        assertEquals(-1L to 10, calls.single())

        val second = loader.loadNextPage(LogFilter(), 10)
        assertEquals(10, second.size)
        assertEquals(20, second.first().number)
        assertEquals(11, second.last().number)
        assertEquals(11, loader.cursor)
        assertEquals(21L to 10, calls.last())
    }

    @Test
    fun `cursor mode stops when platform reports last page and page is short`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> page(5, 4, 3) to true })
        val page1 = loader.loadNextPage(LogFilter(), 10)
        assertEquals(3, page1.size)
        assertTrue(loader.allLoaded)
        assertTrue(loader.loadNextPage(LogFilter(), 10).isEmpty())
    }

    @Test
    fun `cursor mode stays optimistic when a full page is the last one`() {
        // 平台判定 isLast=true 但恰好返回一整页：保守视为「可能还有」，下次请求确认为空再停
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            if (call == 1) page(10, 9, 8, 7, 6, 5, 4, 3, 2, 1) to true else emptyList<SvnChangeList>() to true
        })
        val first = loader.loadNextPage(LogFilter(), 10)
        assertEquals(10, first.size)
        assertFalse(loader.allLoaded)

        val second = loader.loadNextPage(LogFilter(), 10)
        assertTrue(second.isEmpty())
        assertTrue(loader.allLoaded)
    }

    @Test
    fun `cursor mode marks all loaded on empty response`() {
        val loader = LogPageLoader(context, cursorSource = { _, _ -> emptyList<SvnChangeList>() to false })
        assertTrue(loader.loadNextPage(LogFilter(), 10).isEmpty())
        assertTrue(loader.allLoaded)
    }

    @Test
    fun `reset applies the revision upper bound with plus one`() {
        val seen = mutableListOf<Long>()
        val loader = LogPageLoader(context, cursorSource = { cursor, _ ->
            seen += cursor
            emptyList<SvnChangeList>() to true
        })
        // 搜索词形如 "10-20" → revisionRange = (10, 20)，游标应为 20 + 1
        loader.reset(LogFilter(searchText = "10-20", fields = setOf(LogFilterField.REVISION)))
        assertEquals(21, loader.cursor)

        loader.loadNextPage(LogFilter(searchText = "10-20"), 10)
        assertEquals(listOf(21L), seen)
    }

    @Test
    fun `cursor mode stops early at the revision lower bound`() {
        // 下界 15：遇到 < 15 的修订立即停止，不再继续取
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            page(20, 19, 18, 17, 16, 15, 14, 13, 12, 11) to false
        })
        val filter = LogFilter(searchText = "15-30", fields = setOf(LogFilterField.REVISION))
        val result = loader.loadNextPage(filter, 10)
        // 只保留 >= 15 的 6 条
        assertEquals(listOf(20L, 19L, 18L, 17L, 16L, 15L), result.map { it.number })
        assertTrue(loader.allLoaded)
        assertEquals(1, calls)
    }

    @Test
    fun `cursor mode refills when the first batch is short`() {
        // 模式 B 一页可能超过 pageSize（整批加入），但第一批不足 pageSize 时必然补批
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            if (call == 1) page(10, 9) to false else page(8, 7, 6, 5, 4) to false
        })
        val result = loader.loadNextPage(LogFilter(), 3)
        assertEquals(2, call)        // 第一批 2 条不足 3 → 触发补批
        assertEquals(7, result.size) // 2 + 5（第二批整批加入，故超过 pageSize）
        assertEquals(10, result.first().number)
        assertEquals(4, result.last().number)
    }

    @Test
    fun `cursor mode refills when filtering removes most items`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            // 每批 5 条，仅第一条命中过滤词 keep
            val batch = (0 until 5).map { list(50L - call * 5 - it.toLong(), if (it == 0) "keep" else "drop") }
            batch to false
        })
        val result = loader.loadNextPage(LogFilter(searchText = "keep"), 3)
        assertEquals(3, result.size)
        assertTrue(result.all { it.comment == "keep" })
        assertEquals(3, call)
        assertFalse(loader.allLoaded)
    }

    @Test
    fun `cursor mode gives up after max batches`() {
        var call = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            call++
            // 永远只有 1 条命中，永远凑不满 pageSize=100 → 触发 MAX_BATCHES 上限
            listOf(list(1000L - call, "keep")) to false
        })
        val result = loader.loadNextPage(LogFilter(searchText = "keep"), 100)
        assertEquals(LogPageLoader.MAX_BATCHES, call)
        assertEquals(LogPageLoader.MAX_BATCHES, result.size)
    }

    // -------------------------------------------------- 模式 A：日期内存分页

    /** 2100-01-01，用作「足够晚」的日期上界，避免测试数据被日期过滤掉。 */
    private val farFuture = Date(4102444800000L)

    @Test
    fun `date mode fetches once and pages in memory`() {
        var fetches = 0
        val all = (1..25).map { list(it.toLong()) }
        val loader = LogPageLoader(
            context,
            dateRangeSource = { fetches++; all },
        )
        val filter = LogFilter(dateRangeEnabled = true, fromDate = Date(0), toDate = farFuture)

        val p1 = loader.loadNextPage(filter, 10)
        assertEquals(10, p1.size)
        assertEquals(1, fetches)

        val p2 = loader.loadNextPage(filter, 10)
        assertEquals(10, p2.size)
        assertEquals(11, p2.first().number)

        val p3 = loader.loadNextPage(filter, 10)
        assertEquals(5, p3.size)

        val p4 = loader.loadNextPage(filter, 10)
        assertTrue(p4.isEmpty())
        assertEquals(1, fetches)
        assertTrue(loader.allLoaded)
    }

    @Test
    fun `date mode applies client side filtering`() {
        val all = (1..20).map { list(it.toLong(), comment = if (it % 2 == 0) "even" else "odd") }
        val loader = LogPageLoader(context, dateRangeSource = { all })
        val filter = LogFilter(dateRangeEnabled = true, fromDate = Date(0), toDate = farFuture, searchText = "even")

        val p1 = loader.loadNextPage(filter, 3)
        assertEquals(3, p1.size)
        assertTrue(p1.all { it.comment == "even" })
        assertTrue(loader.allLoaded) // 日期模式一次拉完，立即标记到底
    }

    @Test
    fun `reopenIfExhausted allows one more round trip`() {
        var calls = 0
        val loader = LogPageLoader(context, cursorSource = { _, _ ->
            calls++
            if (calls == 1) emptyList<SvnChangeList>() to true else page(7, 6) to true
        })
        assertTrue(loader.loadNextPage(LogFilter(), 10).isEmpty())
        assertTrue(loader.allLoaded)

        loader.reopenIfExhausted()
        val second = loader.loadNextPage(LogFilter(), 10)
        assertEquals(2, second.size)
        assertTrue(loader.allLoaded)
    }
}
