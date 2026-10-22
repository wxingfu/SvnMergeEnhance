package com.weixf.svn.mergeenhance.ui

import org.jetbrains.idea.svn.history.SvnChangeList

/**
 * 已加载修订全集的**累积**（去重语义的关键所在，单独抽出以便单测看守）。
 */
internal object RevisionAccumulator {

    /**
     * 把新一页并入已加载全集，返回新的全集（不可变；原列表不变）。
     *
     * ⚠️ **只与「此前已加载的集合」去重，必须保留批内重复项**：
     * SVN 的「合并修订」在日志里会按**路径**展开成多个条目——同一个修订号会出现多条、
     * 但各自代表不同的路径集合（见 svn4idea `MergeSourceHierarchyBuilder` +
     * `SvnMergeSourceTracker`，`getCommittedChangesWithMergedRevisons` 走的就是它）。
     * 这些是**不同的条目**，按修订号去重会**丢数据**（表格少行、合并集合缺修订）。
     * 原版插件同样保留批内重复（它的去重集合在循环内不更新）。
     *
     * 跨页去重仍然保留：同一修订号若在上一页已出现过，本页的副本不再重复入集
     * （翻页边界上偶发重复，属原版既有行为）。
     */
    fun merge(loaded: List<SvnChangeList>, added: List<SvnChangeList>): List<SvnChangeList> {
        if (added.isEmpty()) return loaded
        val existing = loaded.mapTo(HashSet()) { it.number }
        val fresh = added.filter { it.number !in existing }
        if (fresh.isEmpty()) return loaded
        val merged = ArrayList<SvnChangeList>(loaded.size + fresh.size)
        merged.addAll(loaded)
        merged.addAll(fresh)
        return merged
    }
}
