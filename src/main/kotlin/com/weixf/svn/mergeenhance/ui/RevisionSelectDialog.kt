package com.weixf.svn.mergeenhance.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.committed.CommittedChangeListDecorator
import com.intellij.openapi.vcs.changes.committed.CommittedChangeListRenderer
import com.intellij.openapi.vcs.changes.committed.CommittedChangesBrowser
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList
import com.intellij.ui.ClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.TableViewSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogFilterField
import com.weixf.svn.mergeenhance.log.LogPageLoader
import com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings
import org.jetbrains.idea.svn.history.SvnChangeList
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.idea.svn.integrate.MergeContext
import org.jetbrains.idea.svn.integrate.QuantitySelection
import org.jetbrains.idea.svn.mergeinfo.ListMergeStatus
import org.jetbrains.idea.svn.mergeinfo.MergeChecker
import org.jetbrains.idea.svn.mergeinfo.MergeCheckResult
import org.jetbrains.idea.svn.mergeinfo.OneShotMergeInfoHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.VcsException
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Rectangle
import java.util.Date
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellRenderer

/**
 * 步骤 3 —— 修订选择（图4）。
 *
 * 布局与交互对齐原版 `ToBeMergedDialog`：
 * ```
 * ┌ Merge from <branch> ───────────────────────────────────────────┐
 * │ [过滤栏：By ▾ | 搜索框 ✕ | From 日期 | To 日期]                 │
 * │ [全选 / 全不选 / Show All / Refresh / +100 / +500]               │
 * │ ☑ r27 …  author  date  msg  │ 变更路径（CommittedChangesBrowser）│
 * │ ☑ r24 …                     │  src/…                            │
 * │ 状态栏：显示 N 个版本 rX–rY · 已选 M · K 个路径                  │
 * │                                    [Merge Selected] [Cancel]  │
 * └────────────────────────────────────────────────────────────────┘
 * ```
 *
 * 复用件（均为原版同构的 public API）：
 * - `TableView` + `ListTableModel` + 单列 `ColumnInfo`（原版 `FAKE_COLUMN`）
 * - `CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges`
 * - `QuantitySelection`（稀疏勾选语义：「全选后排除个别」）
 * - `OneShotMergeInfoHelper` / `MergeChecker` / `ListMergeStatus`（T8 合并状态）
 *
 * ## 为什么行渲染要自绘（而非直接用 `CommittedChangeListRenderer`）
 * 原版用 `CommittedChangeListRenderer` 渲染整行，但它只提供"一个 additional icon + 一段文本"，
 * 无法按命中片段分段着色（T6 的 TortoiseSVN 式高亮需要把 `r27`、作者、日期、说明分别染色）。
 * 因此这里自绘 `SimpleColoredComponent` 拼接各字段，仅在**图标与文本顺序**上保持原版布局。
 *
 * ## 为什么复选框必须固定在单元格最左
 * `installCheckboxClick()` 的命中区是 `Rectangle(cell.x, cell.y, checkboxWidth, cell.height)`
 * （从列左缘起算一个复选框宽度，与原版同构）。任何把复选框右移的布局（例如把状态图标放到
 * 它左边）都会让"点到的位置"与"判定命中的位置"错位，表现为鼠标点了没反应（T8 曾踩坑）。
 */
