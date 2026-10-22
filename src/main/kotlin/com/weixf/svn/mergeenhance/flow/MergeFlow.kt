package com.weixf.svn.mergeenhance.flow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.SvnUtil
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.api.Url
import org.jetbrains.idea.svn.branchConfig.SelectBranchPopup
import org.jetbrains.idea.svn.branchConfig.SvnBranchConfigurationNew
import org.jetbrains.idea.svn.dialogs.WCInfo
import org.jetbrains.idea.svn.integrate.MergeContext
import java.awt.Component

/**
 * Enhance Merge 流程编排（第六轮起入口唯一化 = 常驻标签页）。
 *
 * ```
 * Enhance Merge 标签页（EnhanceMergePanel）
 *   └─ ① SelectBranchPopup（图2，复用原版弹窗）
 *        └─ ② 首屏加载修订（标签页内 RevisionSelectPanel；取日志走 LogPageLoader）  ← T4–T6
 *             └─ ③ MergeExecutor（复用 SvnIntegrateChangesTask）                    ← T7
 * ```
 *
 * 设计要点：
 * - 分支弹窗**直接复用**原版 `SelectBranchPopup`（public），因此 trunk / branches / Configure Branches…
 *   三项与分隔线由原版提供，无需自绘（v1 自绘的 `BranchSelectionDialog` 已在 T2 删除）。
 * - 分支配置管理器 `SvnBranchConfigurationManager` 是 internal，不能引用；
 *   但弹窗回调会把 `SvnBranchConfigurationNew`（public）交出来，足够完成 URL 换算。
 * - 换算规则与原版 `CopiesPanel.mergeFrom` 一致：
 *   `sourceUrl = branchUrl.appendPath(configuration.getRelativeUrl(wcInfo.url))`。
 * - 取日志复用原版 `MergeCalculatorTask.loadChangeLists`（public static，实时直连仓库、非缓存）——
 *   由标签页内的 `LogPageLoader` 调用。
 *
 * ## 入口沿革（D1 ②，2026-10-22 已实施）
 *
 * 早期唯一入口是「Subversion Working Copies Information」面板里反射注入的链接（`WcInfoLinkInjector`），
 * 走 `MergeFlow.start → MergeVariantDialog → RevisionSelectDialog` 的模态流。第六轮新增官方
 * `changesViewContentEP` 标签页后，为满足「不反射注入其他插件」的上架规范，已按 D1 ②
 * **删除注入入口及其整条模态链路**（`WcInfoLinkInjector` / `EnhanceMergeAction` /
 * `MergeVariantDialog` / `RevisionSelectDialog` / `MergeFlow.start` / `MergeFlow.openRevisionSelect`），
 * 现**唯一入口 = 标签页**，本类只保留 [selectBranch] 供标签页调用。
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
     * @param wcInfo   目标工作副本（来自标签页根选择器所选的那一个）
     * @param anchor   弹窗锚点组件（标签页里的「Select Branch…」按钮）
     * @param onContext 分支选定且 URL 换算成功后回调；标签页在回调里加载首屏并建 `RevisionSelectPanel`
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
