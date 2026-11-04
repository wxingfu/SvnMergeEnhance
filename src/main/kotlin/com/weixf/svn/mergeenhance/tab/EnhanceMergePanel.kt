package com.weixf.svn.mergeenhance.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Consumer
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.flow.MergeFlow
import com.weixf.svn.mergeenhance.ui.MergeVariantDialog
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.actions.CleanupWorker
import org.jetbrains.idea.svn.branchConfig.BranchConfigurationDialog
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
 * 并存，不替换：本标签页提供「工作副本信息块 + Cleanup + Configure Branches + Merge From…（走 Enhance Merge）」；
 * `Properties` 等其余原版面板专有项仍由原版 WC Info 面板承担。
 * （2026-11-01 起「Configure Branches」也已复刻：原版用的是**公开**的
 * `BranchConfigurationDialog.configureBranches(project, root)`，并无「只能依赖 internal 类」的限制。）
 *
 * ## 生命周期
 *
 * 面板实现 [Disposable]，由 `EnhanceMergeTabContent.disposeContent` 释放；
 * 消息总线连接以面板自身为父 Disposable（`connect(this)`），面板 dispose 时自动断开，不泄漏；
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
        override fun actionPerformed(e: AnActionEvent) = refreshRoots()
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

        // 订阅「工作副本根已重载」事件（对齐原版 `CopiesPanel` 构造时的同名订阅）。
        //
        // 背景（2026-11-04 用户反馈「切换分支后本面板 URL 不刷新」）：
        // svn4idea 把「工作副本根 → URL」的映射缓存在 `SvnFileUrlMappingImpl` 里，
        // 只有在**重扫**根之后才会更新；重扫由 `SvnVcs.invokeRefreshSvnRoots()` 触发，
        // 扫完通过 `SvnVcs.ROOTS_RELOADED` 广播（`notifyRootsReloaded`）。
        // 分支切换（`svn switch` / 更新后根 URL 变化）正是走
        // `NestedCopiesBuilder.bewareRoot → invokeRefreshSvnRoots → ROOTS_RELOADED` 这条链。
        // 原版 `CopiesPanel` 订阅了该事件 → URL 立即刷新；
        // 本插件此前**只在构造时 + 点刷新按钮**读 `getAllWcInfos()`（读缓存映射），
        // 既不订阅事件、刷新按钮也不触发重扫 → 分支切换后 URL 长期不更新（= 本次修复的缺陷）。
        //
        // `connect(this)`：连接挂在面板自身的 Disposable 下，面板 dispose 时自动断开，不泄漏。
        project.messageBus.connect(this).subscribe(ROOTS_RELOADED, Consumer<Boolean> { rootsChanged ->
            // 与原版 `CopiesPanel.rootsReloaded` 一致：只有映射真的变了才重绘（false 只是收尾信号）
            if (rootsChanged) refresh()
        })

        // 首次渲染：与原版 `CopiesPanel` 构造器同构（`rootsReloaded(true); refresh();`）——
        // 先按当前映射渲染一次，再触发一次根重扫；重扫完成后由 ROOTS_RELOADED 事件再渲染一次。
        // （否则面板在「映射尚未刷新」时创建，会一直显示旧 URL，直到下一次外部事件）
        refreshRoots()
    }

    override fun dispose() {
        // 中断在飞的枚举任务：`allWcInfos` 会读根信息，面板已销毁时不该继续跑
        refreshTask?.cancel(true)
        refreshTask = null
        // 消息总线连接由 Disposer 作为子节点自动断开（connect(this)），此处只需置位标志，
        // 让后台回调据此作废
        if (disposed) return
        disposed = true
    }

    // ------------------------------------------------------------ 枚举与渲染

    /**
     * 「刷新」按钮（对齐原版 `CopiesPanel.refresh`）。
     *
     * 原版点刷新调的是 `SvnVcs.invokeRefreshSvnRoots()`——**重扫**工作副本根
     * （重新探测每个根的真实 URL），扫完由 `ROOTS_RELOADED` 事件驱动面板重绘。
     *
     * ⚠️ 本插件此前直接读 `getAllWcInfos()`（**只读缓存映射**，不重扫），
     * 于是「外部 `svn switch` / 分支切换」后即便连点刷新也刷不出新 URL——
     * 因为缓存映射要等 svn4idea 自己重扫才会更新。现改为与原版同构：
     * ① 先按当前映射快速重绘一次（用户立刻看到反馈）；
     * ② 再触发真正的根重扫，重扫完成后由 `ROOTS_RELOADED` 事件再重绘一次（此时 URL 已是新的）。
     *
     * ⚠️ 绝不能把 `invokeRefreshSvnRoots()` 放进 [refresh]：那会形成
     * 「重绘 → 重扫 → 事件 → 重绘 → 重扫…」的无限循环（事件回调也会触发重扫）。
     */
    private fun refreshRoots() {
        // 快速路径：先按当前映射重绘
        refresh()
        // 慢路径：真正重扫根（重扫完成会广播 ROOTS_RELOADED → 再次重绘）
        SvnVcs.getInstance(project)?.invokeRefreshSvnRoots()
    }

    /** 后台枚举工作副本并回 EDT 填充（`allWcInfos` 会读根信息，不得在 EDT 同步执行）。 */
    private fun refresh() {
        if (isDisposed || project.isDisposed) return
        // 取消上一次未完成的枚举：避免并发探测同一批工作副本（也避免旧任务覆盖新结果）
        refreshTask?.cancel(true)
        val generation = ++refreshGeneration
        refreshTask = ApplicationManager.getApplication().executeOnPooledThread {
            val infos = try {
                SvnVcs.getInstance(project)?.allWcInfos ?: emptyList()
            } catch (e: ProcessCanceledException) {
                // 取消（被更新的一次刷新取代 / 面板已销毁）→ 不再渲染，也不用记 WARN
                throw e
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
                // ⚠️ 必须用 write-safe 的模态态：`ModalityState.any()` 从未登记进 TransactionGuard 的
                // write-safe 模态表 → 平台会打 "Write-unsafe context!" 错误（2026-11-03 同类问题）。
                // `NON_MODAL` 恒 write-safe，且会在模态对话框打开期间推迟执行，语义更安全。
                ModalityState.NON_MODAL,
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
            // 按路径排序（对齐原版 `CopiesPanel.setWorkingCopies` 的 `infoList.sort(comparing(WCInfo::getPath))`）
            // —— 否则两个面板的工作副本**顺序**可能不一致，看起来像「没同步」
            infos.sortedBy { it.path }.forEach { listContainer.add(buildBlock(it)) }
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
        panel.onConfigureBranches = { configureBranches(wcInfo) }
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
     * 配置分支：复用**原版公开的** `BranchConfigurationDialog.configureBranches(project, root)`
     * （2022.1 与最新版 svn4idea 均为 public static），弹出的就是内置插件自己的分支配置对话框。
     *
     * 根无效（目录被删除/移动）时给出提示并中止，与原版 `checkRoot` 的守卫等价。
     */
    private fun configureBranches(wcInfo: WCInfo) {
        val root = wcInfo.rootInfo.virtualFile
        if (!root.isValid) {
            Messages.showWarningDialog(
                project,
                EnhanceMergeBundle.message("error.invalid.working.copy.root", wcInfo.path),
                EnhanceMergeBundle.message("wc.configure.branches"),
            )
            return
        }
        BranchConfigurationDialog.configureBranches(project, root)
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

        /**
         * `SvnVcs.ROOTS_RELOADED` 的**类型化视图**。
         *
         * svn4idea 声明为 `public static final Topic<Consumer>`（raw `Consumer`），
         * Kotlin 侧只能拿到 `Topic<Consumer<*>>`，直接 `subscribe` 无法把回调参数定型成 `Boolean`；
         * 这里做一次受控的 unchecked 转换（擦除后类型一致，跨 2022.1~2026.x 均成立：
         * 2022.1 的 `com.intellij.util.Consumer` 只有抽象方法 `consume`，
         * 2026 的多继承 `java.util.function.Consumer` 但 `accept` 是 default，抽象方法仍只有 `consume`）。
         */
        @Suppress("UNCHECKED_CAST")
        private val ROOTS_RELOADED: Topic<Consumer<Boolean>> =
            SvnVcs.ROOTS_RELOADED as Topic<Consumer<Boolean>>
    }
}
