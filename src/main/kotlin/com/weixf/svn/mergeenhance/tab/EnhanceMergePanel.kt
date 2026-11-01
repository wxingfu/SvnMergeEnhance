package com.weixf.svn.mergeenhance.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.flow.MergeFlow
import com.weixf.svn.mergeenhance.ui.MergeVariantDialog
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.actions.CleanupWorker
import org.jetbrains.idea.svn.dialogs.WCInfo
import javax.swing.JComponent
import javax.swing.JPanel
import java.util.concurrent.Future

/**
 * 「Enhance Merge」标签页主体（第七轮：复刻 WC Info 面板）。
 *
 * ## 形态（与原版 `CopiesPanel` 同构）
 *
 * ```
 * SimpleToolWindowPanel
 *  ├─ 左侧竖排工具条：⟳ 刷新（与原版一致：刷新按钮在左侧窄栏，不在顶部）
 *  └─ 内容：滚动区 + 纵向排列的工作副本信息块（每个块 = WcInfoPanel，块间距 8px）
 * ```
 *
 * 每个信息块复刻原版 `WorkingCopyInfoPanel`（path / URL / Format / Depth / Working copy root /
 * 「Merge From…」链接），点「Merge From…」后的流程与原版 Merge From 一致：
 * ```
 * Merge From… → SelectBranchPopup（分支选择，弹在信息块中心）
 *              → MergeVariantDialog（Select Merge Variant：Revision Select / Cancel）
 *              → RevisionSelectDialog（模态修订选择）
 *              → MergeExecutor（合并）
 * ```
 * 全程走 Enhance Merge（逐版本勾选 + 搜索过滤 + 合并状态），**不走**原版 `QuickMerge` 流程。
 *
 * ## 与原版 WC Info 面板的关系
 *
 * 并存，不替换：本标签页提供「工作副本信息块 + Cleanup + Merge From…（走 Enhance Merge）」；
 * `Configure Branches`（依赖 internal 的 `SvnBranchConfigurationManager`）与 `Properties`
 * 仍由原版 WC Info 面板承担（决策项 D2，不复刻）。
 *
 * ## 生命周期
 *
 * 面板实现 [Disposable]，由 `EnhanceMergeTabContent.disposeContent` 释放；无子 Disposable，
 * 后台枚举任务的回调以 [isDisposed] / `project.isDisposed` 守卫。
 */
class EnhanceMergePanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {

    private val LOG = Logger.getInstance(EnhanceMergePanel::class.java)

    /** 面板是否已释放。用 `@Volatile` 标志（`Disposer.isDisposed` 已废弃，见 `RevisionSelectPanel` 说明）。 */
    @Volatile
    private var disposed = false

    private val isDisposed: Boolean get() = disposed

    /**
     * 在飞的工作副本枚举任务（`getAllWcInfos()` 可能触发 svn 探测）。
     * 面板关闭/项目关闭时需中断它，否则标签已销毁仍在跑无用的仓库探测。
     */
    @Volatile
    private var refreshTask: Future<*>? = null

    /**
     * 刷新世代号（2026-11-01 修复 P1-7）。
     *
     * 连点「刷新」会并发跑多次 svn 枚举，**先发起的旧任务可能后完成** → 旧快照覆盖新快照
     * （列表回退到旧的工作副本集合）。与 `LogPageLoader` 的世代号同构：
     * 回调里比对世代号，不是最新一次的结果直接丢弃。
     */
    @Volatile
    private var refreshGeneration = 0L

