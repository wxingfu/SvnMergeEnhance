package com.weixf.svn.mergeenhance.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 版本选择对话框表格上方的**显式搜索框**（第五轮唯一新增组件）。
 *
 * ## 定位
 *
 * 原版 `ToBeMergedDialog` 没有任何显式搜索入口，版本多、时间跨度大时只能滚动列表逐个寻找。
 * 本组件补上这一能力：常驻输入框 + 输入即过滤（防抖 300ms）+ 同行右侧计数标签。
 *
 * ## 交互约定
 *
 * - 输入 300ms 防抖后回调 [onSearch]；Esc 或点 ✕ 清空并立即恢复完整列表（**必须** consume Esc，
 *   否则模态对话框会把它当"取消"直接关窗）；
 * - ✕ 仅在有内容时出现（纯图标按钮已给 `accessibleContext.accessibleName`，官方无障碍要求）；
 * - 计数标签在有搜索词时显示「匹配 M / 已加载 N」，无搜索词时隐藏。
 *
 * ## 合规要点
 *
 * - 防抖用平台 [Alarm]（绑父 [Disposable]），**不用** `javax.swing.Timer`（官方禁用）；
 * - 像素值一律 `JBUI.scale(...)`；边框色走 `JBColor.border()`，背景走 `UIUtil.getTextFieldBackground()`；
 * - 用户可见文本全部来自 [EnhanceMergeBundle]。
 */
class RevisionSearchField(
    parentDisposable: Disposable,
    private val onSearch: (String) -> Unit,
) : JPanel(BorderLayout(JBUI.scale(ITEM_HGAP), 0)) {

    private val textField = JBTextField().apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0, 2, 0)
        emptyText.text = EnhanceMergeBundle.message("search.placeholder")
    }

    /** 搜索框内右侧的清除按钮（对齐 TortoiseSVN 过滤条的清除图标样式）。 */
    private val clearButton = JButton(AllIcons.Actions.Close).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        isRolloverEnabled = true
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(CLEAR_BUTTON_SIZE, CLEAR_BUTTON_SIZE)
        toolTipText = EnhanceMergeBundle.message("search.clear")
        // 官方无障碍要求：纯图标按钮必须给出可读名称
        accessibleContext.accessibleName = EnhanceMergeBundle.message("search.clear")
        addActionListener { clearSearch() }
    }

    /** 同行右侧的计数标签：有搜索词时显示「匹配 M / 已加载 N」。 */
    private val countLabel = JBLabel().apply {
        isVisible = false
        foreground = UIUtil.getInactiveTextColor()
    }

    /** 搜索框 = 无边框输入框 + 内嵌 ✕，整体套一层与 IDEA 文本框一致的细边框；宽度适度不拉满。 */
    private val searchBox = JPanel(BorderLayout(0, 0)).apply {
        background = UIUtil.getTextFieldBackground()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(0, 4, 0, 0),
        )
        add(textField, BorderLayout.CENTER)
        add(clearButton, BorderLayout.EAST)
    }

    /** 输入防抖（绑对话框 disposable，关闭即作废）。 */
    private val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    /**
     * 程序化赋值抑制标志：`clearSearch()` 清空文本时会再次触发 DocumentListener，
     * 置位期间不排防抖请求——否则每次清空都多一轮「空词全量过滤 + filterToken 自增」。
     */
    private var suppressEvents = false

    init {
        border = JBUI.Borders.empty(2, 0, 2, 0)

        // 子组件装完后按内容高度固定宽度（BorderLayout.WEST 不拉伸，天然保持适度宽度）。
        // 高度兜底：未 realize 的组件算不出 preferredSize 时（个别 LAF 返回 0）用最小行高，
        // 防止搜索框塌成零高。
        val boxHeight = searchBox.preferredSize.height.coerceAtLeast(JBUI.scale(MIN_SEARCH_HEIGHT))
        searchBox.preferredSize = JBUI.size(SEARCH_FIELD_WIDTH, boxHeight)

        add(searchBox, BorderLayout.WEST)
        add(countLabel, BorderLayout.CENTER)

        textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onInputChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onInputChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onInputChanged()
        })

        // Esc 清空：必须 consume，否则模态对话框按 Esc 会直接取消关窗
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    e.consume()
                    clearSearch()
                }
            }
        })

        syncClearVisibility()
    }

    private fun onInputChanged() {
        if (suppressEvents) return
        syncClearVisibility()
        // 防抖：先取消未触发的请求再排一个新的（Alarm 没有 Swing Timer 的 restart()）
        debounce.cancelAllRequests()
        debounce.addRequest({ onSearch(textField.text) }, DEBOUNCE_MS)
    }

    /** 当前搜索文本（过滤条件的唯一来源）。 */
    fun currentText(): String = textField.text

    /** 是否有搜索文本（决定计数标签显隐）。 */
    fun hasText(): Boolean = textField.text.isNotEmpty()

    /**
     * 清空搜索词并**立即**恢复完整列表（不等防抖）。
     * 清空后焦点留在输入框（spec R3.6）。
     */
    fun clearSearch() {
        debounce.cancelAllRequests()
        // 程序化清空：抑制 DocumentListener，免得 300ms 后又排一轮空词全量过滤
        suppressEvents = true
        try {
            textField.text = ""
        } finally {
            suppressEvents = false
        }
        syncClearVisibility()
        onSearch("")
        textField.requestFocusInWindow()
    }

    /** ✕ 只在输入框有内容时出现。 */
    private fun syncClearVisibility() {
        clearButton.isVisible = textField.text.isNotEmpty()
    }

    /**
     * 更新计数标签：[matched] 为当前过滤命中条数，[loaded] 为已加载全集条数。
     * 无搜索词时隐藏（避免无谓噪音）。
     */
    fun updateCount(matched: Int, loaded: Int) {
        if (!hasText()) {
            countLabel.isVisible = false
            return
        }
        countLabel.text = EnhanceMergeBundle.message("status.search.count", loaded.toString(), matched.toString())
        countLabel.isVisible = true
    }

    private companion object {
        /** 输入防抖间隔（毫秒）：连续敲字时只触发一次重过滤。 */
        const val DEBOUNCE_MS = 300

        /** 搜索框宽度（经 JBUI 缩放）：适度即可，不拉满整行。 */
        const val SEARCH_FIELD_WIDTH = 300

        /** 搜索框最小行高（经 JBUI 缩放）：preferredSize 取样失败时的兜底。 */
        const val MIN_SEARCH_HEIGHT = 24

        /** ✕ 按钮尺寸（经 JBUI 缩放）。 */
        const val CLEAR_BUTTON_SIZE = 20

        /** 搜索框与计数标签之间的水平间距（经 JBUI 缩放）。 */
        const val ITEM_HGAP = 6
    }
}
