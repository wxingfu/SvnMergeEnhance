package com.weixf.svn.mergeenhance

import com.intellij.openapi.project.Project
import com.weixf.svn.mergeenhance.flow.MergeFlow
import org.jetbrains.idea.svn.dialogs.WCInfo
import java.awt.Component

/**
 * 「Enhance Merge…」的流程入口。
 *
 * ## 为什么不再继承 [com.intellij.openapi.actionSystem.AnAction]
 *
 * 本插件**不把它注册到任何菜单 / 右键组**（2026-10-18 决策）：唯一入口是
 * 「Subversion Working Copies Information」面板中注入的 Enhance Merge 链接
 * （见 `com.weixf.svn.mergeenhance.wcinfo.WcInfoLinkInjector`），由该链接直接调用 [performFor]。
 *
 * 继承 `AnAction` 时必须实现 `update()` / `actionPerformed()`，而没有任何扩展点注册它们，
 * 这两个方法就成了永不执行的死代码（还会连带 `CommonDataKeys` / `FileUtil` /
 * `ProjectLevelVcsManager` 等一串看似"可用"实则走不到的导入）。因此这里改为普通类：
 * - 真正被调用的只有 [performFor]；
 * - "仅在 SVN 工作副本下可用"由入口链接本身保证——它只出现在 SVN 的 WC 信息面板里，
 *   且 [MergeFlow.start] 内还有`SvnVcs.getInstance(project) ?: return` 兜底。
 *
 * 若将来要把入口挂到菜单上（VCS 菜单 / 右键），再改回 `AnAction` 并实现
 * `update()` + `getActionUpdateThread()` 即可，本类的方法签名无需变动。
 */
class EnhanceMergeAction {

    /**
     * 以**已知**的工作副本启动流程：分支选择 → 合并方式 → 修订选择 → 合并。
     *
     * WC 面板链接在点击时已持有精确的 `WCInfo`（反射自面板本身），因此这里**不**做
     * "按选中文件反查工作副本"——那条路径走 `SvnVcs.allWcInfos`，可能触发
     * `SvnFileUrlMapping` 重新探测工作副本、进而在 EDT 上同步执行 `svn info`，
     * 触发平台断言 `Synchronous execution on EDT`（并把本插件列为 blame）。
     *
     * @param project 当前项目
     * @param wcInfo  目标工作副本（来自面板链接所点击的那一个）
     * @param anchor  分支弹窗的锚点组件（面板链接本身）；为 null 时弹窗居中
     */
    fun performFor(project: Project, wcInfo: WCInfo, anchor: Component?) {
        MergeFlow.start(project, wcInfo, anchor)
    }
}