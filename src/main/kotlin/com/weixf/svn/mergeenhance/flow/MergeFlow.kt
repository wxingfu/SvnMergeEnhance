package com.weixf.svn.mergeenhance.flow

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.log.LogPageLoader
import com.weixf.svn.mergeenhance.merge.MergeExecutor
import com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings
import com.weixf.svn.mergeenhance.ui.RevisionSelectDialog
import org.jetbrains.idea.svn.SvnUtil
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.api.Url
import org.jetbrains.idea.svn.branchConfig.SelectBranchPopup
import org.jetbrains.idea.svn.branchConfig.SvnBranchConfigurationNew
import org.jetbrains.idea.svn.dialogs.WCInfo
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.MergeContext
import java.awt.Component

/**
 * Enhance Merge 流程编排（第六轮起入口唯一化 = 常驻标签页；第七轮改回模态修订选择）。
 *
 * ```
 * Enhance Merge 标签页（EnhanceMergePanel：工作副本列表 + 每行 Merge From…）
 *   └─ ① SelectBranchPopup（复用原版弹窗）
 *        └─ ② RevisionSelectDialog（模态，复用 RevisionSelectPanel；取日志走 LogPageLoader）  ← 本插件核心
 *             └─ ③ MergeExecutor（复用 SvnIntegrateChangesTask）                               ← T7
 * ```
 *
 * 设计要点：
 * - 分支弹窗**直接复用**原版 `SelectBranchPopup`（public），因此 trunk / branches / Configure Branches…
 *   三项与分隔线由原版提供，无需自绘。
 * - 分支配置管理器 `SvnBranchConfigurationManager` 是 internal，不能引用；
 *   但弹窗回调会把 `SvnBranchConfigurationNew`（public）交出来，足够完成 URL 换算。
 * - 换算规则与原版 `CopiesPanel.mergeFrom` 一致：
 *   `sourceUrl = branchUrl.appendPath(configuration.getRelativeUrl(wcInfo.url))`。
 * - 取日志复用原版 `MergeCalculatorTask.loadChangeLists`（public static，实时直连仓库、非缓存）——
 *   由 `LogPageLoader` 调用。
 *
 * ## 入口沿革（D1 ②，2026-10-22 已实施）
 *
 * 早期唯一入口是「Subversion Working Copies Information」面板里反射注入的链接（`WcInfoLinkInjector`），
 * 走 `MergeFlow.start → MergeVariantDialog → RevisionSelectDialog` 的模态流。第六轮新增官方
 * `changesViewContentEP` 标签页，为满足「不反射注入其他插件」的上架规范，已按 D1 ② 删除注入入口及
 * `MergeVariantDialog`/`MergeFlow.start`；第七轮按用户拍板**恢复模态 `RevisionSelectDialog` + [openRevisionSelect]**
 * （作为标签页「Merge From…」的后续步骤，非注入入口，仍合规）。
 */
object MergeFlow {

    /** 弹窗标题（T9.4：不再硬编码，走本地化 bundle）。 */
    private val TITLE: String
        get() = EnhanceMergeBundle.message("title.enhance.merge")

    /**
     * 分支选择入口：校验工作副本根 → 弹出分支选择（[SelectBranchPopup]）→ URL 换算 → 守卫 →
     * 回调 [onContext]（得到换算好的 [MergeContext]）。
     *
     * @param project  当前项目
     * @param wcInfo   目标工作副本（来自标签页工作副本列表所点击的那一个）
     * @param anchor   弹窗锚点组件（标签页里该工作副本行的「Merge From…」链接）
     * @param onContext 分支选定且 URL 换算成功后回调；标签页在回调里走 [openRevisionSelect]（模态修订选择）
     *
     * `root.isValid` 校验的原因：后续 `SvnBranchConfigurationNew.getRelativeUrl()` 与
     * `MergeContext` 都要用 VirtualFile；无效根（目录被删除/移动）时原版分支弹窗会自己抛异常，
     * 这里提前拦下并给出可读提示，比让用户看到堆栈友好。
     * `SvnVcs.getInstance(project) ?: return` 同理——非 SVN 项目直接静默退出
     * （标签页本身只在 SVN 项目显示，这是双保险）。
     */
    fun selectBranch(project: Project, wcInfo: WCInfo, anchor: Component?, onContext: (MergeContext) -> Unit) {
        val vcs = SvnVcs.getInstance(project) ?: return
        val root = wcInfo.rootInfo.virtualFile

        if (!root.isValid) {
            Messages.showWarningDialog(
                project,
                EnhanceMergeBundle.message("error.invalid.working.copy.root", wcInfo.path),
                TITLE,
            )
            return
        }

        val callback = BranchSelectedHandler(project, vcs, wcInfo, root, onContext)
        SelectBranchPopup.showForBranchRoot(
            project,
            root,
            callback,
            EnhanceMergeBundle.message("popup.title.select.branch"),
            anchor,
        )
    }

