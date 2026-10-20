package com.weixf.svn.mergeenhance.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import javax.swing.JComponent

/**
 * 「版本区间」对话框 —— 语义与 TortoiseSVN 日志窗口的 **Show range** 一致：
 * 用两个独立输入框指定起始/结束修订号，**留空表示不限**。
 *
 * ## 为什么必须是独立控件，不能靠搜索框文本推断
 *
 * 早期实现把搜索框里"形如 `10-20`"的文本解析成版本区间（`LogFilter.looksLikeRevisionRange`），
 * 后果是搜 `2026-10`（本意是搜 2026 年 10 月）被静默改写成「版本区间 [10, 2026]」，
 * 而版本区间又会改变服务端游标，于是用户得到一个**空列表且没有任何报错**。
 * 更糟的是"改搜索词"会隐式切换查询通道，出现"我只是加了个关键词，列表却整个变了"的现象。
 *
 * TortoiseSVN 的做法是：版本区间放在独立的 Show range 对话框里（`m_startRev`/`m_endRev`，
 * 见 `LogDlg.cpp` 的 `ID_CMD_SHOWRANGE`），搜索框里修订号只作为**普通文本**参与匹配
 * （`LogDlgFilter.cpp` 的 `LOGFILTER_REVS` 分支仅把修订号拼进待匹配字符串）。
 * 本类即该设计的复刻。
 */
class RevRangeDialog(
    project: Project,
    initialFrom: Long?,
    initialTo: Long?,
) : DialogWrapper(project) {

    private val fromField = JBTextField(initialFrom?.toString().orEmpty())
    private val toField = JBTextField(initialTo?.toString().orEmpty())

    init {
        title = EnhanceMergeBundle.message("revrange.title")
        // OK / Cancel 沿用平台自带文案（随 IDE 语言变化），不自行硬编码
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hint = JBLabel(EnhanceMergeBundle.message("revrange.hint")).apply {
            foreground = UIUtil.getContextHelpForeground()
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(EnhanceMergeBundle.message("revrange.from")), fromField, 1, false)
            .addLabeledComponent(JBLabel(EnhanceMergeBundle.message("revrange.to")), toField, 1, false)
            .addComponent(hint)
            // FormBuilder 在 2022.1 没有 addLineGap()（官方规范：改用 addVerticalGap(-1)）
            .addVerticalGap(-1)
            .panel
            .apply { border = JBUI.Borders.empty(2, 0, 2, 0) }
    }

    override fun getPreferredFocusedComponent(): JComponent = fromField

    /**
     * 输入校验：**只接受单个修订号或空**。
     *
     * 刻意不支持 `40-50` 这种区间写法——两个框已经能表达区间，写在同一个框里反而会
     * 重演"一个输入框承担两种语义"的歧义（也正是旧搜索框的毛病）。
     */
    override fun doValidate(): ValidationInfo? {
        val fromText = fromField.text.trim()
        val toText = toField.text.trim()
        val from = if (fromText.isEmpty()) null else fromText.toLongOrNull()
        val to = if (toText.isEmpty()) null else toText.toLongOrNull()
        if (fromText.isNotEmpty() && (from == null || from < 0)) {
            return ValidationInfo(EnhanceMergeBundle.message("revrange.error"), fromField)
        }
        if (toText.isNotEmpty() && (to == null || to < 0)) {
            return ValidationInfo(EnhanceMergeBundle.message("revrange.error"), toField)
        }
        if (from != null && to != null && from > to) {
            return ValidationInfo(EnhanceMergeBundle.message("revrange.error.order"), fromField)
        }
        return null
    }

    /**
     * 确定后的结果（`起始修订号` 与 `截止修订号`，null = 该侧不限）。
     *
     * 必须在 [showAndGet] 返回 true 之后调用；校验已由 [doValidate] 保证。
     */
    fun range(): Pair<Long?, Long?> =
        fromField.text.trim().toLongOrNull() to toField.text.trim().toLongOrNull()
}
