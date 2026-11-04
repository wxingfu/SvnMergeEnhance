package com.weixf.svn.mergeenhance.merge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.history.SvnChangeList
import org.jetbrains.idea.svn.integrate.ChangeListsMergerFactory
import org.jetbrains.idea.svn.integrate.LocalChangesPromptTask
import org.jetbrains.idea.svn.integrate.MergeContext
import org.jetbrains.idea.svn.integrate.MergeTask
import org.jetbrains.idea.svn.integrate.QuickMerge
import org.jetbrains.idea.svn.integrate.QuickMergeInteractionImpl
import org.jetbrains.idea.svn.integrate.SvnIntegrateChangesTask
import org.jetbrains.idea.svn.integrate.WorkingCopyInfo

/**
 * 真正执行合并，与原版 `QuickMerge` 流程保持一致：
 *
 * ```
 * LocalChangesPromptTask（合并前本地改动提示）
 *   → MergeTask（准备「记录本次合并内容」的活动变更列表）★ 2026-11-04 补齐
 *   → SvnIntegrateChangesTask（真正合并；进度条 / 冲突解决 / 结果展示全部由原版负责）
 * ```
 *
 * - [mergeWithLocalChangesPrompt] 复刻原版「合并前本地改动提示」——直接复用原版
 *   [LocalChangesPromptTask]（以临时实例化的 [QuickMerge] + [QuickMergeInteractionImpl] 作为宿主，
 *   仅用于提供 `MergeContext` / `Interaction` / `runInBackground`，**不调用** QuickMerge 的合并流程方法）；
 *   LocalChangesPromptTask 会检查工作副本本地改动与待合并版本路径的交集，弹出原版对话框让用户选择
 *   继续 / 搁置 / 查看 / 取消，与原版完全一致。
 * - **中间一步 [MergeTask]**（原版 `QuickMerge.merge` 链路里原本就有，本插件此前**漏掉了**）：
 *   它在合并前准备一个**空的活动变更列表**（名字 = 原版的 `Merge from <分支>`），合并产生的文件落在其中；
 *   若已存在同名且**有文件**的变更列表，则新建 `Merge from <分支> (1)` 并设为活动列表。
 *   漏掉这一步的后果：合并结果会混进用户**当前**的活动变更列表（如 Default Changelist），
 *   与原版行为不一致（用户看不到一个独立的「本次合并」列表）。
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

        // 仅作为 LocalChangesPromptTask / MergeTask 的宿主，不触发 QuickMerge 的合并流程。
        // 为什么不直接调 QuickMerge.merge/execute：那会连同它内部的 MergeTask 信号量包装、
        // selectMergeVariant（弹它自己的合并方式对话框）等整套流程一起启动，与本插件自己的
        // 修订选择对话框重复。这两个 Task 只从宿主取 MergeContext/Interaction，
        // 是复刻前置步骤的最小代价。
        val quickMerge = QuickMerge(context, QuickMergeInteractionImpl(context))

        // 真正执行合并的动作（由 MergeTask 在变更列表就绪后回调触发）
        val mergeAction = Runnable { merge(context, selected) }

        // 原版链路：LocalChangesPromptTask → MergeTask → SvnIntegrateChangesTask
        // （原版见 `QuickMerge.merge(title, mergerFactory, changeLists)`）
        val afterLocalChangesPrompt = Runnable {
            if (context.project.isDisposed) return@Runnable

            // ① 先保存文档（对齐原版 `QuickMerge.execute()` 的第一步）。
            //    ⚠️ 必须排在「切换活动变更列表」**之前**：保存产生的本地修改应落到用户
            //    **原有**的活动变更列表里（原版顺序：saveAllDocuments → MergeTask → 合并）；
            //    若先切列表，保存产生的修改就会混进新建的合并列表。
            //    此处是 write-safe 上下文（见下方 runOnEdt 的说明），`saveAllDocuments()`
            //    内部的 `TransactionGuard.assertWriteActionAllowed()` 不会报「Write-unsafe context!」。
            FileDocumentManager.getInstance().saveAllDocuments()

            // ② 再跑原版 MergeTask：查找/创建并**激活**用于记录本次合并内容的变更列表，
            //    变更列表模型刷新完毕后才真正合并。
            try {
                MergeTask(quickMerge, Runnable { runOnEdt(context, mergeAction) }).run()
            } catch (e: Exception) {
                // 变更列表只是「记录」手段，失败不应阻断合并——否则表现为「点了合并，什么也没发生」。
                // 原版此处的异常同样只进日志（平台会把未捕获异常记成插件内部错误，故这里 catch 住）。
                LOG.warn("Failed to prepare the change list for merge", e)
                runOnEdt(context, mergeAction)
            }
        }

        // ⚠️ 回调必须回到 EDT（2026-11-01 修复 P1-3）：
        // `LocalChangesPromptTask` 在「搁置(shelve)」分支里是在**后台任务**中调用本回调的，
        // 而后续要执行 `saveAllDocuments()`、`MergeTask`（变更列表模型写入）与
        // `SvnIntegrateChangesTask.queue()`——都要求在 EDT 调用。
        val callback = Runnable {
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) {
                // 已在 EDT：直接执行。原版 `LocalChangesPromptTask.processIntersection` 的
                // `continueMerge` 分支就是在 EDT 同步调用本回调的（`selectLocalChangesAction`
                // 的模态对话框返回后），此时仍处于外层 `invokeLater(defaultModalityState)`
                // 这个 **write-safe** 任务的调用栈内（TransactionGuard 已授予写权限），
                // 故 `saveAllDocuments()` 不会触发平台的 "Write-unsafe context" 断言。
                if (!context.project.isDisposed) afterLocalChangesPrompt.run()
            } else {
                // 后台线程（`LocalChangesPromptTask` 的 shelve/搁置分支在 `runInBackground` 里回调）：
                // 必须切回 EDT 才能执行 `saveAllDocuments()` 与 `SvnIntegrateChangesTask.queue()`。
                //
                // ⚠️ modality 必须给 **write-safe** 的状态，**禁用 `ModalityState.any()`**：
                // `any()` 返回的是独立实例 `AnyModalityState.ANY`，它**从未**被登记进
                // TransactionGuard 的 write-safe 模态表（`TransactionGuardImpl` 构造时只登记了
                // `ModalityState.NON_MODAL`，`enteredModality()` 只在进入**模态**时被调用）→
                // `isWriteSafeModality(any())` 恒为 false → `wrapLaterInvocation` 走「不授予写权限」
                // 分支 → 任务里调 `saveAllDocuments()` 会触发平台
                // `LOG.error("Write-unsafe context! ... (not \"any\")")`（2026-11-03 实测复现）。
                // `NON_MODAL` 恒为 write-safe，语义也正确：模态对话框打开期间会推迟执行，
                // 避免在模态中启动合并。
                app.invokeLater(
                    {
                        // 过期判定写在 lambda 内：排队期间项目可能已被关闭。
                        if (context.project.isDisposed) return@invokeLater
                        afterLocalChangesPrompt.run()
                    },
                    ModalityState.NON_MODAL,
                    context.project.disposed,
                )
            }
        }

        ApplicationManager.getApplication().invokeLater(
            {
                // ⚠️ 过期判定必须写在 **lambda 内**：平台的「项目已关闭」过期条件只覆盖排队期间，
                // 排队结束到真正执行之间项目仍可能被关闭 —— 那时不该再弹模态对话框。
                if (context.project.isDisposed) return@invokeLater
                try {
                    LocalChangesPromptTask(quickMerge, selected, callback).run()
                } catch (e: Exception) {
                    // 兜底：前置提示失败不应让整个流程无声终止（原版此处的异常同样只进日志）。
                    // 用 warn 而非 error：这是可恢复的前置步骤失败，不该按"插件内部错误"上报。
                    LOG.warn("Failed to run local changes prompt before merge", e)
                    // 但**必须让用户知道合并没有执行**（否则表现为"点了合并，什么也没发生"）。
                    Messages.showErrorDialog(
                        context.project,
                        EnhanceMergeBundle.message(
                            "error.merge.local.changes.failed",
                            // `orEmpty().ifBlank {}`：异常消息可能为空，用异常类名兜底
                            e.message.orEmpty().ifBlank { e.javaClass.simpleName },
                        ),
                        EnhanceMergeBundle.message("title.enhance.merge"),
                    )
                }
            },
            // 官方要求：invokeLater 必须显式给 ModalityState（默认状态在模态框关闭动画期间
            // 可能尚未恢复），并给"过期条件"——项目已关闭时直接丢弃，别再弹模态框。
            ModalityState.defaultModalityState(),
            context.project.disposed,
        )
    }

    /**
     * 在 **EDT + write-safe 上下文**执行 [action]。
     *
     * - 已在 EDT：直接执行（当前调用栈通常已在 write-safe 的 `invokeLater` 内）；
     * - 否则：`invokeLater(..., ModalityState.NON_MODAL, project.disposed)`（恒 write-safe，
     *   且会在模态对话框打开期间推迟执行）。
     *
     * 为什么需要它：`MergeTask` 内部通过
     * `ChangeListManager.invokeAfterUpdateWithProgress(...)` 回调合并动作，
     * 该回调**不一定**在 EDT 上触发（变更列表模型更新是异步的），
     * 而 `SvnIntegrateChangesTask.queue()` 要求在 EDT 调用。
     */
    private fun runOnEdt(context: MergeContext, action: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            if (!context.project.isDisposed) action.run()
        } else {
            app.invokeLater(
                {
                    if (!context.project.isDisposed) action.run()
                },
                ModalityState.NON_MODAL,
                context.project.disposed,
            )
        }
    }

    /**
     * 实际执行合并：对齐原版 `QuickMerge.newIntegrateTask`。
     *
     * ⚠️ 文档保存（`FileDocumentManager.saveAllDocuments`）**不在这里**，而在
     * [mergeWithLocalChangesPrompt] 的回调里、`MergeTask` **之前**完成——与原版
     * `QuickMerge.execute()` 的顺序一致（先保存文档，再切活动变更列表，最后合并）。
     * 这里若再保存一次，一旦本方法被调用的上下文不是 write-safe，就会触发平台
     * 「Write-unsafe context!」错误（2026-11-03 实测）。
     */
    fun merge(context: MergeContext, selected: List<SvnChangeList>) {
        if (selected.isEmpty()) return

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
