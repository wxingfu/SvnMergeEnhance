package com.weixf.svn.mergeenhance.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.flow.MergeFlow
import com.weixf.svn.mergeenhance.log.LogPageLoader
import com.weixf.svn.mergeenhance.merge.MergeExecutor
import com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings
import com.weixf.svn.mergeenhance.ui.RevisionSelectPanel
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.dialogs.WCInfo
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.MergeContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 「Enhance Merge」标签页主体（第六轮新增）。
 *
 * 常驻合并工作台：`选工作副本根 → Select Branch（复用 `SelectBranchPopup`）→ 在标签页内做修订选择
 * （复用 [RevisionSelectPanel]）→ Merge Selected（复用 [MergeExecutor]）`。
 *
 * ## 状态机
 *
 * ```
 * Idle ──Select Branch(成功)──▶ LoadingRevisions ──首屏完成──▶ Loaded
 * Loaded ──Change Branch──▶ LoadingRevisions(新分支)
 * Loaded ──Merge Selected──▶ 触发 MergeExecutor ──▶ Idle（结果树由原版任务展示）
 * ```
 *
 * ## 入口唯一（D1 ②，2026-10-22）
 *
 * - 本标签页是本插件**唯一入口**（旧的注入链接入口已删除）；选分支后**直接**进入修订选择视图，
 *   不再经过任何「合并方式选择」对话框。
 * - 每个分支新建 `LogPageLoader` + `RevisionSelectPanel`（其内部再建 `OneShotMergeInfoHelper`），
 *   换分支时 dispose 旧面板，避免 mergeinfo 缓存跨分支串数据。
 *
 * ## 生命周期
 *
 * 面板实现 [Disposable]，由 `EnhanceMergeTabContent.disposeContent` 释放；子资源（子面板、Alarm 等）
 * 注册到 `this`，随标签关闭/项目关闭一并释放。
 */
class EnhanceMergePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val LOG = Logger.getInstance(EnhanceMergePanel::class.java)

    /** 面板是否已释放。用 `@Volatile` 标志（`Disposer.isDisposed` 已废弃，见 `RevisionSelectPanel` 说明）。 */
    @Volatile
    private var disposed = false

    private val isDisposed: Boolean get() = disposed

    /** 弹窗标题（复用主标题 key）。 */
    private val TITLE: String get() = EnhanceMergeBundle.message("title.enhance.merge")

    // ---- 状态 ----
    /** 当前选中的工作副本根。 */
    private var currentRoot: WCInfo? = null

    /** 当前分支对应的合并上下文（Loaded/Loading 态有效）。 */
    private var currentContext: MergeContext? = null

    /** 当前修订选择面板（Loaded 态有效）。 */
    private var revisionPanel: RevisionSelectPanel? = null

    /** 首屏加载进行中。 */
    private var loadingFirstPage = false

    /** 首屏加载令牌：换分支/重置时递增，作废在飞的旧加载回调。 */
    private var loadToken = 0

    // ---- UI ----
    private val rootCombo = ComboBox<WCInfo>()

    private val refreshRootsButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = EnhanceMergeBundle.message("tab.refresh.roots")
        // 官方无障碍要求：纯图标按钮必须给出可读名称
        accessibleContext.accessibleName = EnhanceMergeBundle.message("tab.refresh.roots")
        addActionListener { refreshRoots() }
    }

    private val selectBranchButton = JButton(EnhanceMergeBundle.message("tab.select.branch")).apply {
        addActionListener {
            val root = currentRoot ?: return@addActionListener
            MergeFlow.selectBranch(project, root, this) { context -> onBranchSelected(context) }
        }
    }

    private val changeBranchButton = JButton(EnhanceMergeBundle.message("tab.change.branch")).apply {
        addActionListener {
            val root = currentRoot ?: return@addActionListener
            MergeFlow.selectBranch(project, root, this) { context -> onBranchSelected(context) }
        }
    }

    private val branchLabel = JBLabel().apply { isVisible = false }

    private val mergeButton = JButton(EnhanceMergeBundle.message("button.merge.selected")).apply {
        isVisible = false
        addActionListener { onMergeSelected() }
    }

    private val emptyLabel = JBLabel(
        "<html>${EnhanceMergeBundle.message("tab.empty.hint")}</html>",
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(12, 8, 12, 8)
    }

    private val loadingLabel = JBLabel(EnhanceMergeBundle.message("progress.loading.revisions")).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(12, 8, 12, 8)
    }

    /** 中央容器：按状态在「空态 / 加载 / 修订面板」之间切换。 */
    private val centerPanel = JPanel(BorderLayout())

    init {
        // 头部：根标签 + 根选择器 + 刷新 + 选择/更换分支 + 分支名
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel(EnhanceMergeBundle.message("tab.select.root")))
            add(rootCombo)
            add(refreshRootsButton)
            add(selectBranchButton)
            add(branchLabel)
            add(changeBranchButton)
        }
        add(header, BorderLayout.NORTH)

        // 中央：状态切换容器
        add(centerPanel, BorderLayout.CENTER)

        // 南侧：右对齐的 Merge Selected
        val south = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { add(mergeButton) }
        add(south, BorderLayout.SOUTH)

        // 工作副本根选择器：展示路径；切换根时回到 Idle
        rootCombo.setRenderer(WcInfoRenderer())
        rootCombo.addActionListener {
            if (suppressRootEvents) return@addActionListener
            val selected = rootCombo.selectedItem as? WCInfo ?: return@addActionListener
            if (currentRoot?.path != selected.path) {
                currentRoot = selected
                resetToIdle()
            }
        }

        // 首次展示即枚举工作副本根
        refreshRoots()
    }

    override fun dispose() {
        // 取消在飞加载、释放子面板；不因 project.isDisposed 提前返回（沿用既有 dispose 时序教训）
        if (disposed) return
        disposed = true
        cancelLoading()
        // revisionPanel 注册在 `this` 上、由 Disposer 树随本面板一并释放；这里显式释放更直白
        revisionPanel?.let { Disposer.dispose(it) }
        revisionPanel = null
    }

    // ------------------------------------------------------------ 状态切换

    /** 回到 Idle：清掉当前分支上下文与修订面板（保留根选择器状态）。 */
    private fun resetToIdle() {
        cancelLoading()
        revisionPanel?.let { Disposer.dispose(it) }
        revisionPanel = null
        currentContext = null
        updateState()
    }

    /** 取消在飞的首屏加载（递增令牌作废旧回调）。 */
    private fun cancelLoading() {
        loadToken++
        loadingFirstPage = false
    }

    /** 按当前状态重排中央内容与各按钮可见/可用态。 */
    private fun updateState() {
        val hasRoot = currentRoot != null
        val context = currentContext
        val panel = revisionPanel

        selectBranchButton.isEnabled = hasRoot && !loadingFirstPage
        changeBranchButton.isVisible = context != null
        branchLabel.isVisible = context != null
        branchLabel.text = context?.mergeTitle ?: ""
        mergeButton.isVisible = context != null && panel != null

        centerPanel.removeAll()
        val content: Component = when {
            loadingFirstPage -> loadingLabel
            context != null && panel != null -> panel.component
            else -> emptyLabel
        }
        centerPanel.add(content, BorderLayout.CENTER)
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    // ------------------------------------------------------------ 根选择

    private fun refreshRoots() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // 枚举工作副本可能触发重探测（svn 命令），必须在后台线程执行
            val infos = try {
                SvnVcs.getInstance(project)?.allWcInfos ?: emptyList()
            } catch (e: Exception) {
                LOG.warn("Failed to enumerate working copies", e)
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (isDisposed || project.isDisposed) return@invokeLater
                    applyRoots(infos)
                },
                ModalityState.any(),
                project.disposed,
            )
        }
    }

    /** 根选择器事件抑制标志：`applyRoots` 编程改选时避免误触「切换根」逻辑。 */
    private var suppressRootEvents = false

    private fun applyRoots(infos: List<WCInfo>) {
        suppressRootEvents = true
        try {
            val previous = currentRoot
            rootCombo.removeAllItems()
            infos.forEach { rootCombo.addItem(it) }
            currentRoot = if (previous != null && infos.any { it.path == previous.path }) {
                infos.first { it.path == previous.path }
            } else {
                infos.firstOrNull()
            }
            if (currentRoot != null) rootCombo.selectedItem = currentRoot
        } finally {
            suppressRootEvents = false
        }
        // 根集合变化后，若当前上下文对应的根已不存在，退回 Idle
        if (currentContext != null && currentRoot == null) resetToIdle()
        updateState()
    }

    // ------------------------------------------------------------ 分支与加载

    private fun onBranchSelected(context: MergeContext) {
        if (isDisposed || project.isDisposed) return
        resetToIdle()
        currentContext = context
        loadingFirstPage = true
        loadToken++
        val token = loadToken
        updateState()
        loadFirstPage(context, token)
    }

    private fun loadFirstPage(context: MergeContext, token: Int) {
        val loader = LogPageLoader(context)
        val pageSize = EnhanceMergeSettings.getInstance().pageSize
        val generation = loader.snapshot()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            EnhanceMergeBundle.message("progress.loading.revisions"),
            true,
        ) {
            private var items: List<SvnChangeList> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                items = loader.loadNextPageRaw(pageSize, generation)
            }

            override fun onSuccess() {
                if (token != loadToken || isDisposed || project.isDisposed) return
                loadingFirstPage = false
                if (items.isEmpty()) {
                    currentContext = null
                    updateState()
                    Messages.showInfoMessage(
                        project,
                        EnhanceMergeBundle.message("message.no.revisions"),
                        TITLE,
                    )
                    return
                }
                val panel = RevisionSelectPanel(
                    context,
                    loader,
                    items,
                    this@EnhanceMergePanel,
                    onSelectionChanged = { hasSelection -> mergeButton.isEnabled = hasSelection },
                )
                revisionPanel = panel
                mergeButton.isEnabled = false
                updateState()
            }

            override fun onThrowable(error: Throwable) {
                if (token != loadToken || isDisposed || project.isDisposed) return
                // 被换分支重置取消：静默丢弃
                if (error is ProcessCanceledException) return
                loadingFirstPage = false
                currentContext = null
                updateState()
                Messages.showErrorDialog(
                    project,
                    EnhanceMergeBundle.message(
                        "error.load.revisions",
                        error.message ?: error.javaClass.simpleName,
                    ),
                    TITLE,
                )
            }
        })
    }

    // ------------------------------------------------------------ 合并

    private fun onMergeSelected() {
        val context = currentContext ?: return
        val panel = revisionPanel ?: return
        val selected = panel.selectedChangeLists()
        if (selected.isEmpty()) return
        // 复用原版「合并前本地改动提示」→ SvnIntegrateChangesTask 的完整链路；
        // 发起后回 Idle，结果树/冲突解决/VFS 刷新由原版任务展示。
        MergeExecutor.mergeWithLocalChangesPrompt(context, selected)
        resetToIdle()
    }

    // ------------------------------------------------------------ 渲染

    /** 工作副本根选择器的行渲染：显示工作副本本地路径。 */
    private class WcInfoRenderer : ListCellRenderer<WCInfo> {
        private val renderer = JBLabel()

        override fun getListCellRendererComponent(
            list: JList<out WCInfo>?,
            value: WCInfo?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            renderer.text = value?.path ?: ""
            if (isSelected) {
                renderer.background = UIUtil.getListSelectionBackground(true)
                renderer.foreground = UIUtil.getListSelectionForeground(true)
            } else {
                renderer.background = UIUtil.getListBackground()
                renderer.foreground = UIUtil.getListForeground()
            }
            renderer.isOpaque = true
            return renderer
        }
    }
}