    /** 工作副本信息块的纵向容器（对齐原版 `VerticalLayout(8)` 间距 + `empty(2, 4)` 边框）。 */
    private val listContainer = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(2, 4)
    }

    /** 左侧竖排工具条上的刷新动作（对齐原版 `Svn.RefreshWorkingCopies`）。 */
    private val refreshAction = object : DumbAwareAction(
        EnhanceMergeBundle.message("tab.refresh.roots"),
        EnhanceMergeBundle.message("tab.refresh.roots"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = refresh()
    }

    init {
        setContent(ScrollPaneFactory.createScrollPane(listContainer))
        // 竖排（horizontal = false）——与原版 `createActionToolbar(TOOLBAR_PLACE, toolbarGroup, false)` 一致
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_PLACE, DefaultActionGroup(refreshAction), false)
        // 必须显式指定目标组件：否则平台会用「当前焦点组件」更新动作状态，刷新按钮可能被误禁用
        // （平台会打 WARN 提示，且该 WARN 因含插件名会被回归脚本判为 issue）
        toolbar.setTargetComponent(listContainer)
        setToolbar(toolbar.component)
        refresh()
    }

    override fun dispose() {
        // 中断在飞的枚举任务：`allWcInfos` 会触发 svn 探测，面板已销毁时不该继续跑
        refreshTask?.cancel(true)
        refreshTask = null
        // 无子 Disposable；只置位标志，后台回调据此作废
        if (disposed) return
        disposed = true
    }

    // ------------------------------------------------------------ 枚举与渲染

    /** 后台枚举工作副本并回 EDT 填充（`allWcInfos` 可能触发 svn 探测，不得在 EDT 同步执行）。 */
    private fun refresh() {
        if (project.isDisposed) return
        // 取消上一次未完成的枚举：避免并发探测同一批工作副本（也避免旧任务覆盖新结果）
        refreshTask?.cancel(true)
        val generation = ++refreshGeneration
        refreshTask = ApplicationManager.getApplication().executeOnPooledThread {
            val infos = try {
                SvnVcs.getInstance(project)?.allWcInfos ?: emptyList()
            } catch (e: Exception) {
                LOG.warn("Failed to enumerate working copies", e)
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    // 世代号比对：只有**最新一次**刷新的结果才生效（否则旧快照会覆盖新快照）
                    if (generation != refreshGeneration) return@invokeLater
                    if (isDisposed || project.isDisposed) return@invokeLater
                    applyWcInfos(infos)
                },
                ModalityState.any(),
                project.disposed,
            )
        }
    }

    private fun applyWcInfos(infos: List<WCInfo>) {
        listContainer.removeAll()
        if (infos.isEmpty()) {
            listContainer.add(
                JBLabel(EnhanceMergeBundle.message("wc.no.working.copies")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
            )
        } else {
            infos.forEach { listContainer.add(buildBlock(it)) }
        }
        listContainer.revalidate()
        listContainer.repaint()
    }

    /**
     * 一个工作副本的信息块。
     *
     * 该块同时是**分支弹窗的锚点**（对齐原版：原版把整个 `infoPanel` 传给 `SelectBranchPopup`，
     * 其内部 `showInCenterOf(myComponent)` 使弹窗显示在信息块中心）。
     */
    private fun buildBlock(wcInfo: WCInfo): JComponent {
        val panel = WcInfoPanel()
        panel.setWcInfo(wcInfo)
        panel.onCleanup = { cleanupWorkingCopy(wcInfo) }
        panel.onMergeFrom = {
            MergeFlow.selectBranch(project, wcInfo, panel) { context ->
                when (MergeVariantDialog(project).showDialog()) {
                    MergeVariantDialog.Variant.REVISION_SELECT -> MergeFlow.openRevisionSelect(context)
                    MergeVariantDialog.Variant.CANCEL -> Unit
                }
            }
        }
        return panel
    }

    /**
     * 清理工作副本：复用**原版公开的** `CleanupWorker`（`CleanupWorker(SvnVcs, List<VirtualFile>)` +
     * `execute()`），不自实现 svn 清理逻辑——与原版 `CopiesPanel.cleanup` 完全同一条路径。
     *
     * 根无效（目录被删除/移动）时给出提示并中止，与原版 `checkRoot` 的守卫等价。
     */
    private fun cleanupWorkingCopy(wcInfo: WCInfo) {
        val vcs = SvnVcs.getInstance(project) ?: return
        val root = wcInfo.rootInfo.virtualFile
        if (!root.isValid) {
            Messages.showWarningDialog(
                project,
                EnhanceMergeBundle.message("error.invalid.working.copy.root", wcInfo.path),
                EnhanceMergeBundle.message("wc.cleanup"),
            )
            return
        }
        CleanupWorker(vcs, listOf(root)).execute()
    }

    private companion object {
        /** 工具条 place（同一容器内多个工具条用 place 区分）。 */
        const val TOOLBAR_PLACE = "SvnEnhanceMergeWorkingCopies"
    }
}
