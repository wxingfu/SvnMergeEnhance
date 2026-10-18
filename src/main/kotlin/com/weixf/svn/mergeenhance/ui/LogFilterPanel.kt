package com.weixf.svn.mergeenhance.ui

import com.github.lgooddatepicker.components.DatePicker
import com.github.lgooddatepicker.components.DatePickerSettings
import com.github.lgooddatepicker.components.DatePickerSettings.DateArea
import com.github.lgooddatepicker.optionalusertools.CalendarBorderProperties
import com.github.lgooddatepicker.optionalusertools.DateChangeListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogFilterField
import com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 图4 顶部过滤栏，交互严格对齐 TortoiseSVN「被过滤信息」：
 * `[By ▾] [搜索框 ✕] [From: 日期] [To: 日期]`。
 *
 * - `By ▾`：紧凑下拉按钮（TortoiseSVN `OnClickedInfoIcon`），弹出勾选项 =
 *   Messages / Paths / Authors / Revisions / Bug-IDs / Date / Date range，分隔线后接
 *   Use regular expressions / Match case；按钮 tooltip 显示当前生效字段。
 * - 搜索框右侧内嵌 **X 按钮**（对齐 TortoiseSVN 过滤条的清除图标）：清空搜索词并把
 *   起止日历重置为当前页范围。
 * - `Date range` 为独立开关：勾选时才显示起止日历（TortoiseSVN `AdjustDateFilterVisibility`），
 *   且默认取当前页最早/最晚日期（`OnClickedCancelFilter` 行为）。
 * - 日期控件采用 LGoodDatePicker，配色取自 IDEA 主题；**隐藏其自带的切换按钮**，
 *   改为**点击日期框即弹出日历**，语言跟随 [EnhanceMergeBundle.locale]（与界面一致），
 *   一周起始日固定为**周一**（中国习惯，不随 locale 漂移，见 [createDatePicker]）。
 * - 任意变化 300ms 防抖后回调。
 */
