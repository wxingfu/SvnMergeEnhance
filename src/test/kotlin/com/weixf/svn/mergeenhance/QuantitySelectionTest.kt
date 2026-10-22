package com.weixf.svn.mergeenhance

import org.jetbrains.idea.svn.integrate.QuantitySelection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `QuantitySelection` 稀疏勾选语义测试（T10.2）。
 *
 * 语义（与原版 `ToBeMergedDialog` 共用同一实例，属"复用原版行为"的回归测试）：
 * - 从「全选」起点的实例：默认全部选中，`unselected` 为排除集；
 * - 从「全不选」起点的实例：默认全部未选，`selected` 为勾选集；
 * - `setAll()` / `clearAll()` 切换起点并清空另一侧。
 *
 * 为什么值得测一个"只是复用"的类：`RevisionSelectPanel.selectedChangeLists()` 的正确性
 * **完全依赖** `areAllSelected()` 的原版语义——它在 selectAll 起点下恒为 true，
 * 于是"全选后排除个别"必须走 `unselected` 排除集分支。若有人"修正"这个看似奇怪的行为，
 * 勾选会静默退化（全选后得到空列表）。本测试类把该语义钉死。
 */
class QuantitySelectionTest {

    // -------------------------------------------------- 起点 = 全不选

    @Test
    fun `starts empty when constructed with false`() {
        val selection = QuantitySelection<Long>(false)
        assertFalse(selection.isSelected(1L))
        assertFalse(selection.areAllSelected())
        assertTrue(selection.selected.isEmpty())
    }

    @Test
    fun `add and remove work as explicit set`() {
        val selection = QuantitySelection<Long>(false)
        selection.add(10L)
        selection.add(11L)
        assertTrue(selection.isSelected(10L))
        assertTrue(selection.isSelected(11L))
        assertFalse(selection.isSelected(12L))

        selection.remove(10L)
        assertFalse(selection.isSelected(10L))
        assertTrue(selection.isSelected(11L))
    }

    @Test
    fun `clearAll empties explicit set`() {
        val selection = QuantitySelection<Long>(false)
        selection.add(1L)
        selection.add(2L)
        selection.clearAll()
        assertFalse(selection.isSelected(1L))
        assertFalse(selection.isSelected(2L))
        assertFalse(selection.areAllSelected())
    }

    @Test
    fun `areAllSelected is true only for select-all start with no exclusions`() {
        val selection = QuantitySelection<Long>(false)
        selection.add(1L)
        assertFalse(selection.areAllSelected())

        selection.clearAll()
        selection.setAll()
        assertTrue(selection.areAllSelected())
    }

    // -------------------------------------------------- 起点 = 全选（稀疏排除）

    @Test
    fun `starts with everything selected when constructed with true`() {
        val selection = QuantitySelection<Long>(true)
        assertTrue(selection.isSelected(1L))
        assertTrue(selection.isSelected(999L))
        assertTrue(selection.areAllSelected())
        assertTrue(selection.unselected.isEmpty())
    }

    @Test
    fun `select all then exclude individual items`() {
        val selection = QuantitySelection<Long>(true)
        selection.remove(5L)
        selection.remove(7L)

        // 排除集生效：被排除的不选中，其余仍选中
        assertFalse(selection.isSelected(5L))
        assertFalse(selection.isSelected(7L))
        assertTrue(selection.isSelected(6L))
        assertTrue(selection.isSelected(100L))
        // 注意（原版语义）：areAllSelected() 只表示「起点为全选」，排除个别项不会改变它。
        // RevisionSelectPanel.selectedChangeLists() 正是靠它走「排除集」分支，故此行为必须固化。
        assertTrue(selection.areAllSelected())
        assertTrue(selection.unselected.containsAll(setOf(5L, 7L)))
    }

    @Test
    fun `re-adding an excluded item restores it`() {
        val selection = QuantitySelection<Long>(true)
        selection.remove(5L)
        assertFalse(selection.isSelected(5L))
        selection.add(5L)
        assertTrue(selection.isSelected(5L))
        assertFalse(selection.unselected.contains(5L))
    }

    @Test
    fun `setAll after clearAll returns to all-selected`() {
        val selection = QuantitySelection<Long>(true)
        selection.remove(1L)
        selection.clearAll()
        assertFalse(selection.areAllSelected())
        assertFalse(selection.isSelected(1L))

        selection.setAll()
        assertTrue(selection.areAllSelected())
        assertTrue(selection.isSelected(1L))
        assertTrue(selection.unselected.isEmpty())
    }

    @Test
    fun `unselect all from select-all start leaves nothing selected`() {
        val selection = QuantitySelection<Long>(true)
        selection.clearAll()
        assertFalse(selection.isSelected(1L))
        assertFalse(selection.areAllSelected())
    }
}
