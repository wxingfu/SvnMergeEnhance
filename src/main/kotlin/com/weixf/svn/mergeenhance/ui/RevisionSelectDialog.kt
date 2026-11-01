package com.weixf.svn.mergeenhance.ui

import com.intellij.openapi.ui.DialogWrapper
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.log.LogPageLoader
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.MergeContext
import javax.swing.JComponent

/**
 * 修订选择的**模态对话框**（第七轮恢复）：`DialogWrapper` 的薄包装，对齐原版 Merge From 的体验。
 *
 * 全部「修订选择」逻辑与视图在 [RevisionSelectPanel]，本对话框只保留 DialogWrapper 专属职责：
 * 标题、可缩放、OK 按钮文案、尺寸记忆 key，并把 OK 按钮可用态与面板的勾选状态联动。
 *
 * 入口：标签页工作副本行的「Merge From…」→ `MergeFlow.selectBranch` → `MergeFlow.openRevisionSelect`
 * → 本对话框 → `MergeExecutor`。
 */
class RevisionSelectDialog(
    private val context: MergeContext,
    private val loader: LogPageLoader,
    initialItems: List<SvnChangeList>,
) : DialogWrapper(context.project, true) {

    /**
     * 复用面板：承载全部修订选择逻辑。
     * 挂到 DialogWrapper 自己的 [disposable]（对话框关闭时面板资源一并释放）。
     * `onSelectionChanged` 回传「是否有勾选」，驱动 OK（Merge Selected）按钮可用态。
     */
    private val panel = RevisionSelectPanel(
        context,
        loader,
        initialItems,
        disposable,
        onSelectionChanged = { hasSelection -> isOKActionEnabled = hasSelection },
    )

    init {
        // 标题用 MergeContext 自带的 mergeTitle（形如 "Merge from <branch>"，与原版一致）
        title = context.mergeTitle
        // 允许拉伸：DialogWrapper 默认不可调整大小，而本对话框要同时容纳长提交说明、深路径与右栏变更树
        setResizable(true)
        setOKButtonText(EnhanceMergeBundle.message("button.merge.selected"))
        init()
        // ⚠️ 面板是在 `init()` **之前**构造的，它自己的 `updateStatus()` 那时尚未跑过，
        // 于是 OK（Merge Selected）按钮保持平台默认的「可用」——空选时点它毫无反应。
        // 这里在 `init()` 之后补一次状态同步：没有勾选就禁用，有勾选才可用。
        panel.syncSelectionState()
    }

    override fun createCenterPanel(): JComponent = panel.component

    /** 已勾选的修订（委托复用面板；由 `MergeFlow.openRevisionSelect` 在对话框关闭后读取）。 */
    fun selectedChangeLists(): List<SvnChangeList> = panel.selectedChangeLists()

    /**
     * 尺寸记忆的 key。
     *
     * 官方在 2023.2+ 提供了 `getDimensionServiceKey(project, useForRestore)` 重载（可按项目隔离），
     * 但本插件 `since-build=221`（IDEA 2022.1），2022.1 的 `DialogWrapper` 只有无参版本——
     * 改用新重载会导致 2022.1 上编译/运行失败，故此处沿用全局 key（多项目共用同一尺寸记忆）。
     */
    override fun getDimensionServiceKey(): String = "com.weixf.svn.mergeenhance.RevisionSelectDialog"
}
