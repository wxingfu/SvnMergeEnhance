package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.ui.RevisionAccumulator
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.idea.svn.history.SvnChangeList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 已加载修订全集的累积测试（**回归看守**）。
 *
 * ## 为什么值得测
 *
 * 曾把「批内同修订号」当重复项去重（循环内回填去重集合），结果**丢掉合法的条目**：
 * SVN 的「合并修订」在日志里会按**路径**展开成多个条目——同一修订号多条、路径集合不同
 * （svn4idea \MergeSourceHierarchyBuilder\ + \SvnMergeSourceTracker\）。
 * 表格因此少行、合并集合缺修订（真机在有合并修订的分支上验证过）。
 *
 * 正确语义：**只与「此前已加载的集合」去重，批内重复项全部保留**（与原版插件一致）。
 */
class RevisionAccumulatorTest {

    private fun list(number: Long): SvnChangeList =
        mockk<SvnChangeList>().apply {
            every { this@apply.number } returns number
        }

    @Test
    fun keepsDuplicatedRevisionNumbersInsideOneBatch() {
        // 同一修订号在一批里出现两条 = 合并修订按路径展开，是**不同条目**
        val added = listOf(list(100L), list(100L))

        val merged = RevisionAccumulator.merge(emptyList(), added)

        assertEquals(2, merged.size)
        assertEquals(listOf(100L, 100L), merged.map { it.number })
    }

    @Test
    fun dropsCrossPageDuplicatesButKeepsTheRest() {
        val merged = RevisionAccumulator.merge(listOf(list(100L)), listOf(list(100L), list(99L)))

        assertEquals(listOf(100L, 99L), merged.map { it.number })
    }

    @Test
    fun appendsNewRevisionsPreservingOrder() {
        val merged = RevisionAccumulator.merge(listOf(list(100L)), listOf(list(99L), list(98L)))

        assertEquals(listOf(100L, 99L, 98L), merged.map { it.number })
    }

    @Test
    fun returnsTheSameListWhenThereIsNothingNew() {
        val loaded = listOf(list(100L))

        assertSame(loaded, RevisionAccumulator.merge(loaded, emptyList()))
        assertSame(loaded, RevisionAccumulator.merge(loaded, listOf(list(100L))))
    }
}