class LogFilterPanel(
    parentDisposable: Disposable,
    private val onFilterChanged: () -> Unit,
) : JPanel() {

    private val settings = EnhanceMergeSettings.getInstance()

    /**
     * 勾选的过滤字段：初始值取自上次使用（持久化在 [EnhanceMergeSettings]），
     * 为空/全非法时回退默认全选。
     */
    private val enabledFields: LinkedHashSet<LogFilterField> = LinkedHashSet(restoredFilterFields())

    private var dateRangeEnabled: Boolean = settings.dateRangeEnabled
    private var useRegex: Boolean = settings.useRegex
    private var caseSensitive: Boolean = settings.caseSensitive

    private val byButton = JButton().apply {
        margin = JBUI.insets(2, 8)
        isFocusable = false
        addActionListener { showByPopup(this) }
    }
    private val searchField = JBTextField().apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0, 2, 0)
    }

    /** 搜索框内右侧的清除按钮（形似 X，对齐 TortoiseSVN 过滤条上的清除图标）。 */
    private val clearSearchButton = JButton(AllIcons.Actions.Close).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        isRolloverEnabled = true
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(20, 20)
        toolTipText = EnhanceMergeBundle.message("filter.clear")
        // 官方无障碍要求：纯图标按钮必须给出可读名称，否则屏幕阅读器只会念出 "button"
        accessibleContext.accessibleName = EnhanceMergeBundle.message("filter.clear")
        addActionListener { reset() }
    }

    /** 搜索框 = 无边框输入框 + 内嵌 X 按钮，整体套一层与 IDEA 文本框一致的细边框。 */
    private val searchBox = JPanel(BorderLayout(0, 0)).apply {
        background = UIUtil.getTextFieldBackground()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(0, 4, 0, 0),
        )
        add(searchField, BorderLayout.CENTER)
        add(clearSearchButton, BorderLayout.EAST)
    }

    private val fromPicker = createDatePicker()
    private val toPicker = createDatePicker()

    /** 起止日历容器；`Date range` 未勾选时整体隐藏。 */
    private val datePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ITEM_HGAP), 0)).apply {
        add(JBLabel(EnhanceMergeBundle.message("filter.from")))
        add(fromPicker)
        add(JBLabel(EnhanceMergeBundle.message("filter.to")))
        add(toPicker)
    }

    /**
     * 输入防抖。
     *
     * 用平台的 [Alarm] 而**不是** `javax.swing.Timer`：官方明确要求禁用 Swing Timer
     * （它不受 Disposer 管理、对模态状态无感知、面板关闭后仍可能回调）。
     * [Alarm] 绑在对话框的 disposable 上，对话框一关请求自动作废。
     */
    private val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var suppressEvents = false
    private var dateBounds: Pair<Date?, Date?> = null to null

    init {
        layout = BorderLayout(JBUI.scale(ITEM_HGAP), 0)
        border = JBUI.Borders.empty(2, 6, 2, 6)

        searchField.emptyText.text = EnhanceMergeBundle.message("filter.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                syncClearVisibility()
                onInputChanged()
            }

            override fun removeUpdate(e: DocumentEvent) {
                syncClearVisibility()
                onInputChanged()
            }

            override fun changedUpdate(e: DocumentEvent) {
                syncClearVisibility()
                onInputChanged()
            }
        })

        val dateListener = DateChangeListener { onInputChanged() }
        fromPicker.addDateChangeListener(dateListener)
        toPicker.addDateChangeListener(dateListener)

        val west = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(BUTTON_HGAP), 0)).apply { add(byButton) }
        val east = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ITEM_HGAP), 0)).apply { add(datePanel) }

        add(west, BorderLayout.WEST)
        add(searchBox, BorderLayout.CENTER)
        add(east, BorderLayout.EAST)

        updateByButton()
        syncDateVisibility()
        syncClearVisibility()
    }

    /** X 按钮只在输入框有内容时出现，为空则隐藏（与 TortoiseSVN 一致）。 */
    private fun syncClearVisibility() {
        clearSearchButton.isVisible = searchField.text.isNotEmpty()
    }

    // ------------------------------------------------------------- 日期控件

    /**
     * 构建与 IDEA 主题一致的 LGoodDatePicker：
     * - 去掉右侧「切换日历」按钮（置零尺寸但仍显示，供弹窗锚点计算）；
     * - 点击日期框 / 控件本体即弹出日历；
     * - **日期必填**（不可清空），日历弹窗底部的 Clear 一并去掉；
     * - 边框 / 背景与搜索框统一（1px 主题边框 + 文本框背景），日历弹窗外框同样统一；
     * - 配色取自 [UIUtil] 主题色，语言取 [EnhanceMergeBundle.locale]。
     */
    private fun createDatePicker(): DatePicker {
        val settings = DatePickerSettings(EnhanceMergeBundle.locale)
        settings.setFormatForDatesCommonEra(DATE_TEXT_PATTERN)
        // 一周第一天固定为周一（中国习惯：周一在首列、周日在末列）。
        // 为什么必须显式设置：LGoodDatePicker 默认按 locale 推断起始日——英文 locale 是
        // 周日开头，装了中文语言包才是周一起始。若跟随 locale，会出现"中文 IDE 周一开头、
        // 英文 IDE 周日开头"的不一致；日期选择是高频操作，按目标用户习惯统一固定更合适。
        // （周数显示已关闭，不会触发 WeekFields 覆盖该设置。）
        settings.setFirstDayOfWeek(DayOfWeek.MONDAY)
        // 日期区间是必须的（TortoiseSVN 的起止日期同样不允许留空）
        settings.setAllowEmptyDates(false)
        settings.setAllowKeyboardEditing(false)
        // 去掉日历弹窗底部的「Clear」：日期不可被清空，该按钮没有意义
        settings.setVisibleClearButton(false)
        // LGood 按字体自动算宽度，在高 DPI / IDEA 字体下会偏窄导致「2023-12-31」被裁成「023-12-31」，
        // 这里显式给足宽度并强制覆盖默认值。
        settings.setSizeTextFieldMinimumWidth(JBUI.scale(DATE_FIELD_MIN_WIDTH))
        settings.setSizeTextFieldMinimumWidthDefaultOverride(true)
        // 弹窗外框：默认是 LGood 硬编码的蓝色 LineBorder(99,130,191)，改为与 By / 搜索框一致的
        // 1px 主题边框 + 2px 留白。
        settings.setBorderCalendarPopup(
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2),
            )
        )
        // 日期网格外围那圈默认蓝色边框（CalendarBorderProperties）一并清空，
        // 让弹窗只剩一层主题色外框。
        settings.setBorderPropertiesList(ArrayList<CalendarBorderProperties>())
        applyThemeColors(settings)

        val picker = DatePicker(settings)

        // 隐藏自带的日历切换按钮：置零但保持可见，避免 openPopup() 计算锚点时抛异常
        picker.componentToggleCalendarButton.apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            text = ""
            icon = null
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty()
            isFocusable = false
        }

        // 点击日期框直接弹出日历（不再依赖额外按钮）
        val opener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && !picker.isPopupOpen) {
                    picker.togglePopup()
                }
            }
        }
        picker.componentDateTextField.addMouseListener(opener)
        picker.addMouseListener(opener)

        // 边框 / 背景与「By」按钮、搜索框统一：1px 主题边框 + 文本框背景
        picker.apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            background = UIUtil.getTextFieldBackground()
        }
        picker.componentDateTextField.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 4)
            background = UIUtil.getTextFieldBackground()
            foreground = UIUtil.getTextFieldForeground()
        }
        return picker
    }

    private fun applyThemeColors(s: DatePickerSettings) {
        val bg = UIUtil.getPanelBackground()
        val fg = UIUtil.getLabelForeground()
        val fieldBg = UIUtil.getTextFieldBackground()
        val fieldFg = UIUtil.getTextFieldForeground()
        val selBg = UIUtil.getListSelectionBackground(true)
        val inactive = UIUtil.getInactiveTextColor()

        // 输入框
        s.setColor(DateArea.TextFieldBackgroundValidDate, fieldBg)
        s.setColor(DateArea.TextFieldBackgroundInvalidDate, fieldBg)
        s.setColor(DateArea.TextFieldBackgroundDisallowedEmptyDate, fieldBg)
        s.setColor(DateArea.TextFieldBackgroundVetoedDate, fieldBg)
        s.setColor(DateArea.TextFieldBackgroundDisabled, bg)
        s.setColor(DateArea.DatePickerTextValidDate, fieldFg)
        s.setColor(DateArea.DatePickerTextInvalidDate, UIUtil.getErrorForeground())
        s.setColor(DateArea.DatePickerTextVetoedDate, fieldFg)
        s.setColor(DateArea.DatePickerTextDisabled, inactive)

        // 日历面板
        s.setColor(DateArea.BackgroundOverallCalendarPanel, bg)
        s.setColor(DateArea.CalendarBackgroundNormalDates, bg)
        s.setColor(DateArea.CalendarBackgroundSelectedDate, selBg)
        s.setColor(DateArea.CalendarBorderSelectedDate, selBg)
        s.setColor(DateArea.CalendarBackgroundVetoedDates, bg)
        s.setColor(DateArea.CalendarTextNormalDates, fg)
        s.setColor(DateArea.CalendarTextWeekdays, fg)
        s.setColor(DateArea.CalendarTextWeekNumbers, inactive)
        s.setColor(DateArea.TextMonthAndYearMenuLabels, fg)
        s.setColor(DateArea.BackgroundMonthAndYearMenuLabels, bg)
        s.setColor(DateArea.TextMonthAndYearNavigationButtons, fg)
        s.setColor(DateArea.BackgroundMonthAndYearNavigationButtons, bg)
        s.setColor(DateArea.TextTodayLabel, fg)
        s.setColor(DateArea.BackgroundTodayLabel, bg)
        s.setColor(DateArea.TextClearLabel, fg)
        s.setColor(DateArea.BackgroundClearLabel, bg)
        s.setColor(DateArea.TextCalendarPanelLabelsOnHover, fg)
        s.setColor(DateArea.BackgroundCalendarPanelLabelsOnHover, selBg)
        s.setColor(DateArea.BackgroundTopLeftLabelAboveWeekNumbers, bg)

        s.setColorBackgroundWeekdayLabels(bg, false)
        s.setWeekNumbersDisplayed(false, false)
    }

    // ------------------------------------------------------------- 交互

    private fun onInputChanged() {
        if (suppressEvents) return
        persistOptions()
        // 防抖：先取消未触发的请求再排一个新的（Alarm 没有 Swing Timer 的 restart()）
        debounce.cancelAllRequests()
        debounce.addRequest({ onFilterChanged() }, DEBOUNCE_MS)
    }

    /**
     * 从设置里恢复上次勾选的过滤字段。
     *
     * 存的是字段 id（不是枚举名），因此配置里出现过期的/被手改过的 id 时直接忽略即可；
     * 全部非法则回退默认全选，保证过滤栏永远可用。
     */
    private fun restoredFilterFields(): List<LogFilterField> {
        val saved = settings.filterFieldIds
        if (saved.isEmpty()) return LogFilterField.values().toList()
        val known = LogFilterField.values().filter { it.id in saved }
        return if (known.isEmpty()) LogFilterField.values().toList() else known
    }

    /**
     * 把界面选项写回设置。
     *
     * 只写内存里的 State，不做 IO —— 平台会在 IDE 关闭时统一持久化整个 State。
     */
    private fun persistOptions() {
        settings.filterFieldIds = enabledFields.map { it.id }
        settings.dateRangeEnabled = dateRangeEnabled
        settings.useRegex = useRegex
        settings.caseSensitive = caseSensitive
    }

    private fun updateByButton() {
        byButton.text = EnhanceMergeBundle.message("filter.by.toggle")
        val active = (
            enabledFields.map { EnhanceMergeBundle.message("filter.field.${it.id}") } +
                (if (dateRangeEnabled) listOf(EnhanceMergeBundle.message("filter.field.dateRange")) else emptyList())
            ).joinToString(", ")
        byButton.toolTipText = EnhanceMergeBundle.message("filter.by.active", active)
    }

    private fun syncDateVisibility() {
        datePanel.isVisible = dateRangeEnabled
    }

    private fun showByPopup(anchor: JButton) {
        val menu = JPopupMenu()
        for (field in LogFilterField.values().toList()) {
            val item = JBCheckBox(EnhanceMergeBundle.message("filter.field.${field.id}"), enabledFields.contains(field))
            item.addActionListener {
                if (item.isSelected) enabledFields.add(field) else enabledFields.remove(field)
                updateByButton()
                onInputChanged()
            }
            menu.add(item)
        }
        val dateRangeItem = JBCheckBox(EnhanceMergeBundle.message("filter.field.dateRange"), dateRangeEnabled)
        dateRangeItem.addActionListener {
            dateRangeEnabled = dateRangeItem.isSelected
            syncDateVisibility()
            updateByButton()
            onInputChanged()
        }
        menu.add(dateRangeItem)

        menu.addSeparator()

        val regexItem = JBCheckBox(EnhanceMergeBundle.message("filter.field.regex"), useRegex)
        regexItem.addActionListener {
            useRegex = regexItem.isSelected
            onInputChanged()
        }
        menu.add(regexItem)

        val caseItem = JBCheckBox(EnhanceMergeBundle.message("filter.field.caseSensitive"), caseSensitive)
        caseItem.addActionListener {
            caseSensitive = caseItem.isSelected
            onInputChanged()
        }
        menu.add(caseItem)

        menu.show(anchor, 0, anchor.height)
    }

    /** 由界面状态构建过滤条件（日期区间仅在 `dateRangeEnabled` 时生效）。 */
    fun buildFilter(): LogFilter {
        val fromD = if (dateRangeEnabled) toDate(fromPicker, false) else null
        val toD = if (dateRangeEnabled) toDate(toPicker, true) else null
        // Bug-ID 正则取自设置（T9）；用户改坏时回退默认值，避免整个过滤失效
        val bugId = settings.bugIdRegex ?: LogFilter.DEFAULT_BUG_ID_PATTERN
        return LogFilter(
            searchText = searchField.text,
            fields = LinkedHashSet(enabledFields),
            dateRangeEnabled = dateRangeEnabled,
            fromDate = fromD,
            toDate = toD,
            useRegex = useRegex,
            caseSensitive = caseSensitive,
            bugIdPattern = bugId,
        )
    }

    /** 用当前页日志的最早/最晚日期初始化起止日历（TortoiseSVN 行为；不触发重查）。 */
    fun initDateRange(min: Date?, max: Date?) {
        dateBounds = min to max
        suppressEvents = true
        setPickers(min, max)
        suppressEvents = false
    }

    /** 清空搜索文本并重置日期区间为当前页范围（保留 By 选择与大小写/正则开关）。 */
    fun reset() {
        suppressEvents = true
        searchField.text = ""
        setPickers(dateBounds.first, dateBounds.second)
        suppressEvents = false
        updateByButton()
        persistOptions()
        onFilterChanged()
    }

    /** 日期必填：边界缺失时用今天兜底，避免出现「不允许空日期」状态。 */
    private fun setPickers(min: Date?, max: Date?) {
        fromPicker.date = min?.let { toLocalDate(it) } ?: LocalDate.now()
        toPicker.date = max?.let { toLocalDate(it) } ?: LocalDate.now()
    }

    private fun toLocalDate(date: Date): LocalDate =
        date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

    /** LGoodDatePicker 的 LocalDate 转 java.util.Date（endOfDay=true 时取到当日 23:59:59）。 */
    private fun toDate(picker: DatePicker, endOfDay: Boolean): Date? {
        val ld = picker.date ?: return null
        val ldt = if (endOfDay) ld.atTime(LocalTime.MAX) else ld.atStartOfDay()
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant())
    }

    private companion object {
        /** 输入防抖间隔（毫秒）：连续敲字时只触发一次重查。 */
        const val DEBOUNCE_MS = 300

        /** 过滤栏内各控件之间的水平间距（经JBUI 缩放）。 */
        const val ITEM_HGAP = 6

        /** By 按钮与相邻控件的间距（经 JBUI 缩放）。 */
        const val BUTTON_HGAP = 4

        /**
         * 日期输入框的显示格式。
         *
         * 固定 ISO 而非跟随 locale：过滤栏里的日期文本要能被 [LogFilter] 的文本匹配命中，
         * 格式随语言漂移会让"照着框里输入"变得不可预测（TortoiseSVN 亦用固定格式）。
         */
        const val DATE_TEXT_PATTERN = "yyyy-MM-dd"

        /** 日期输入框最小宽度（经 JBUI 缩放）：容纳 `yyyy-MM-dd` 而不被裁剪。 */
        const val DATE_FIELD_MIN_WIDTH = 96
    }
}
