package com.weixf.svn.mergeenhance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「工作副本列表刷新」链路的**接线**回归测试（2026-11-04 用户报障：切换分支后面板 URL 不刷新）。
 *
 * ## 背景：为什么用「读源码」这种静态方式守护
 *
 * 本插件的刷新链路横跨**两个 svn4idea 组件**，且中间隔着一次**异步事件**，纯逻辑单测无法覆盖：
 *
 * ```
 * 分支切换 / svn update
 *   → NestedCopiesBuilder.bewareRoot（发现根 URL 与缓存映射不符）
 *   → SvnVcs.invokeRefreshSvnRoots()（重扫工作副本根）
 *   → SvnFileUrlMappingImpl.notifyRootsReloaded（更新「根 → URL」缓存映射）
 *   → 广播 SvnVcs.ROOTS_RELOADED  ← 面板必须订阅这个事件才会重绘
 * ```
 *
 * 原版 `CopiesPanel` 订阅了该事件，所以「Subversion Working Copies Information」面板的 URL 会自动刷新；
 * 本插件一旦漏掉订阅（或刷新按钮不去触发真正的重扫），两个面板就会显示**不同的 URL**，
 * 而这在编译期与运行期都不报错（只是「数据陈旧」），故在此用源码静态校验钉住。
 *
 * 守护点：
 * 1. **必须订阅 `SvnVcs.ROOTS_RELOADED`** —— 否则分支切换后 URL 永不更新（本次报障根因）；
 * 2. **刷新按钮必须调用 `invokeRefreshSvnRoots()`** —— `getAllWcInfos()` 只读**缓存映射**，
 *    不重扫就永远读不到新 URL；
 * 3. **不得使用 `ModalityState.any()`** —— 该模态态从未登记进 TransactionGuard 的 write-safe 表，
 *    平台会打 "Write-unsafe context!" 错误（2026-11-03 同类问题的规范沉淀）。
 */
class WorkingCopyRefreshWiringTest {

    private fun sourceFile(): File {
        val relative = "src/main/kotlin/com/weixf/svn/mergeenhance/tab/EnhanceMergePanel.kt"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码文件 $relative（测试的工作目录异常）")
    }

    private fun source(): String = sourceFile().readText(Charsets.UTF_8)

    @Test
    fun `panel subscribes to the svn roots reloaded topic`() {
        val text = source()
        assertTrue(
            text.contains("ROOTS_RELOADED"),
            "EnhanceMergePanel 未引用 SvnVcs.ROOTS_RELOADED —— " +
                "分支切换后 svn4idea 重扫根并广播该事件，面板不订阅就永远不会刷新 URL",
        )
        assertTrue(
            Regex("""subscribe\(\s*ROOTS_RELOADED""").containsMatchIn(text),
            "EnhanceMergePanel 没有真正 subscribe(ROOTS_RELOADED, ...) —— " +
                "只引用常量不算订阅，事件仍收不到",
        )
    }

    @Test
    fun `refresh action triggers a real roots rescan`() {
        val text = source()
        assertTrue(
            text.contains("invokeRefreshSvnRoots"),
            "刷新按钮必须调用 SvnVcs.invokeRefreshSvnRoots()（对齐原版 CopiesPanel.refresh）—— " +
                "getAllWcInfos() 只读缓存映射，不重扫就刷不出分支切换后的新 URL",
        )
    }

    @Test
    fun `panel never uses the non write safe modality state`() {
        // 只看**代码行**：注释里提到 `ModalityState.any()`（说明为何不能用）不算违规
        val code = source().lines().filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        assertTrue(
            code.none { it.contains("ModalityState.any()") },
            "禁止使用 ModalityState.any()：它从未登记进 TransactionGuard 的 write-safe 模态表 → " +
                "平台会打 \"Write-unsafe context!\" 错误（应改用 ModalityState.NON_MODAL）",
        )
    }
}
