package com.weixf.svn.mergeenhance.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * 步骤 2 —— 合并方式选择（图3）。
 *
 * 与原版 `QuickMergeWayOptionsPanel` 的差异：**只保留一个合并方式** `Revision Select`（附描述）+ `Cancel`。
 * 原版的 Merge All / Select With Pre-Filter 不再提供——本插件的定位就是"手动逐版本勾选"，
 * 其余方式由原版「Merge From…」承担，避免两个入口提供重复能力。
 *
 * 视觉沿用原版：左对齐的按钮 + 按钮下方的多行说明 + 底部留白，
 * 不使用 DialogWrapper 的标准按钮行（避免出现第二组 OK/Cancel），ESC 显式绑定到取消。
 *
 * `createActions()` 返回空数组是"不显示标准按钮行"的开关——返回空比返回带
 * OK/Cancel 的 Action 更干净（后者还要处理它们的可见性）。
 */
class MergeVariantDialog(project: Project) : DialogWrapper(project, true) {

    /** 用户选择的结果。 */
    enum class Variant { REVISION_SELECT, CANCEL }

    private val revisionSelectButton = JButton(EnhanceMergeBundle.message("button.revision.select")).apply {
        addActionListener { close(REVISION_SELECT_CODE) }
    }

    init {
        title = EnhanceMergeBundle.message("dialog.title.select.merge.variant")
        init()

        // 无标准按钮行时 DialogWrapper 不会自动绑 ESC，这里显式补上
        // （WHEN_IN_FOCUSED_WINDOW：对话框内任意焦点下按 ESC 都生效，与模态框预期一致）
        rootPane.registerKeyboardAction(
            { doCancelAction() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
    }

    /**
     * 自绘按钮，不生成标准 OK/Cancel 行。
     * 返回空数组即"无动作"——DialogWrapper 的按钮区会整体消失。
     */
    override fun createActions(): Array<Action> = emptyArray()

    override fun getPreferredFocusedComponent(): JComponent = revisionSelectButton

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(4, 4, 0, 4)

        root.add(
            optionRow(
                revisionSelectButton,
                EnhanceMergeBundle.message("variant.revision.select.explanation"),
            )
        )

        val cancelButton = JButton(EnhanceMergeBundle.message("button.cancel")).apply {
            addActionListener { doCancelAction() }
        }
        root.add(optionRow(cancelButton, null))

        return root
    }

    /**
     * 一行选项：按钮在上（左对齐）、多行说明在下。
     *
     * @param button      选项按钮
     * @param description 说明文字（可为 null，通常含 `\n` 换行）
     */
    private fun optionRow(button: JButton, description: String?): JComponent {
        val row = JPanel(BorderLayout(0, 2))
        val buttonHolder = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        buttonHolder.add(button)
        row.add(buttonHolder, BorderLayout.NORTH)

        if (description != null) {
            // 用 <br> 承载换行：比 MultiLineLabelUI 更稳定，中英文断行都由文案里的 \n 控制
            val label = JLabel("<html>" + description.replace("\n", "<br>") + "</html>")
            label.border = JBUI.Borders.empty(0, 2, 10, 0)
            row.add(label, BorderLayout.CENTER)
        }
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    /** 显示对话框并返回用户选择。 */
    fun showDialog(): Variant {
        show()
        return if (exitCode == REVISION_SELECT_CODE) Variant.REVISION_SELECT else Variant.CANCEL
    }

    companion object {
        /** 自定义退出码：选中「Revision Select」。 */
        const val REVISION_SELECT_CODE = 100
    }
}
