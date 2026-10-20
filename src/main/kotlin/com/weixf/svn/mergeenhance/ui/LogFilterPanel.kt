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
 * - 搜索框右侧内嵌 **X 按钮**（对齐 TortoiseSVN 过滤条的清除图标）：**只**清空搜索词，
 *   不动日期区间与 By 选择（对齐 TSVN：它的"取消过滤"也不重置日期控件的值）。
 * - `Date range` 为独立开关，**默认开启**（对齐 TortoiseSVN 的 `LOGFILTER_ALL`）：
 *   勾选时才显示起止日历（TortoiseSVN `AdjustDateFilterVisibility`），
 *   默认值取**已加载数据的**最早/最晚日期（TSVN `LogDlg.cpp:1926-1933` 每次加载后重算），
 *   且日历的**可选范围**被锁在数据范围内（TSVN 的 `SetRange`），
 *   从交互上杜绝"日历里有这一天、日志里却没有"的矛盾。
 *   日期区间是**纯客户端**过滤（TortoiseSVN 亦然），**不会**改变向服务端查询的范围。
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

    /**
     * 「日期区间」开关。
     *
     * 初始值走 [EnhanceMergeSettings.dateRangeEnabledOnOpen]：默认 **true**（对齐 TortoiseSVN
     * 的 `LOGFILTER_ALL`，日期区间位默认就是开的），之后沿用用户上次的开关选择。
     *
     * 之所以"默认开着"不会造成错位：日期区间是**纯客户端**过滤，且起止默认值恒等于
     * 「已加载数据的日期范围」⇒ 首屏等价于"未过滤"。
     * 真正会改变服务端查询范围的只有版本区间（独立对话框，见 [RevRangeDialog]）。
     */
    private var dateRangeEnabled: Boolean = settings.dateRangeEnabledOnOpen()
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
        toolTipText = EnhanceMergeBundle.message("filter.clear.search")
        // 官方无障碍要求：纯图标按钮必须给出可读名称，否则屏幕阅读器只会念出 "button"
        accessibleContext.accessibleName = EnhanceMergeBundle.message("filter.clear.search")
        addActionListener { clearSearchText() }
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

    /**
     * 日期控件的**可选范围**（已加载数据的 min/max 日期，只扩不收）。
     *
     * 为什么要有范围（对齐 TortoiseSVN `LogDlg.cpp:1930` 的 `m_dateFrom.SetRange(&min,&max)`）：
     * 允许选到"数据之外"的日期，用户就会遇到"日历里明明包含这一天，日志里却没有"的矛盾——
     * 因为日期区间是**客户端**过滤，超出已加载范围的日子本来就没下载过。
     * 把可选范围锁在数据范围内，从交互上就消灭了这种自相矛盾（想看更早的数据请先「+100」/「全部显示」）。
     *
     * 只扩不收：加载更多之后范围随之放宽，但**不改动用户已选的区间**——
     * 否则每次「+100」都会把用户精心选的子区间重置成全范围。
     */
    private var boundsMin: LocalDate? = null
    private var boundsMax: LocalDate? = null

    /**
     * 用户是否**手动**改过起止日期。
     *
     * 决定加载更多之后日历的起止值要不要跟着数据范围走：
     * - 未改过（默认状态）→ 跟随数据范围（对齐 TortoiseSVN `LogDlg.cpp:1926-1927`
     *   每次加载后无条件用 min/max 覆盖 `m_tFrom`/`m_tTo`）。**这一步是必需的**：
     *   新加载的条目日期必然更早，若区间仍停在首屏范围，它们会被日期过滤**整批抹掉**，
     *   表现为"点了 +100 但什么都没多出来"。
     * - 改过 → 尊重用户选择，只把越界的值夹回可选范围内。
     */
    private var dateRangeTouched: Boolean = false

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

        val dateListener = DateChangeListener {
            // 记录"用户手动改过日期"：此后加载更多就不再自动把区间重设为数据范围
            // （否则用户精心选的子区间会被每次「+100」重置掉）。
            // 程序化赋值（syncDateRangeToData / applyDefaultDateRange）走 suppressEvents，不会置此标志。
            if (!suppressEvents) dateRangeTouched = true
            onInputChanged()
        }
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

    /**
     * 由界面状态构建过滤条件。
     *
     * 只有**纯客户端**的两个维度（搜索文本、日期区间）——它们都只影响"已加载数据的可见子集"，
     * 不影响向服务端请求的范围（版本区间维度已整体删除，见第四轮决策）。
     */
    /**
     * 汇总当前过滤栏状态。
     *
     * 不再接受版本区间参数：版本区间维度已整体删除（2026-10-20 第四轮）。
     * 剩下的两个维度（搜索文本、日期区间）**都是纯客户端过滤**，
     * 不影响"向服务端要哪一段"，因此调用方无需再关心游标范围。
     */
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

    /**
     * 用**已加载数据**的实际日期范围校准日历：放宽可选范围（[boundsMin]/[boundsMax]），
     * 并把起止值对齐到该范围。
     *
     * 对齐 TortoiseSVN `LogDlg.cpp:1926-1933`（每次加载完成后用数据的 min/max 校准日历
     * 并 `SetRange` 限制可选范围）。与 TSVN 的差别只有一处，且是**改进**：
     * TSVN 无条件覆盖用户选好的区间（`m_tFrom = m_logEntries.GetMinDate()`），
     * 我们只在**用户没手动改过**时跟随（见 [dateRangeTouched]），改过就保留用户的选择。
     *
     * @param min 已加载数据的最早提交时间；null 时不做任何事（还没加载到带日期的数据）。
     * @param max 已加载数据的最晚提交时间。
     */
    fun syncDateRangeToData(min: Date?, max: Date?) {
        val lo = min?.let { toLocalDate(it) } ?: return
        val hi = max?.let { toLocalDate(it) } ?: return
        val newMin = listOfNotNull(boundsMin, lo).minOrNull()!!
        val newMax = listOfNotNull(boundsMax, hi).maxOrNull()!!
        boundsMin = newMin
        boundsMax = newMax
        suppressEvents = true
        try {
            // 可选范围锁在已加载数据内（min>max 时返回 false，忽略即可）
            fromPicker.settings.setDateRangeLimits(newMin, newMax)
            toPicker.settings.setDateRangeLimits(newMin, newMax)
            if (dateRangeTouched) {
                // 用户自己选过：只把越界的值拉回范围内
                fromPicker.date = clampToBounds(fromPicker.date, newMin, newMax)
                toPicker.date = clampToBounds(toPicker.date, newMin, newMax)
            } else {
                // 未手动改过：跟随数据范围（否则新加载的更早条目会被日期过滤整批抹掉）
                fromPicker.date = newMin
                toPicker.date = newMax
            }
        } finally {
            suppressEvents = false
        }
    }

    /**
     * 应用设置里的「默认日期范围（天）」：打开开关并把区间填成最近 N 天。
     *
     * 与 [syncDateRangeToData] 的区别：后者用**数据实际范围**校准（不改用户选择），
     * 本方法对应"用户在设置里配了默认范围"，是主动施加的默认值，必须真正生效。
     * 因此这里同时把可选范围一并撑到覆盖该区间——否则默认区间早于已加载数据时会被 veto 成空结果。
     *
     * 刻意**不**写回设置（不调 `persistOptions()`）：这是"每次打开对话框施加一次"的默认值，
     * 不该污染"上次手动选择"的偏好。由于日期区间现在默认就是开的（对齐 TortoiseSVN），
     * 这里也不再需要"强制打开开关"来避免设置项静默失效。
     */
    fun applyDefaultDateRange(from: Date, to: Date) {
        dateRangeEnabled = true
        // 这是用户显式配置的默认范围，**不能**再被"加载更多后跟随数据范围"覆盖
        dateRangeTouched = true
        syncDateVisibility()
        updateByButton()
        val lo = toLocalDate(from)
        val hi = toLocalDate(to)
        val newMin = listOfNotNull(boundsMin, lo).minOrNull()!!
        val newMax = listOfNotNull(boundsMax, hi).maxOrNull()!!
        boundsMin = newMin
        boundsMax = newMax
        suppressEvents = true
        try {
            fromPicker.settings.setDateRangeLimits(newMin, newMax)
            toPicker.settings.setDateRangeLimits(newMin, newMax)
            fromPicker.date = lo.coerceIn(newMin, newMax)
            toPicker.date = hi.coerceIn(newMin, newMax)
        } finally {
            suppressEvents = false
        }
    }

    /**
     * 只取**日期维度**（含开关状态）的过滤条件。
     *
     * 供调用方在"加载完成 → 日历可能变了"这类场景下更新生效条件：
     * 用它 [LogFilter.copy] 现有条件，只替换日期部分，
     * 就不会把**尚未提交的搜索词**提前写进生效条件（否则会出现"列表突然按新词过滤了"）。
     */
    fun dateRangeFilter(): LogFilter = LogFilter(
        dateRangeEnabled = dateRangeEnabled,
        fromDate = if (dateRangeEnabled) toDate(fromPicker, false) else null,
        toDate = if (dateRangeEnabled) toDate(toPicker, true) else null,
    )

    /**
     * X 按钮：**只**清空搜索文本（保留 By 选择、日期区间、大小写/正则开关与版本区间）。
     *
     * 为什么不动日期：日期区间是独立维度，用户点 X 的意图是"清掉刚才搜的东西"，
     * 而不是"把日期也恢复成某个基准"。早期实现顺带把日历重置为"数据范围"，
     * 于是点一下 X 就连带改变日期过滤（甚至触发一次全量重拉），非常反直觉。
     */
    fun clearSearchText() {
        suppressEvents = true
        searchField.text = ""
        suppressEvents = false
        syncClearVisibility()
        persistOptions()
        onFilterChanged()
    }

    /** 值落在可选范围之外时（如可选范围被收窄）把它拉回范围内，避免控件进入 veto 状态。 */
    private fun clampToBounds(value: LocalDate?, lo: LocalDate, hi: LocalDate): LocalDate =
        (value ?: lo).coerceIn(lo, hi)

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
