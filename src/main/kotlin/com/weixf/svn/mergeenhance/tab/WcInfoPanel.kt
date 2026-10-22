package com.weixf.svn.mergeenhance.tab

import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.HtmlPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.NestedCopyType
import org.jetbrains.idea.svn.WorkingCopyFormat
import org.jetbrains.idea.svn.dialogs.WCInfo
import java.awt.Font
import javax.swing.event.HyperlinkEvent

/**
 * 单个工作副本的**信息块**（复刻原版 svn4idea `WorkingCopyInfoPanel`）。
 *
 * 用平台 [HtmlPanel]（JEditorPane + Swing HTML 引擎）渲染，与原版**同一套渲染技术**，
 * 因此列宽（跨列的 path 行会把宽度均分给三列 → 值列起点与原版一致）、长 URL 换行、
 * 斜体/加粗等细节都与原版一致——这是用 Swing 组件手拼表格无法达到的还原度。
 *
 * 行结构与原版 `UiBuilder` 一一对应：
 * ```
 * path（加粗，跨 3 列） / URL: / Format: / Depth: / [嵌套副本类型] / [Working copy root]
 *   / Cleanup（仅 1.7+） / Merge From…（加粗）
 * ```
 * 「Cleanup」「Merge From…」是表内真正的 HTML 链接（`href` + `description` 携带 action 名），
 * 点击由 [hyperlinkUpdate] 捕获。
 *
 * ⚠️ **数据必须通过 [setWcInfo] 注入而不是构造参数**：Kotlin 的属性初始化在 `HtmlPanel`
 * 构造之后，而 `HtmlPanel` 构造期间就会调用 [getBody]，用构造参数会导致读到未初始化的字段。
 */
internal class WcInfoPanel : HtmlPanel() {

    private var wcInfo: WCInfo? = null

    /** 点击「Merge From…」时回调（由 `EnhanceMergePanel` 绑定）。 */
    var onMergeFrom: (() -> Unit)? = null

    /** 点击「Cleanup」时回调（由 `EnhanceMergePanel` 绑定）。 */
    var onCleanup: (() -> Unit)? = null

    /** 注入工作副本信息并刷新 HTML（对齐原版 `setInfo(...)` + `update()`）。 */
    fun setWcInfo(info: WCInfo) {
        wcInfo = info
        update()
    }

    /**
     * 正文字体：用 UI 标签字体（而非 `HtmlPanel` 默认的提交信息等宽字体），与原版一致。
     * 不用 `StartupUiUtil`（该类在 2022.1 是 `com.intellij.util.ui`，新版本已搬到 `com.intellij.ide.util`，
     * 跨版本不稳定）；`JBFont.label()` 两端皆可用。
     */
    override fun getBodyFont(): Font = JBFont.label()

    override fun getBody(): String {
        val info = wcInfo ?: return ""
        return HtmlBuilder().apply {
            append(pathRow(info))
            append(urlRow(info))
            append(formatRow(info))
            append(depthRow(info))
            append(nestedTypeRow(info))
            append(isRootRow(info))
            append(cleanupRow(info))
            // 与原版一致：出错的工作副本不提供合并入口
            if (!info.hasError()) append(mergeFromRow())
        }.wrapWith("table").toString()
    }

    override fun hyperlinkUpdate(e: HyperlinkEvent) {
        if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return
        when (e.description) {
            ACTION_MERGE_FROM -> onMergeFrom?.invoke()
            ACTION_CLEANUP -> onCleanup?.invoke()
        }
    }

    // ------------------------------------------------------------------ 行

    private fun pathRow(info: WCInfo): HtmlChunk =
        tr().child(td(3).child(HtmlChunk.text(info.path).bold()))

    private fun urlRow(info: WCInfo): HtmlChunk {
        val row = tr().child(td().addText(EnhanceMergeBundle.message("wc.label.url")))
        return if (info.hasError()) {
            row.child(
                td(2)
                    .attr("color", ColorUtil.toHex(UIUtil.getErrorForeground()))
                    .addText(info.errorMessage)
            )
        } else {
            row.child(td(2).addText(info.url.toDecodedString()))
        }
    }

    private fun formatRow(info: WCInfo): HtmlChunk =
        tr()
            .child(td().addText(EnhanceMergeBundle.message("wc.label.format")))
            .child(td(2).addText(info.format.displayName))

    private fun depthRow(info: WCInfo): HtmlChunk =
        tr()
            .child(td().addText(EnhanceMergeBundle.message("wc.label.depth")))
            .child(td(2).addText(info.stickyDepth.displayName))

    private fun nestedTypeRow(info: WCInfo): HtmlChunk {
        val type = info.type
        if (NestedCopyType.external != type && NestedCopyType.switched != type) return HtmlChunk.empty()
        return tr().child(td(3).child(HtmlChunk.text(type.displayName).italic()))
    }

    private fun isRootRow(info: WCInfo): HtmlChunk {
        if (!info.isIsWcRoot) return HtmlChunk.empty()
        return tr().child(
            td(3).child(HtmlChunk.text(EnhanceMergeBundle.message("wc.label.working.copy.root")).italic())
        )
    }

    /**
     * 「Cleanup」行（对齐原版 `cleanupRow()`：仅 svn 1.7+ 工作副本支持该动作）。
     * 动作本身复用原版公开的 `CleanupWorker`，不自行实现 svn 清理逻辑。
     */
    private fun cleanupRow(info: WCInfo): HtmlChunk {
        if (!info.format.isOrGreater(WorkingCopyFormat.ONE_DOT_SEVEN)) return HtmlChunk.empty()
        return tr().child(td(3).child(actionLink(ACTION_CLEANUP, EnhanceMergeBundle.message("wc.cleanup"))))
    }

    private fun mergeFromRow(): HtmlChunk =
        tr().child(
            td(3).child(
                actionLink(ACTION_MERGE_FROM, EnhanceMergeBundle.message("wc.merge.from")).bold()
            )
        )

    /** 表内动作链接：`href` + `description` 同时携带 action 名（点击时由 [hyperlinkUpdate] 捕获）。 */
    private fun actionLink(action: String, text: String): HtmlChunk =
        HtmlChunk.tag("a")
            .attr("href", action)
            .attr("description", action)
            .addText(text)

    // ------------------------------------------------------------------ 工具

    private fun tr(): HtmlChunk.Element = HtmlChunk.tag("tr").attr("valign", "top")

    private fun td(): HtmlChunk.Element = HtmlChunk.tag("td")

    private fun td(colspan: Int): HtmlChunk.Element = HtmlChunk.tag("td").attr("colspan", colspan)

    companion object {
        /** 「Merge From…」链接携带的 action 名（同时写入 href 与 description）。 */
        const val ACTION_MERGE_FROM = "MERGE_FROM"

        /** 「Cleanup」链接携带的 action 名（与原版 `CopiesPanel.CLEANUP` 同名）。 */
        const val ACTION_CLEANUP = "CLEANUP"
    }
}