    /**
     * 后台加载首屏修订日志，完成后在 EDT 弹出**模态修订选择对话框**（对齐原版 Merge From 体验）。
     * 加载失败给出错误说明；对话框取消/关闭无副作用。
     */
    fun openRevisionSelect(context: MergeContext) {
        val loader = LogPageLoader(context)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project,
            EnhanceMergeBundle.message("progress.loading.revisions"),
            true,
        ) {
            private var items: List<SvnChangeList> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // 首屏：加载器刚创建、世代号即 0，游标从 HEAD 开始
                items = loader.loadNextPageRaw(EnhanceMergeSettings.getInstance().pageSize, loader.snapshot())
            }

            override fun onSuccess() {
                // 后台加载期间项目可能已被关闭：此时既不该弹提示，也不该再建对话框
                if (context.project.isDisposed) return
                if (items.isEmpty()) {
                    Messages.showInfoMessage(
                        context.project,
                        EnhanceMergeBundle.message("message.no.revisions"),
                        TITLE,
                    )
                    return
                }

                val dialog = RevisionSelectDialog(context, loader, items)
                if (!dialog.showAndGet()) return

                val selected = dialog.selectedChangeLists()
                if (selected.isEmpty()) return

                // 先走原版「合并前本地改动提示」，确认后再执行合并（与原版 QuickMerge 流程一致）
                MergeExecutor.mergeWithLocalChangesPrompt(context, selected)
            }

            override fun onThrowable(error: Throwable) {
                // 项目已关闭时不再对已释放的 project 弹模态错误框
                if (context.project.isDisposed) return
                Messages.showErrorDialog(
                    context.project,
                    EnhanceMergeBundle.message(
                        "error.load.revisions",
                        error.message ?: error.javaClass.simpleName,
                    ),
                    TITLE,
                )
            }
        })
    }

    private class BranchSelectedHandler(
        private val project: Project,
        private val vcs: SvnVcs,
        private val wcInfo: WCInfo,
        private val root: VirtualFile,
        private val onContext: (MergeContext) -> Unit,
    ) : SelectBranchPopup.BranchSelectedCallback {

        override fun branchSelected(
            project: Project,
            configuration: SvnBranchConfigurationNew,
            url: Url,
            revision: Long,
        ) {
            // URL 换算：把「WC 相对于其分支根的路径」拼到「所选分支 URL」后面。
            // 例：WC = .../branches/feature-x/modules/core，所选分支 = .../branches/release-1.0
            //     → relative = "modules/core"，sourceUrl = .../branches/release-1.0/modules/core
            // 与原版 CopiesPanel.mergeFrom 的换算规则一致；relative 为 null 表示该 WC 不在任何
            // 已配置分支之下（未 Configure Branches），此时无法定位对应路径，只能报错退出。
            val relative = configuration.getRelativeUrl(wcInfo.url)
            if (relative == null) {
                Messages.showErrorDialog(
                    project,
                    EnhanceMergeBundle.message("error.cannot.resolve.branch.relative.url", url.toDecodedString()),
                    TITLE,
                )
                return
            }

            // appendPath(relative, false)：false = 不对 relative 做 URL 编码
            // （relative 本身已是仓库路径片段，二次编码会把空格/中文变成 %XX 而匹配不上）
            val sourceUrl = url.appendPath(relative, false)
            // 自身层级守卫：源与目标是同一条分支（互为祖先）时合并没有意义，
            // SVN 还会因"merge from self"直接报错，提前拦下更友好。
            if (isSameHierarchy(sourceUrl, wcInfo.url)) {
                Messages.showErrorDialog(
                    project,
                    EnhanceMergeBundle.message("error.merge.from.self", url.toDecodedString()),
                    TITLE,
                )
                return
            }

            val context = MergeContext(vcs, sourceUrl, wcInfo, url.tail, root)
            onContext(context)
        }

        /** 与原版 `QuickMerge.areInSameHierarchy` 同义：两个 URL 互为祖先时不允许合并。 */
        private fun isSameHierarchy(one: Url, two: Url): Boolean =
            SvnUtil.isAncestor(one, two) || SvnUtil.isAncestor(two, one)
    }
}
