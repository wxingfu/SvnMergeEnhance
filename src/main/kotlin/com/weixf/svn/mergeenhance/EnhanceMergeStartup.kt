package com.weixf.svn.mergeenhance

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 启动期诊断（第六轮，D1 ② 后新增）。
 *
 * 唯一职责：在插件加载时打印一条 `EnhanceMerge i18n` 诊断日志——
 * - 记录 IDE 当前语言与实际生效的文案包语言（排查"装中文语言包后界面语言不对"用）；
 * - 同时作为 `scripts/verify-plugin.ps1` 的**就绪信号**：该脚本以「日志里出现 `EnhanceMerge i18n`」
 *   判定插件已加载完成，比「进程存在」更可靠（能捕获启动期异常）。
 *
 * 早期这条日志由注入入口 `WcInfoLinkInjector` 的安装器在 init 里打印；D1 ② 删除注入入口后，
 * 就绪信号随之消失，故改由本类承担。本类**不做任何 UI 注入 / 反射 / 轮询**，仅打印一行日志
 * （符合「不反射注入其它插件」的上架规范）。
 */
class EnhanceMergeStartup : StartupActivity.DumbAware {

    private val LOG = Logger.getInstance(EnhanceMergeStartup::class.java)

    override fun runActivity(project: Project) {
        LOG.info("EnhanceMerge i18n state: ${EnhanceMergeBundle.localizationDiagnostics()}")
    }
}
