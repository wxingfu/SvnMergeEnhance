package com.weixf.svn.mergeenhance.merge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.ChangeListsMergerFactory
import org.jetbrains.idea.svn.integrate.LocalChangesPromptTask
import org.jetbrains.idea.svn.integrate.MergeContext
import org.jetbrains.idea.svn.integrate.QuickMerge
import org.jetbrains.idea.svn.integrate.QuickMergeInteractionImpl
import org.jetbrains.idea.svn.integrate.SvnIntegrateChangesTask
import org.jetbrains.idea.svn.integrate.WorkingCopyInfo

/**
 * 真正执行合并，与原版 `QuickMerge` 流程保持一致：
 *
 * - [mergeWithLocalChangesPrompt] 复刻原版「合并前本地改动提示」——直接复用原版
 *   [LocalChangesPromptTask]（以临时实例化的 [QuickMerge] + [QuickMergeInteractionImpl] 作为宿主，
 *   仅用于提供 `MergeContext` / `Interaction` / `runInBackground`，**不调用** QuickMerge 的合并流程方法）；
 *   LocalChangesPromptTask 会检查工作副本本地改动与待合并版本路径的交集，弹出原版对话框让用户选择
 *   继续 / 搁置 / 查看 / 取消，与原版完全一致。
 * - 用户确认后（或本地无交集时）回调 [merge]，直接 `queue` 原版 [SvnIntegrateChangesTask]
 *   （构造参数对齐原版 `newIntegrateTask`）。进度条、冲突解决、结果展示全部由原版负责，不额外弹窗。
 */
object MergeExecutor {

    private val LOG = Logger.getInstance(MergeExecutor::class.java)

    /**
     * 入口：先走原版「合并前本地改动提示」，确认后再执行合并。须在 EDT 调用
     * （LocalChangesPromptTask 内部会弹模态对话框）。
     *
     * 包一层 `invokeLater` 的原因：调用方（`MergeFlow` 的 DialogWrapper 关闭回调）虽在 EDT，
     * 但这里不能假设；且**模态对话框不应在当前调用栈内弹出**——先让 `showAndGet()` 的关闭流程
     * 走完再弹，避免"旧对话框还没拆完就弹新模态"导致的焦点/窗口层级异常。
     */
    fun mergeWithLocalChangesPrompt(context: MergeContext, selected: List<SvnChangeList>) {
        if (selected.isEmpty()) return

        // 仅作为 LocalChangesPromptTask 的宿主，不触发 QuickMerge 的合并流程。
        // 为什么不直接调 QuickMerge.merge/execute：那会连同它内部的 MergeTask 信号量包装、
        // selectMergeVariant（弹它自己的合并方式对话框）等整套流程一起启动，与本插件自己的
        // 修订选择对话框重复。LocalChangesPromptTask 只从宿主取 MergeContext/Interaction，
        // 是复刻前置步骤的最小代价。
        val quickMerge = QuickMerge(context, QuickMergeInteractionImpl(context))
        val callback = Runnable { merge(context, selected) }

        ApplicationManager.getApplication().invokeLater(
            {
                try {
                    LocalChangesPromptTask(quickMerge, selected, callback).run()
                } catch (e: Exception) {
                    // 兜底：前置提示失败不应让整个流程无声终止（原版此处的异常同样只进日志）。
                    // 用 warn 而非 error：这是可恢复的前置步骤失败，不该按"插件内部错误"上报。
                    LOG.warn("Failed to run local changes prompt before merge", e)
                }
            },
            // 官方要求：invokeLater 必须显式给 ModalityState（默认状态在模态框关闭动画期间
            // 可能尚未恢复），并给"过期条件"——项目已关闭时直接丢弃，别再弹模态框。
            ModalityState.defaultModalityState(),
            context.project.disposed,
        )
    }

    /**
     * 实际执行合并：对齐原版 `QuickMerge.newIntegrateTask`。
     * 合并前 [FileDocumentManager.saveAllDocuments]，再直接 queue 原版 [SvnIntegrateChangesTask]。
     *
     * `saveAllDocuments()` 的原因：编辑器里有未落盘改动时，svn 看到的仍是旧内容，
     * 合并会产生"看似无冲突却覆盖了用户刚写的代码"或反向丢失。原版 `QuickMerge.execute`
     * 第一步就是它。
     */
    fun merge(context: MergeContext, selected: List<SvnChangeList>) {
        if (selected.isEmpty()) return

        FileDocumentManager.getInstance().saveAllDocuments()

        // 参数含义（与 SvnIntegrateChangesTask 构造器对齐）：
        // - vcs：当前项目的 SvnVcs，决定用哪个 SVN 后端（命令行/SVNKit）与凭据
        // - WorkingCopyInfo(path, true)：目标工作副本根；第二个参数是 isIsWcRoot 的"强制为 true"语义，
        //   原版 newIntegrateTask 同样传 true（合并总是以 WC 根为单位）
        // - mergerFactory：把选中修订合并成合并区间的工厂；四个布尔依次为
        //   recordOnly=false（真正改动文件而非只记 mergeinfo）、
        //   force=false、allowLocalModifications=true（允许本地有修改时继续，由前面的
        //   LocalChangesPromptTask 负责征求用户同意，这里不再二次拦截）、reuseRecordOnly=false
        // - sourceUrl：合并来源分支 URL（MergeContext 里由分支弹窗换算得来）
        // - title：进度条标题；dryRun=false 正式执行；branchName：仅用于日志/展示
        val mergerFactory = ChangeListsMergerFactory(selected, false, false, true)
        SvnIntegrateChangesTask(
            context.vcs,
            WorkingCopyInfo(context.wcInfo.path, true),
            mergerFactory,
            context.sourceUrl,
            context.mergeTitle,
            false,
            context.branchName,
        ).queue()
    }
}
