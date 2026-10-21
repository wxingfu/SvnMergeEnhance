package com.weixf.svn.mergeenhance.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 设置页：`Settings/Preferences → Version Control → SVN Enhance Merge`。
 *
 * 两个配置项（第五轮收敛后）：
 * - 每页条数（10–500，默认 100 对齐 TortoiseSVN 的 `NumberOfLogs`）
 * - 计算已合并状态（默认开启）
 *
 * 历史上有「Bug-ID 正则」「默认日期范围（天）」两项，随过滤维度退化
 * （By 字段勾选 / 日期区间删除）一并移除，见 `LogFilter` 的沿革说明。
 */
class EnhanceMergeConfigurable : Configurable {

    private val settings = EnhanceMergeSettings.getInstance()

    private var pageSizeSpinner: JSpinner? = null
    private var mergeStatusCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = EnhanceMergeBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        pageSizeSpinner = JSpinner(
            SpinnerNumberModel(
                settings.pageSize,
                EnhanceMergeSettings.MIN_PAGE_SIZE,
                EnhanceMergeSettings.MAX_PAGE_SIZE,
                PAGE_SIZE_STEP,
            )
        )
        mergeStatusCheckBox = JBCheckBox().apply { isSelected = settings.computeMergeStatus }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(EnhanceMergeBundle.message("settings.page.size")), pageSizeSpinner!!)
            .addTooltip(EnhanceMergeBundle.message("settings.page.size.hint"))
            .addVerticalGap(FORM_LINE_GAP)
            .addLabeledComponent(
                JBLabel(EnhanceMergeBundle.message("settings.compute.merge.status")),
                mergeStatusCheckBox!!,
            )
            .addTooltip(EnhanceMergeBundle.message("settings.compute.merge.status.hint"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val spinner = pageSizeSpinner ?: return false
        val merge = mergeStatusCheckBox ?: return false
        return spinner.value != settings.pageSize ||
            merge.isSelected != settings.computeMergeStatus
    }

    override fun apply() {
        (pageSizeSpinner?.value as? Int)?.let { settings.pageSize = it }
        mergeStatusCheckBox?.isSelected?.let { settings.computeMergeStatus = it }
    }

    override fun reset() {
        pageSizeSpinner?.value = settings.pageSize
        mergeStatusCheckBox?.isSelected = settings.computeMergeStatus
    }

    override fun disposeUIResources() {
        pageSizeSpinner = null
        mergeStatusCheckBox = null
    }

    override fun getHelpTopic(): String? = null

    private companion object {
        /**
         * `FormBuilder.addVerticalGap` 的"一整行"间距。
         *
         * 传 -1 时平台插入一个空 `JLabel`，高度由**当前行高/字体/HiDPI 缩放**决定；
         * 直接写像素（如 12）在 200% 缩放下会明显偏窄，故不用魔法数字。
         */
        const val FORM_LINE_GAP = -1

        /** 每页条数 spinner 的步进（10 条一档）。 */
        const val PAGE_SIZE_STEP = 10
    }
}
