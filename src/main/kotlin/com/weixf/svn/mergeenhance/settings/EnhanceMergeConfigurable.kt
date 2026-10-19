package com.weixf.svn.mergeenhance.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 设置页：`Settings/Preferences → Version Control → SVN Enhance Merge`（Spec R11）。
 *
 * 四个配置项：
 * - 每页条数（10–500，默认 100 对齐 TortoiseSVN 的 `NumberOfLogs`）
 * - 计算已合并状态（默认开启）
 * - Bug-ID 正则
 * - 默认日期范围（天，0 = 不限制）
 */
class EnhanceMergeConfigurable : Configurable {

    private val settings = EnhanceMergeSettings.getInstance()

    private var pageSizeSpinner: JSpinner? = null
    private var mergeStatusCheckBox: JBCheckBox? = null
    private var bugIdField: JBTextField? = null
    private var dateRangeSpinner: JSpinner? = null

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
        bugIdField = JBTextField(settings.bugIdPattern, PATTERN_FIELD_COLUMNS)
        dateRangeSpinner = JSpinner(
            SpinnerNumberModel(settings.defaultDateRangeDays, 0, MAX_DATE_RANGE_DAYS, DATE_RANGE_STEP)
        )

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(EnhanceMergeBundle.message("settings.page.size")), pageSizeSpinner!!)
            .addTooltip(EnhanceMergeBundle.message("settings.page.size.hint"))
            .addVerticalGap(FORM_LINE_GAP)
            .addLabeledComponent(
                JBLabel(EnhanceMergeBundle.message("settings.compute.merge.status")),
                mergeStatusCheckBox!!,
            )
            .addTooltip(EnhanceMergeBundle.message("settings.compute.merge.status.hint"))
            .addVerticalGap(FORM_LINE_GAP)
            .addLabeledComponent(JBLabel(EnhanceMergeBundle.message("settings.bug.id.pattern")), bugIdField!!)
            .addTooltip(EnhanceMergeBundle.message("settings.bug.id.pattern.hint"))
            .addVerticalGap(FORM_LINE_GAP)
            .addLabeledComponent(
                JBLabel(EnhanceMergeBundle.message("settings.default.date.range")),
                dateRangeSpinner!!,
            )
            .addTooltip(EnhanceMergeBundle.message("settings.default.date.range.hint"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val spinner = pageSizeSpinner ?: return false
        val merge = mergeStatusCheckBox ?: return false
        // 输入框本身只做非空校验，取值统一走 normalizedBugIdPattern()（已对 null 安全）
        if (bugIdField == null) return false
        val dateRange = dateRangeSpinner ?: return false
        return spinner.value != settings.pageSize ||
            merge.isSelected != settings.computeMergeStatus ||
            normalizedBugIdPattern() != settings.bugIdPattern ||
            dateRange.value != settings.defaultDateRangeDays
    }

    override fun apply() {
        (pageSizeSpinner?.value as? Int)?.let { settings.pageSize = it }
        mergeStatusCheckBox?.isSelected?.let { settings.computeMergeStatus = it }
        settings.bugIdPattern = normalizedBugIdPattern()
        (dateRangeSpinner?.value as? Int)?.let { settings.defaultDateRangeDays = it }
    }

    /**
     * 输入框里的 Bug-ID 正则归一化：去首尾空白，空串回退默认值（等价于"不提取 Bug-ID"）。
     *
     * [isModified] 与 [apply] **必须共用这一套**归一化：此前前者比原始文本、后者写 trim 后文本，
     * 输入尾部空格时会永远判定"已修改"（应用完再比对仍不等）。
     */
    private fun normalizedBugIdPattern(): String =
        bugIdField?.text?.trim().orEmpty().ifEmpty { EnhanceMergeSettings.DEFAULT_BUG_ID_PATTERN }

    override fun reset() {
        pageSizeSpinner?.value = settings.pageSize
        mergeStatusCheckBox?.isSelected = settings.computeMergeStatus
        bugIdField?.text = settings.bugIdPattern
        dateRangeSpinner?.value = settings.defaultDateRangeDays
    }

    override fun disposeUIResources() {
        pageSizeSpinner = null
        mergeStatusCheckBox = null
        bugIdField = null
        dateRangeSpinner = null
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

        /** 日期范围 spinner 的步进（1 天）。 */
        const val DATE_RANGE_STEP = 1

        /** 日期范围上限：10 年，够用且防止误填天文数字。 */
        const val MAX_DATE_RANGE_DAYS = 3650

        /** Bug-ID 正则输入框的列数（列是字符数，与 HiDPI 缩放无关）。 */
        const val PATTERN_FIELD_COLUMNS = 40
    }
}
