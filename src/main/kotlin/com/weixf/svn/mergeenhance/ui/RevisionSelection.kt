package com.weixf.svn.mergeenhance.ui

import org.jetbrains.idea.svn.integrate.QuantitySelection

/**
 * 修订勾选语义的**纯逻辑**（无 UI、无平台依赖，可直接单测）。
 *
 * ## 为什么单独抽出来
 *
 * 勾选集合的解析曾经直接写在面板里，并误用「当前过滤后的视图」作为数据源：
 * 用户勾了 r100、r200，随后输入只匹配 r200 的搜索词 → 视图只剩 r200 →
 * 合并集合里 r100 被**静默剔除**（勾选框还显示「已勾」）。这是合并工具的
 * **数据正确性**缺陷，必须有回归测试看守，故把语义收敛到这里。
 *
 * ## 语义约定
 *
 * - 勾选以**修订号**为准（稀疏集合，跨翻页 / 跨过滤保留）；
 * - **过滤只是视图**：过滤不改变勾选，也不改变合并集合；
 * - 「全选」有两种语义，由是否存在过滤条件决定（见 [selectAll]）。
 */
internal object RevisionSelection {

    /**
     * 解析「已勾选的修订」。
     *
     * @param universe **已加载全集**（不是当前过滤后的视图）；
     * @param numberOf 取元素的修订号（勾选以修订号为标识）；
     * @param selection 稀疏勾选状态（修订号集合）。
     *
     * - 显式勾选模式：取 `selected` 中的修订；
     * - 全选模式（`setAll()`）：取全集去掉 `unselected`（"全选后排除个别"）。
     */
    fun <T> resolve(
        universe: List<T>,
        numberOf: (T) -> Long,
        selection: QuantitySelection<Long>,
    ): List<T> {
        val selected = selection.selected
        val unselected = selection.unselected
        return if (selection.areAllSelected()) {
            universe.filter { !unselected.contains(numberOf(it)) }
        } else {
            universe.filter { selected.contains(numberOf(it)) }
        }
    }

    /**
     * 「全选」。
     *
     * - **无过滤条件**：切换到全选模式（[QuantitySelection.setAll]），即选中已加载全集，
     *   之后可逐个排除（"全选后排除个别"的稀疏语义）；
     * - **有过滤条件**：只选中**当前可见**的行（显式模式）。
     *   否则「全选」会把被过滤掉的成千上万条也纳入合并集合 —— 用户以为只合并看到的几条，
     *   实际合并上万条，属于数据正确性问题。
     *
     * @param visibleNumbers 当前可见行的修订号；
     * @param hasFilter      当前是否存在过滤条件（搜索词非空）。
     */
    fun selectAll(
        visibleNumbers: List<Long>,
        hasFilter: Boolean,
        selection: QuantitySelection<Long>,
    ) {
        if (hasFilter) {
            selection.clearAll()                    // 切到「显式选中」起点
            visibleNumbers.forEach { selection.add(it) }
        } else {
            selection.setAll()
        }
    }
}
