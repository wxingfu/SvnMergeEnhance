package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.ui.RevisionSelection
import org.jetbrains.idea.svn.integrate.QuantitySelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 修订勾选语义测试（**回归看守**）。
 *
 * ## 为什么值得测
 *
 * 勾选集合的解析曾经误用「当前过滤后的视图」作为数据源：
 * 用户勾了 r100、r200，随后输入只匹配 r200 的搜索词 → 视图只剩 r200 →
 * **r100 被静默剔除出合并集合**（勾选框还显示「已勾」）。
 * 这是合并工具的**数据正确性**缺陷，必须有测试钉死，故把语义抽到 [RevisionSelection] 纯逻辑里。
 *
 * ## 语义约定
 *
 * - 勾选以修订号为准（稀疏集合，跨翻页 / 跨过滤保留）；
 * - 过滤只是视图：过滤不改变勾选，也不改变合并集合；
 * - 「全选」：无过滤 → 选中已加载全集（可逐个排除）；有过滤 → 只选当前可见行。
 */
class RevisionSelectionTest {

    private fun newSelection(): QuantitySelection<Long> = QuantitySelection(false)

    // -------------------------------------------------- 解析：数据源是全集

    @Test
    fun `explicit selection is resolved against the whole universe not the filtered view`() {
        val selection = newSelection()
        selection.add(100L)
        selection.add(200L)

        val universe = listOf(100L, 150L, 200L)
        // 视图（过滤后）只剩 200 —— 合并集合必须仍含 100
        val filteredView = listOf(200L)

        val resolved = RevisionSelection.resolve(universe, { it }, selection)

        assertEquals(listOf(100L, 200L), resolved)
        // 视图内容完全不参与解析（这正是回归点）
        assertTrue(filteredView.isNotEmpty())
    }

    @Test
    fun `explicit selection survives an empty filtered view`() {
        val selection = newSelection()
        selection.add(100L)

        val resolved = RevisionSelection.resolve(listOf(100L, 200L), { it }, selection)

        assertEquals(listOf(100L), resolved)
    }

    @Test
    fun `all mode resolves to the universe minus the excluded ones`() {
        val selection = newSelection()
        selection.setAll()
        selection.remove(150L)

        val resolved = RevisionSelection.resolve(listOf(100L, 150L, 200L), { it }, selection)

        assertEquals(listOf(100L, 200L), resolved)
    }

    @Test
    fun `empty selection resolves to nothing`() {
        val selection = newSelection()

        val resolved = RevisionSelection.resolve(listOf(100L, 200L), { it }, selection)

        assertTrue(resolved.isEmpty())
    }

    // -------------------------------------------------- 「全选」

    @Test
    fun `select all without filter switches to all mode`() {
        val selection = newSelection()

        RevisionSelection.selectAll(
            visibleNumbers = listOf(100L, 200L),
            hasFilter = false,
            selection = selection,
        )

        assertTrue(selection.areAllSelected())
        assertEquals(listOf(100L, 200L), RevisionSelection.resolve(listOf(100L, 200L), { it }, selection))
    }

    @Test
    fun `select all with filter selects only the visible rows`() {
        val selection = newSelection()

        RevisionSelection.selectAll(
            visibleNumbers = listOf(200L),
            hasFilter = true,
            selection = selection,
        )

        assertFalse(selection.areAllSelected())
        // 只有可见行进了合并集合：被过滤掉的 100 不会被顺带合并（否则会误合并上万条）
        assertEquals(listOf(200L), RevisionSelection.resolve(listOf(100L, 200L), { it }, selection))
    }

    @Test
    fun `select all with filter then clear filter keeps the explicit selection`() {
        val selection = newSelection()

        RevisionSelection.selectAll(listOf(200L), hasFilter = true, selection = selection)
        // 过滤条件消失后，勾选仍是那一条（过滤不改变勾选）
        val resolved = RevisionSelection.resolve(listOf(100L, 200L), { it }, selection)

        assertEquals(listOf(200L), resolved)
    }
}
