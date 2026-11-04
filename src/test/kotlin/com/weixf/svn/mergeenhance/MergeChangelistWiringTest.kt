package com.weixf.svn.mergeenhance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「合并链路与原版一致」的**接线**回归测试（2026-11-04 用户报障：合并前的活动变更列表处理与原版不一致）。
 *
 * ## 背景：原版做了什么，本插件此前漏了什么
 *
 * 原版 svn4idea 的合并链路（`QuickMerge.merge(title, mergerFactory, changeLists)`）是：
 *
 * ```
 * LocalChangesPromptTask（合并前本地改动提示：继续 / 搁置 / 查看 / 取消）
 *   → MergeTask（★ 准备「记录本次合并内容」的活动变更列表 ★）
 *   → SvnIntegrateChangesTask（真正合并）
 * ```
 *
 * `MergeTask.setupDefaultEmptyChangeListForMerge()` 的语义（原版源码逐行）：
 *
 * - 变更列表名取 `MergeContext.getMergeTitle()`（= `Merge from <分支名>`）；
 * - 若**不存在**同名变更列表 → 新建它并**设为活动（默认）变更列表**；
 * - 若存在同名变更列表且**为空** → 直接把它设为活动变更列表；
 * - 若存在同名变更列表且**有文件** → 名字加序号 `(" (1)")` 继续找，找到空的就用它，
 *   找不到就新建一个并设为活动。
 *
 * 即：**合并结果落在一个专属于本次合并的活动变更列表里**，而不是混进用户当前的
 * 「Default Changelist」。本插件此前从 `LocalChangesPromptTask` 直接跳到
 * `SvnIntegrateChangesTask`，**跳过了 `MergeTask`** → 用户看不到独立的合并列表。
 *
 * ## 为什么用静态校验
 *
 * 这条链路依赖 svn4idea 的三个类 + 平台的变更列表模型，纯逻辑单测覆盖不到；
 * 且「少了 `MergeTask`」**不会报错**，只是行为与原版不一致（属「静默偏离」，最难被发现）。
 *
 * ⚠️ 运行时顺序由**回调嵌套**决定（提示任务 → 回调 → MergeTask → 回调 → 合并），
 * 与源码里的文本先后**无关**，故下面的断言校验的是「嵌套 / 包含关系」，
 * 而不是简单的下标大小关系。
 */
class MergeChangelistWiringTest {

    private fun sourceFile(): File {
        val relative = "src/main/kotlin/com/weixf/svn/mergeenhance/merge/MergeExecutor.kt"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码文件 $relative（测试的工作目录异常）")
    }

    /**
     * 只取**代码行**（滤掉纯注释行）。
     *
     * 为什么必须滤注释：被校验的源码里大量注释会**提到**被禁/被要求的 API
     * （例如注释里写 `ModalityState.any()` 说明为何不能用、`saveAllDocuments()` 说明顺序），
     * 不滤注释就会把「注释里提到」误判成「代码里用了」。
     */
    private fun code(): String = sourceFile().readText(Charsets.UTF_8)
        .lines()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    @Test
    fun `merge chain runs the original merge task`() {
        val text = code()
        assertTrue(
            text.contains("import org.jetbrains.idea.svn.integrate.MergeTask"),
            "MergeExecutor 未引用原版 MergeTask —— 合并前不会准备「记录本次合并内容」的活动变更列表，" +
                "合并结果会混进用户当前的 Default Changelist（与原版行为不一致）",
        )
        assertTrue(
            text.contains("MergeTask(quickMerge"),
            "MergeExecutor 没有真正实例化 MergeTask（只 import 不算）",
        )
    }

    @Test
    fun `merge chain is wired as prompt then merge task then integrate`() {
        val text = code()

        // ① 合并前本地改动提示：其回调是 `callback`
        assertTrue(
            text.contains("LocalChangesPromptTask(quickMerge, selected, callback)"),
            "合并前本地改动提示必须仍走原版 LocalChangesPromptTask（回调用 callback）",
        )
        // ② 提示通过后才准备变更列表：callback 体内调用 afterLocalChangesPrompt
        assertTrue(
            text.contains("afterLocalChangesPrompt.run()"),
            "提示任务通过后应调用 afterLocalChangesPrompt（在其中保存文档并跑 MergeTask）",
        )
        // ③ MergeTask 的回调 = 真正的合并动作（且必须切回 EDT）
        assertTrue(
            text.contains("MergeTask(quickMerge, Runnable { runOnEdt(context, mergeAction) })"),
            "MergeTask 的回调必须是「切回 EDT 后执行合并动作」——" +
                "它的回调经 ChangeListManager.invokeAfterUpdateWithProgress 触发，不一定在 EDT",
        )
        // ④ 合并动作 → merge() → queue SvnIntegrateChangesTask
        assertTrue(
            text.contains("val mergeAction = Runnable { merge(context, selected) }"),
            "合并动作应统一走 merge(context, selected)",
        )
        assertTrue(
            text.contains("SvnIntegrateChangesTask("),
            "真正合并仍应 queue 原版 SvnIntegrateChangesTask",
        )
    }

    @Test
    fun `documents are saved before the change list is switched`() {
        val text = code()
        val afterPrompt = text.indexOf("val afterLocalChangesPrompt = Runnable {")
        val save = text.indexOf("saveAllDocuments()")
        val mergeTask = text.indexOf("MergeTask(quickMerge")
        val callback = text.indexOf("val callback = Runnable {")

        assertTrue(afterPrompt >= 0 && save >= 0 && mergeTask >= 0 && callback >= 0, "合并链路的调用点缺失")
        assertTrue(
            afterPrompt < save && save < mergeTask && mergeTask < callback,
            "顺序错误：应在 afterLocalChangesPrompt 内**先保存文档**、**再跑 MergeTask**（对齐原版 " +
                "QuickMerge.execute() 的 saveAllDocuments → MergeTask 顺序），" +
                "否则保存产生的本地修改会混进新建的合并列表",
        )
    }

    @Test
    fun `quick merge flow methods are never called`() {
        val text = code()
        for (forbidden in listOf(
            "quickMerge.execute(",
            "quickMerge.merge(",
            "quickMerge.mergeAll(",
            "quickMerge.selectMergeVariant(",
        )) {
            assertTrue(
                !text.contains(forbidden),
                "禁止调用 QuickMerge 的流程方法（$forbidden）：" +
                    "它会连同 QuickMerge 自己的合并方式对话框与信号量包装一起启动，" +
                    "与本插件的修订选择对话框重复；QuickMerge 只能作为 LocalChangesPromptTask / MergeTask 的宿主",
            )
        }
    }

    @Test
    fun `merge action is dispatched on the edt`() {
        val text = code()
        assertTrue(
            text.contains("runOnEdt(context, mergeAction)"),
            "MergeTask 的回调（ChangeListManager.invokeAfterUpdateWithProgress）不一定在 EDT 触发，" +
                "合并动作必须经 runOnEdt 切回 EDT（且用 write-safe 的 ModalityState.NON_MODAL）",
        )
        assertTrue(
            !text.contains("ModalityState.any()"),
            "禁止使用 ModalityState.any()：它从未登记进 TransactionGuard 的 write-safe 模态表 → " +
                "平台会打 \"Write-unsafe context!\" 错误（应改用 ModalityState.NON_MODAL）",
        )
    }
}