class RevisionSelectDialog(
    private val context: MergeContext,
    private val loader: LogPageLoader,
    initialItems: List<SvnChangeList>,
) : DialogWrapper(context.project, true) {

    /**
     * 勾选状态：稀疏表示，跨「加载更多」保留。
     *
     * 为什么按修订号（`Long`）而不是按行索引：翻页/过滤会改变行的位置与数量，
     * 只有修订号是稳定标识。`QuantitySelection(false)` 的 false = 从"全不选"起点开始，
     * 用户逐项勾选累积；配合 `areAllSelected()` 的稀疏语义支持"全选后排除个别"
     * （原版 `MySelectAll` 即 `setAll()`）。
     */
    private val wiseSelection = QuantitySelection<Long>(false)

    // ---- T8：合并状态（已合并 / 外来 / 刷新中），复用原版 MergeChecker + ListMergeStatus ----
    private val LOG = Logger.getInstance(RevisionSelectDialog::class.java)

    /** 插件设置（T9）：每页条数 / 是否计算合并状态 / 默认日期范围。 */
    private val settings = EnhanceMergeSettings.getInstance()
    /**
     * 合并状态计算器（原版 `MergeChecker` 实现）。
     * 这里**每个对话框一个实例**：`OneShotMergeInfoHelper` 内部缓存 mergeinfo，
     * 跨对话框复用会拿到过期缓存；但它也不是每次勾选都重新查（见 `mergeCheckerPrepared`）。
     */
    private val mergeChecker: MergeChecker = OneShotMergeInfoHelper(context)

    /** 修订号 → 状态。用 `ConcurrentHashMap`：后台线程写、EDT 读。 */
    private val mergeStatusMap = ConcurrentHashMap<Long, ListMergeStatus>()

    /**
     * mergeinfo 是否已就绪。
     *
     * 必须 `@Volatile`：该标志由**多个** `executeOnPooledThread` 任务读写（首屏、+N、Show All、
     * 改过滤条件各起一个后台任务）。没有 volatile 时，其它线程可能看不到本次写入，
     * 于是重复调用 `prepare()`，重复读一遍mergeinfo、并发初始化同一份缓存。
     */
    @Volatile
    private var mergeCheckerPrepared = false

    /**
     * [mergeChecker.prepare] 的互斥锁：保证 [mergeCheckerPrepared] 的"检查 + prepare"是原子的。
     *
     * 抢到锁的线程负责 prepare，其余线程在这里等待 prepare 完成后再去 checkList——
     * 而不是拿着半初始化的缓存继续查（后者会算出错误的已合并状态）。
     */
    private val mergeCheckerLock = Any()

    /**
     * 当前选中修订中「已合并」的文件，用于右侧文件列表的 [Already merged] 标记（对齐原版）。
     * 每次选中变化都整体重算（原版同样在 selectionListener 里 clear + 重新收集）。
     */
    private val alreadyMerged = HashSet<Change>()

    private val model: ListTableModel<SvnChangeList> =
        ListTableModel<SvnChangeList>(FAKE_COLUMN).apply { setItems(initialItems) }

    /**
     * `lateinit` 的三个 UI 组件：它们在 `createCenterPanel()` 里才被创建，
     * 而 DialogWrapper 的构造流程要求字段先就绪；用 `::x.isInitialized` 兜住
     * "构造期/早期事件先于 UI 创建到达"的情况（例如 init 里的默认日期触发重查）。
     */
    private lateinit var revisionsList: TableView<SvnChangeList>
    private lateinit var changesBrowser: CommittedChangesBrowser
    private lateinit var statusLabel: JBLabel
    private lateinit var toolbar: ActionToolbar

    /** 「加载更多」进行中（防止重复触发；同时驱动工具栏动作的可用态）。 */
    private var loadingMore = false

    /**
     * 过滤栏。**必须在 `init {}` 之前声明**：`init {}` 会调用 DialogWrapper.init()
     * → createCenterPanel()，而 Kotlin 属性初始化按**声明顺序**执行——
     * 声明在 init 之后会导致 createCenterPanel 里用到 null/未初始化字段。
     */
    private val filterPanel = LogFilterPanel(disposable) { onFilterChanged() }

    /** 当前生效的过滤条件（Load More / 全部显示时复用，避免重复构建）。 */
    private var currentFilter: LogFilter = LogFilter()

    /**
     * 重查令牌：只接受最后一次重查结果，防止旧结果覆盖新结果。
     *
     * 为什么需要：重查是异步的（后台 `loadNextPage`）。用户连续改过滤条件时，
     * 可能同时有多个后台任务在飞，先发的后回、后发的先回都会把列表写成过期内容。
     * 每个任务在 `onSuccess/onThrowable/onFinished` 里都比对自己的令牌，不一致就直接丢弃。
     */
    private var reloadToken = 0

    /**
     * 动态「+N」动作（对齐原版 `ToBeMergedDialog.MoreXAction`）：已取完时隐藏。
     *
     * **必须在 `init {}` 之前声明**：`init {}` 会调用 DialogWrapper.init()
     * → createCenterPanel() → createToolbar()，而属性初始化按声明顺序执行。
     */
    private val loadHundredAction = createLoadMoreAction("action.load.hundred", LOAD_MORE_SMALL)

    private val loadFiveHundredAction = createLoadMoreAction("action.load.fiveHundred", LOAD_MORE_LARGE)

    /** 「重新整理」：按当前过滤条件重查（对应 TortoiseSVN 同名按钮，已从底部移到顶部）。 */
    private val reloadAction = object : DumbAwareAction(
        EnhanceMergeBundle.message("action.reload"),
        EnhanceMergeBundle.message("action.reload"),
        AllIcons.Actions.Refresh,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !loadingMore
        }

        override fun actionPerformed(e: AnActionEvent) = onFilterChanged()
    }

    /**
     * 「全部显示」：循环加载剩余全部修订（已从底部移到顶部）。
     * 按需求**常显常可用**（仅加载中禁用）；即使此前判定已到底，点击也会重新探测一次。
     */
    private val showAllAction = object : DumbAwareAction(
        EnhanceMergeBundle.message("action.show.all"),
        EnhanceMergeBundle.message("action.show.all"),
        AllIcons.Actions.Download,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !loadingMore
        }

        override fun actionPerformed(e: AnActionEvent) = loadAll()
    }

    /**
     * 「+N」动作工厂（对齐原版 `ToBeMergedDialog.MoreXAction`）。
     *
     * `isVisible = !loader.allLoaded` 的原因：原版在取完后会**隐藏**这两个按钮
     * （避免用户点了没反应），因此这里是显隐而非置灰；同时仍保留 `isEnabled` 判断，
     * 让"已取完但按钮还没重绘"的窗口期内点击无害。
     */
    private fun createLoadMoreAction(textKey: String, quantity: Int) = object : DumbAwareAction(
        EnhanceMergeBundle.message(textKey),
        EnhanceMergeBundle.message(textKey),
        null,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !loadingMore && !loader.allLoaded
            e.presentation.isVisible = !loader.allLoaded
        }

        override fun actionPerformed(e: AnActionEvent) = loadMore(quantity)
    }

    init {
        // 标题用 MergeContext 自带的 mergeTitle（形如 "Merge from <branch>"，与原版一致）
        title = context.mergeTitle
        setOKButtonText(EnhanceMergeBundle.message("button.merge.selected"))
        // DialogWrapper.init() 会同步调 createCenterPanel()；因此上面所有"供 UI 使用"的
        // 字段必须在 init 之前声明（见各字段的声明顺序注释）。
        init()

        // 起止日历默认取当前页日志的最早/最晚日期（TortoiseSVN 的 OnClickedCancelFilter 行为：
        // 清空过滤时不是留空日期，而是回到"当前可见范围"）
        val dates = model.items.mapNotNull { it.commitDate }
        filterPanel.initDateRange(dates.minOrNull(), dates.maxOrNull())

        // T8：按设置计算合并状态（已合并 / 外来 / 刷新中）。
        // 关设置时**完全不调** prepare()/checkList()：不查 mergeinfo、不显示图标，
        // 大仓库首屏速度与纯 svn log 一致（这是该设置项存在的意义）。
        if (settings.computeMergeStatus) refreshMergeStatus(model.items)

        // T9：默认日期范围（>0 时过滤栏默认填「最近 N 天」并立即生效）。
        // 必须显式触发一次重查：initDateRange 只改 UI（suppressEvents），
        // 而首屏数据是调用方按"无日期"加载的，不重查会出现"界面显示最近 7 天、列表却是全量"。
        val defaultFrom = settings.defaultFromDate()
        if (defaultFrom != null) {
            filterPanel.initDateRange(defaultFrom, Date())
            onFilterChanged()
        }
    }

    // ------------------------------------------------------------------ UI

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())

        val listPanel = JPanel(BorderLayout())
        // 先建表格再建工具栏：工具栏需要把 revisionsList 作为 targetComponent，
        // 否则平台会告警 "toolbar by default uses any focused component to update its actions"
        val tableComponent = createRevisionTable()
        toolbar = createToolbar().apply { targetComponent = revisionsList }
        listPanel.add(toolbar.component, BorderLayout.NORTH)
        listPanel.add(tableComponent, BorderLayout.CENTER)

        changesBrowser = CommittedChangesBrowser(context.project).apply {
            setChangeNodeDecorator(object : ChangeNodeDecorator {
                override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) = Unit

                override fun preDecorate(change: Change, renderer: ChangesBrowserNodeRenderer, showFlatten: Boolean) {
                    if (alreadyMerged.contains(change)) {
                        // 文案自带方括号（见文案包 label.already.merged）——标点也归文案管，
                        // 避免在代码里拼 "[...]" 这种半翻译内容
                        renderer.append(
                            " ${EnhanceMergeBundle.message("label.already.merged")} ",
                            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                        )
                    }
                }
            })
        }

        // 对齐原版：把 diff 动作快捷键注册到版本列表（在列表上按 Ctrl+D 查看所选修订的差异）
        // 必须传对话框自身的 disposable（官方要求）：传 null 会让这个快捷键在对话框关闭后仍留在
        // 组件上，随 Swing 组件树泄漏。
        changesBrowser.getDiffAction().registerCustomShortcutSet(revisionsList, disposable)

        val splitter = Splitter(false, 0.7f).apply {
            firstComponent = listPanel
            secondComponent = changesBrowser
            dividerWidth = JBUI.scale(SPLITTER_DIVIDER_WIDTH)
        }

        root.add(filterPanel, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        root.preferredSize = JBUI.size(1100, 620)
        return root
    }

    private fun createRevisionTable(): JComponent {
        revisionsList = TableView(model).apply {
            setExpandableItemsEnabled(false)
            setTableHeader(null)          // 无表头：单列自绘整行，表头纯属噪音（原版同）
            setShowGrid(false)           // 无网格线：与 CommittedChangesBrowser 的树视觉一致
            rowHeight = JBUI.scale(ROW_HEIGHT)   // 比默认高一点，容纳复选框 + 图标 + 两行文本不拥挤
            // 多选间隔：支持 Ctrl/Shift 点选多行（Ctrl+D 看差异、Space 批量勾选都依赖多选）
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        }
        // 单列 FAKE_COLUMN：整行交给自定义 renderer（复选框 + 状态图标 + 自绘文本）
        revisionsList.columnModel.getColumn(0).cellRenderer = RevisionCellRenderer()

        // 选中行变化 → 刷新右栏变更文件与 [Already merged] 标记（原版在 valueChanged 里做同一件事）
        revisionsList.selectionModel.addListSelectionListener { onListSelectionChanged() }
        installCheckboxClick()
        installSpaceKeyToggle()
        // 快速搜索（对齐原版 ToBeMergedDialog：按提交说明匹配并跳转行；2022.1 构造器自动安装监听器）。
        // 防御：后续 IDE 版本若移除该构造器（新版平台已改为双参重载），降级为不装快速搜索，不影响对话框其余功能。
        try {
            object : TableViewSpeedSearch<SvnChangeList>(revisionsList) {
                override fun getItemText(element: SvnChangeList): String? = element.comment
            }
        } catch (e: LinkageError) {
            // 新版平台改成了双参构造器：旧签名在链接期就抛 NoSuchMethodError（属 LinkageError，
            // 不是 Exception）。只捕获 LinkageError，不吞真正的逻辑异常。
            LOG.warn("TableViewSpeedSearch unavailable on this IDE build; speed search disabled", e)
        }
        return ScrollPaneFactory.createScrollPane(revisionsList)
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            object : DumbAwareAction(
                EnhanceMergeBundle.message("action.select.all"),
                EnhanceMergeBundle.message("action.select.all"),
                AllIcons.Actions.Selectall,
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    wiseSelection.setAll()
                    refreshList()
                }
            },
            object : DumbAwareAction(
                EnhanceMergeBundle.message("action.unselect.all"),
                EnhanceMergeBundle.message("action.unselect.all"),
                AllIcons.Actions.Unselectall,
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    wiseSelection.clearAll()
                    refreshList()
                }
            },
            // 顺序对齐需求：Show All / Refresh 常在，+100 / +500 动态显隐并排在其后
            showAllAction,
            reloadAction,
            loadHundredAction,
            loadFiveHundredAction,
        )
        return ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
    }

    /** 「加载更多」：实时向仓库继续翻页（后台执行，可取消）。 */
    private fun loadMore(quantity: Int) {
        if (loadingMore || loader.allLoaded) return
        loadingMore = true
        refreshToolbar()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project,
            EnhanceMergeBundle.message("progress.loading.more"),
            true,
        ) {
            private var added: List<SvnChangeList> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                added = loader.loadNextPage(currentFilter, quantity)
            }

            override fun onSuccess() {
                // 对话框可能已在后台加载期间被关闭（用户直接点了 Merge Selected）：
                // 此时 model / revisionsList 已随窗口销毁，再 addRows + 重绘会抛异常。
                if (isDisposed || context.project.isDisposed) return
                if (added.isNotEmpty()) {
                    model.addRows(added)
                    revisionsList.revalidate()
                    revisionsList.repaint()
                    refreshMergeStatus(added)
                }
                updateStatus()
            }

            override fun onThrowable(error: Throwable) {
                if (isDisposed || context.project.isDisposed) return
                Messages.showErrorDialog(
                    context.project,
                    EnhanceMergeBundle.message(
                        "error.load.revisions",
                        error.message ?: error.javaClass.simpleName,
                    ),
                    EnhanceMergeBundle.message("title.enhance.merge"),
                )
            }

            override fun onFinished() {
                // 对话框已关闭时不再触碰工具栏（ActionToolbar 也随窗口销毁）。
                if (isDisposed || context.project.isDisposed) return
                loadingMore = false
                refreshToolbar()
            }
        })
    }

    /** 「全部显示」：循环加载剩余全部修订（后台，可取消）。 */
    private fun loadAll() {
        if (loadingMore) return
        // 允许对「已判定到底」的游标再发一次确认请求，避免按钮点了没反应
        loader.reopenIfExhausted()
        loadingMore = true
        refreshToolbar()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project,
            EnhanceMergeBundle.message("progress.loading.all"),
            true,
        ) {
            private var added = ArrayList<SvnChangeList>()

            override fun run(indicator: ProgressIndicator) {
                // 总条数未知（取决于仓库有多少匹配项），因此用"不确定进度 + 已加载计数"，
                // 而不是伪造 fraction（官方要求：无法确定总量时不要编造进度百分比）。
                indicator.isIndeterminate = true
                var guard = 0
                while (!loader.allLoaded && guard++ < MAX_LOAD_ALL_ROUNDS) {
                    indicator.checkCanceled()
                    indicator.text2 = EnhanceMergeBundle.message(
                        "progress.loading.all.count",
                        added.size.toString(),
                    )
                    val page = loader.loadNextPage(currentFilter, settings.pageSize)
                    if (page.isEmpty()) break
                    added.addAll(page)
                }
            }

            override fun onSuccess() {
                // 同 loadMore：对话框已关闭时不得再写model / 重绘。
                if (isDisposed || context.project.isDisposed) return
                if (added.isNotEmpty()) {
                    model.addRows(added)
                    revisionsList.revalidate()
                    revisionsList.repaint()
                    refreshMergeStatus(added)
                }
                updateStatus()
            }

            override fun onThrowable(error: Throwable) {
                if (isDisposed || context.project.isDisposed) return
                Messages.showErrorDialog(
                    context.project,
                    EnhanceMergeBundle.message(
                        "error.load.revisions",
                        error.message ?: error.javaClass.simpleName,
                    ),
                    EnhanceMergeBundle.message("title.enhance.merge"),
                )
            }

            override fun onFinished() {
                if (isDisposed || context.project.isDisposed) return
                loadingMore = false
                refreshToolbar()
            }
        })
    }

    private fun refreshToolbar() {
        if (::toolbar.isInitialized) toolbar.updateActionsImmediately()
    }

    /**
     * 过滤条件变化（防抖后回调）：**重置游标、清空列表、从第一页重新查询**。
     *
     * 为什么必须清空重查而不是"在已加载数据里过滤"：模式 B 的服务端分页是以
     * 「从 HEAD 往后 N 条」为单位的，旧游标位置在新条件下没有意义；日期模式同理
     * （服务端按日期裁剪后的集合完全不同）。只有重查才能保证"结果集 = 满足新条件的全集"。
     *
     * 勾选状态**不清空**：`QuantitySelection` 按修订号保存，与展示列表解耦，
     * 重查后同一修订号仍在集合里（原版同特性）。
     */
    private fun onFilterChanged() {
        val filter = filterPanel.buildFilter()
        currentFilter = filter
        val token = ++reloadToken

        loader.reset(filter)
        // 必须传**可变**列表：ListTableModel 内部持有该引用，后续 addRows 会调 addAll，
        // 传 emptyList() 会抛 UnsupportedOperationException（read-only collection）
        model.setItems(mutableListOf())
        // 旧状态对新查询无意义（修订号可能已不在结果集里），必须清掉，
        // 否则 renderer 会显示上一轮残留的"已合并/刷新中"图标
        mergeStatusMap.clear()
        loadingMore = true
        updateStatus()
        refreshToolbar()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project,
            EnhanceMergeBundle.message("progress.loading.revisions"),
            true,
        ) {
            private var loaded: List<SvnChangeList> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                loaded = loader.loadNextPage(filter, settings.pageSize)
            }

            override fun onSuccess() {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                if (loaded.isNotEmpty()) model.addRows(loaded)
                refreshMergeStatus(loaded)
                refreshList()
            }

            override fun onThrowable(error: Throwable) {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                Messages.showErrorDialog(
                    context.project,
                    EnhanceMergeBundle.message(
                        "error.load.revisions",
                        error.message ?: error.javaClass.simpleName,
                    ),
                    EnhanceMergeBundle.message("title.enhance.merge"),
                )
            }

            override fun onFinished() {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                loadingMore = false
                refreshToolbar()
                updateStatus()
            }
        })
    }

    /**
     * 点击行首复选框区域切换勾选（与 `ToBeMergedDialog` 同一手法：按列宽判定命中）。
     *
     * 为什么不用"给 JCheckBox 加 ActionListener"：JTable 的 renderer 组件**不接收鼠标事件**
     * （它只是被画出来的图形，不是真实子组件）。因此勾选必须由表格级的 ClickListener
     * 自己判定点击位置是否落在复选框矩形内。
     *
     * 命中区从 `cell.x`（列左缘）起算一个复选框宽度，所以 renderer 里复选框必须贴在
     * 单元格最左（`BorderLayout.WEST`）——这就是 T8 把图标放到复选框右侧的原因。
     */
    private fun installCheckboxClick() {
        val checkboxWidth = JCheckBox().preferredSize.width
        object : ClickListener() {
            override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
                val row = revisionsList.rowAtPoint(e.point)
                if (row < 0) return false
                val cell = revisionsList.getCellRect(row, 0, false)
                val hit = Rectangle(cell.x, cell.y, checkboxWidth, cell.height)
                // 未命中复选框区域时返回 false：让事件继续传播，表格正常执行"选中该行"
                // （选中会刷新右栏变更文件，是刻意保留的 TortoiseSVN 式交互）
                if (!hit.contains(e.point)) return false
                toggleInclusion(model.getRowValue(row))
                revisionsList.repaint()
                return true
            }
        }.installOn(revisionsList)
    }

    /**
     * 空格键切换当前选中行（对齐原版）。
     *
     * 对**所有**选中行生效（而非仅"当前行"）：配合 Ctrl/Shift 多选，可一次切换一批。
     *
     * `consume()` 放在 keyPressed 而不是 keyReleased：按键的默认行为（焦点移动、视图滚动）
     * 在 keyPressed 阶段就已处理完毕，keyReleased 里再 `consume()` 不起作用。
     * 真正的"切换勾选"仍留在 keyReleased —— 这样按住空格时不会因按键自动重复而连续翻转。
     */
    private fun installSpaceKeyToggle() {
        revisionsList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) e.consume()
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_SPACE) return
                if (isDisposed) return
                val selected = revisionsList.selectedObjects
                if (selected.isEmpty()) return
                selected.forEach { toggleInclusion(it) }
                revisionsList.repaint()
            }
        })
    }

    private fun toggleInclusion(list: SvnChangeList) {
        val number = list.number
        if (wiseSelection.isSelected(number)) wiseSelection.remove(number) else wiseSelection.add(number)
        updateStatus()
    }

    private fun refreshList() {
        revisionsList.revalidate()
        revisionsList.repaint()
        updateStatus()
    }

    // ------------------------------------------------------- selection state

    /**
     * 已勾选的修订（稀疏语义）。
     *
     * `areAllSelected()` 为 true 时走**排除集**分支：这是 QuantitySelection 的关键设计——
     * "全选"起点下它恒为 true，而 `selected` 集合是空的，只有 `unselected` 记录了用户
     * 取消勾选的个别项。若这里误用 `selected.contains(...)`，全选后会得到空列表。
     */
    fun selectedChangeLists(): List<SvnChangeList> {
        val selected = wiseSelection.selected
        val unselected = wiseSelection.unselected
        return if (wiseSelection.areAllSelected()) {
            model.items.filter { !unselected.contains(it.number) }
        } else {
            model.items.filter { selected.contains(it.number) }
        }
    }

    /**
     * 选中行变化 → 刷新右栏变更文件列表。
     *
     * `alreadyMerged` 必须**先清空再重算**：它描述的是"当前选中集合的已合并文件"，
     * 多选时是各修订的并集；不清会残留上一个选集的标记（原版同样 clear + 重新 addAll）。
     * 关设置时跳过 mergeinfo 查询：右栏只显示文件、不显示 [Already merged]。
     */
    private fun onListSelectionChanged() {
        if (!::changesBrowser.isInitialized) return
        val chosen = revisionsList.selectedObjects
        alreadyMerged.clear()
        // T8/T9：仅在开启「计算已合并状态」时才查 mergeinfo 并标记已合并文件
        if (settings.computeMergeStatus) {
            for (list in chosen) {
                alreadyMerged.addAll(getAlreadyMergedPaths(list))
            }
        }
        changesBrowser.setChangesToDisplay(if (chosen.isEmpty()) emptyList() else collectChanges(chosen))
        updateStatus()
    }

    /**
     * 收集变更列表（对齐原版 `collectChanges(changeLists, false)`）。
     * 第二个参数 `flatten=false`：保留目录层级展示（原版同样不拍平）。
     */
    private fun collectChanges(lists: List<SvnChangeList>): List<Change> {
        val casted: List<CommittedChangeList> = lists
        return CommittedChangesTreeBrowser.collectChanges(casted, false)
    }

    /**
     * 对齐原版 `ToBeMergedDialog.getAlreadyMergedPaths`：某修订中**已合并**的文件（not-merged 集合的补集）。
     * 结果存入 [alreadyMerged]，由装饰器在右侧文件行后追加 `[Already merged]`。
     */
    private fun getAlreadyMergedPaths(list: SvnChangeList): List<Change> {
        val notMerged = try {
            mergeChecker.getNotMergedPaths(list)
        } catch (e: Exception) {
            LOG.warn("Failed to get not-merged paths for r${list.number}", e)
            return emptyList()
        } ?: return emptyList()
        if (notMerged.isEmpty()) return emptyList()
        return list.affectedPaths
            .filter { !notMerged.contains(it) }
            .mapNotNull { list.getByPath(it) }
    }

    private fun updateStatus() {
        val selected = selectedChangeLists()
        val paths = if (selected.isEmpty()) 0 else collectChanges(selected).size
        val revisionRange = if (model.rowCount == 0) "-" else {
            val newest = model.getRowValue(0).number.toString()
            val oldest = model.getRowValue(model.rowCount - 1).number.toString()
            EnhanceMergeBundle.message("status.revision.range", newest, oldest)
        }
        if (::statusLabel.isInitialized) {
            statusLabel.text = if (model.rowCount == 0 && currentFilter.isFiltering()) {
                EnhanceMergeBundle.message("status.no.match")
            } else {
                EnhanceMergeBundle.message(
                    "status.revision.select",
                    model.rowCount.toString(),
                    revisionRange,
                    selected.size.toString(),
                    paths.toString(),
                )
            }
        }
        isOKActionEnabled = selected.isNotEmpty()
        // 「加载更多」的可用状态依赖 allLoaded / loadingMore
        refreshToolbar()
    }

    override fun createSouthAdditionalPanel(): JPanel {
        statusLabel = JBLabel("")
        statusLabel.border = JBUI.Borders.emptyTop(4)
        updateStatus()
        // 「重新整理 / 全部显示」已按需求移到顶部工具栏，底部只保留状态文字
        val panel = JPanel(BorderLayout())
        panel.add(statusLabel, BorderLayout.CENTER)
        return panel
    }

    /**
     * 尺寸记忆的key。
     *
     * 官方在 2023.2+ 提供了 `getDimensionServiceKey(project, useForRestore)` 重载（可按项目隔离），
     * 但本插件 `since-build=221`（IDEA 2022.1），2022.1 的 `DialogWrapper` 只有无参版本——
     * 改用新重载会导致 2022.1 上编译/运行失败，故此处沿用全局 key（多项目共用同一尺寸记忆）。
     */
    override fun getDimensionServiceKey(): String = "com.weixf.svn.mergeenhance.RevisionSelectDialog"

    // ------------------------------------------------------------- renderer

    /**
     * 单行渲染：**状态图标 + 复选框 + 自绘文本**（版本号 / 作者 / 日期 / 提交说明）。
     *
     * 自绘而非复用 `CommittedChangeListRenderer` 的原因：只有自绘才能按
     * [LogFilter.highlightRanges] 对**命中片段**做分段着色（TortoiseSVN 式高亮）。
     * 状态图标（已合并 / 外来 / 刷新中）来自原版 `ListMergeStatus.icon`，在 [refreshMergeStatus] 中计算。
     */
    private inner class RevisionCellRenderer : TableCellRenderer {
        private val panel = JPanel(BorderLayout())
        private val checkBox = JCheckBox().apply {
            isEnabled = true
            isOpaque = false
        }
        private val iconLabel = JLabel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(STATUS_ICON_GAP)
        }
        private val text = SimpleColoredComponent()
        /**
         * 图标 + 文本的组合面板，放在单元格 CENTER。
         * 为什么单独套一层：复选框已占住 WEST，图标必须紧跟其后但不能再占 WEST，
         * 否则复选框会被挤到列中间、点击命中区错位（T8 踩坑，见类注释）。
         */
        private val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(iconLabel, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            panel.removeAll()
            text.clear()

            if (value is SvnChangeList) {
                val bg = if (isSelected) UIUtil.getTableSelectionBackground(true) else UIUtil.getTableBackground()
                val fg = if (isSelected) UIUtil.getTableSelectionForeground(true) else UIUtil.getTableForeground()
                val normal = if (isSelected) SimpleTextAttributes.REGULAR_ATTRIBUTES
                else SimpleTextAttributes.GRAYED_ATTRIBUTES
                val highlight = if (isSelected) HIGHLIGHT_SELECTED else HIGHLIGHT

                text.background = bg
                text.foreground = fg
                text.myBorder = null
                checkBox.background = bg
                checkBox.foreground = fg
                panel.background = bg
                panel.foreground = fg

                // T8：状态图标（原版 ListMergeStatus.icon）；复选框固定在最左保证点击命中，图标紧随其后。
                // 设置中关闭「计算已合并状态」时不显示图标（mergeStatusMap 同时保持为空）。
                val status = if (settings.computeMergeStatus) {
                    mergeStatusMap[value.number] ?: ListMergeStatus.REFRESHING
                } else {
                    null
                }
                iconLabel.icon = status?.icon
                iconLabel.background = bg

                val filter = currentFilter
                fun field(field: LogFilterField, body: String) {
                    if (body.isEmpty()) return
                    val ranges =
                        if (filter.isFieldMatched(value, field)) filter.highlightRanges(body) else emptyList()
                    appendHighlighted(text, body, ranges, normal, highlight)
                }

                field(LogFilterField.REVISION, value.number.toString())
                text.append(FIELD_SEPARATOR)
                field(LogFilterField.AUTHOR, value.committerName)
                text.append(FIELD_SEPARATOR)
                field(
                    LogFilterField.DATE,
                    value.commitDate?.let { currentFilter.dateText(it) } ?: "",
                )
                text.append(FIELD_SEPARATOR)
                field(LogFilterField.MESSAGE, value.comment)

                checkBox.isSelected = wiseSelection.isSelected(value.number)
                panel.add(checkBox, BorderLayout.WEST)
                panel.add(center, BorderLayout.CENTER)
            }
            return panel
        }
    }

    /** 按命中区间分段着色；区间为空时整段用普通样式。 */
    private fun appendHighlighted(
        component: SimpleColoredComponent,
        body: String,
        ranges: List<IntRange>,
        normal: SimpleTextAttributes,
        highlight: SimpleTextAttributes,
    ) {
        if (ranges.isEmpty()) {
            component.append(body, normal)
            return
        }
        var pos = 0
        for (range in ranges) {
            if (range.first > pos) component.append(body.substring(pos, range.first), normal)
            val end = (range.last + 1).coerceAtMost(body.length)
            component.append(body.substring(range.first.coerceAtMost(body.length), end), highlight)
            pos = end
        }
        if (pos < body.length) component.append(body.substring(pos), normal)
    }

    // ------------------------------------------------------ merge status (T8)

    /**
     * 对齐原版 `ToBeMergedDialog.refreshListStatus`：后台线程分批计算每行的合并状态，
     * 每批完成后触发重绘。状态枚举与图标均复用原版 `ListMergeStatus`。
     */
    private fun refreshMergeStatus(lists: List<SvnChangeList>) {
        if (lists.isEmpty()) return
        // 对话框已关闭时不再发起 mergeinfo 查询（省掉一次仓库往返）
        if (isDisposed || context.project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // 「本次是否真正执行了 prepare」：只有真正 prepare 成功的那个线程才额外刷新一次
            // 右栏标记 —— 与原行为一致，避免每次「加载更多」都重算 alreadyMerged 造成右栏闪烁。
            var preparedHere = false
            synchronized(mergeCheckerLock) {
                if (!mergeCheckerPrepared) {
                    try {
                        mergeChecker.prepare()
                        mergeCheckerPrepared = true
                        preparedHere = true
                    } catch (e: VcsException) {
                        LOG.warn("Failed to prepare merge checker", e)
                        return@executeOnPooledThread
                    }
                }
            }
            if (preparedHere) {
                // mergeinfo 就绪后刷新一次右侧「已合并」标记（首次选择时 prepare 可能尚未完成）
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && !context.project.isDisposed) onListSelectionChanged()
                }
            }
            var cnt = REPAINT_BATCH_SIZE
            for (list in lists) {
                try {
                    mergeStatusMap[list.number] = toListMergeStatus(mergeChecker.checkList(list))
                } catch (e: VcsException) {
                    LOG.warn("Failed to check merge status for r${list.number}", e)
                }
                if (--cnt <= 0) {
                    cnt = REPAINT_BATCH_SIZE
                    repaintTable()
                }
            }
            repaintTable()
        }
    }

    private fun repaintTable() {
        // 守卫必须在 invokeLater 的 lambda 内：后台线程排队到 EDT 期间对话框可能已被关闭
        if (::revisionsList.isInitialized) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed && !context.project.isDisposed) revisionsList.repaint()
            }
        }
    }

    /** 与原版 `ToBeMergedDialog.toListMergeStatus` 完全一致：只区分 已合并 / 外来 / 其余(刷新中)。 */
    private fun toListMergeStatus(result: MergeCheckResult): ListMergeStatus = when (result) {
        MergeCheckResult.MERGED -> ListMergeStatus.MERGED
        MergeCheckResult.NOT_EXISTS -> ListMergeStatus.ALIEN
        else -> ListMergeStatus.REFRESHING
    }

    private companion object {
        /**
         * 「全部显示」最多循环多少批，防止极端情况下无休止加载。
         * 100 是安全上限而非功能上限：正常仓库几十批内必然到底（`loader.allLoaded`），
         * 只有"平台判定到底"失灵时才会撞到这个数。
         */
        const val MAX_LOAD_ALL_ROUNDS = 100

        /**
         * 「+N」两个动作的每次追加条数（对齐原版 `ToBeMergedDialog.MoreXAction`）。
         * 与设置里的「每页条数」无关：那两个按钮是固定步进。
         */
        const val LOAD_MORE_SMALL = 100
        const val LOAD_MORE_LARGE = 500

        /** 行高（经 JBUI 缩放）：比默认 16 高一点，容纳复选框 + 状态图标 + 文本。 */
        const val ROW_HEIGHT = 22

        /** 左右分栏的分隔条宽度（经 JBUI 缩放）。 */
        const val SPLITTER_DIVIDER_WIDTH = 2

        /** 状态图标与右侧文本的间距（经 JBUI 缩放）。 */
        const val STATUS_ICON_GAP = 4

        /** 行内各字段（版本号/作者/日期/说明）之间的分隔文本。 */
        const val FIELD_SEPARATOR = "  "

        /** 每算完多少行就重绘一次（避免每行都 repaint）。 */
        const val REPAINT_BATCH_SIZE = 10

        /** 工具栏的 place 标识：ActionToolbar 用它区分"同组件上的多个工具栏"。 */
        const val TOOLBAR_PLACE = "SvnEnhanceMergeRevisions"

        /**
         * 命中片段样式：加粗。
         * 选中态单独一份是为了将来可差异化（例如选中态用反色），目前与原版一致都是加粗。
         */
        val HIGHLIGHT: SimpleTextAttributes = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        val HIGHLIGHT_SELECTED: SimpleTextAttributes = SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES

        /**
         * 原版同构的「单列」占位列：整行交给自定义 renderer 绘制。
         * 列名为空字符串 —— 表头已隐藏，这个名字不会显示，但 ListTableModel 要求非 null。
         */
        val FAKE_COLUMN: ColumnInfo<SvnChangeList, SvnChangeList> =
            object : ColumnInfo<SvnChangeList, SvnChangeList>("") {
                override fun valueOf(item: SvnChangeList): SvnChangeList = item
            }
    }
}