package com.weixf.svn.mergeenhance.tab

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.SvnVcs
import java.util.function.Predicate
import java.util.function.Supplier
import javax.swing.JComponent

/**
 * 「Enhance Merge」标签页的 [ChangesViewContentProvider] 实现（第六轮新增）。
 *
 * 经 `plugin.xml` 的 `<changesViewContent>` 注册到 VCS 工具窗，与「Subversion Working Copies
 * Information」并列。实现契约对齐官方 `GitStageContentProvider` / svn4idea `WorkingCopiesContent`：
 * 只覆写 [initContent]（构建面板）与 [disposeContent]（释放面板）；平台默认的 `initTabContent`
 * 会负责把 [initContent] 的组件挂到 Content 上、并注册一个调用 [disposeContent] 的 Disposer。
 */
class EnhanceMergeTabContent(private val project: Project) : ChangesViewContentProvider {

    /** 面板单例缓存：`initContent` 可能被多次调用，保证同一实例。 */
    private var panel: EnhanceMergePanel? = null

    override fun initContent(): JComponent {
        val existing = panel
        if (existing != null) return existing
        return EnhanceMergePanel(project).also { panel = it }
    }

    override fun disposeContent() {
        panel?.let { Disposer.dispose(it) }
        panel = null
    }

    /**
     * 可见性判定：仅在 SVN 项目显示该标签（与 `WorkingCopiesContent$VisibilityPredicate` 同构）。
     * 由 EP 以无参构造实例化。
     */
    class VisibilityPredicate : Predicate<Project> {
        override fun test(project: Project): Boolean =
            ProjectLevelVcsManager.getInstance(project).checkVcsIsActive(SvnVcs.VCS_NAME)
    }

    /**
     * 标签名供应商（本地化）：走本插件的 [EnhanceMergeBundle]（语言包感知）。
     * 由 EP 以无参构造实例化。
     */
    class DisplayNameSupplier : Supplier<String> {
        override fun get(): String = EnhanceMergeBundle.message("tab.name.enhance.merge")
    }
}
