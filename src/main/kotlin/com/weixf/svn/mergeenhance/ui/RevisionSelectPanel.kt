package com.weixf.svn.mergeenhance.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
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
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogPageLoader
import com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings
import org.jetbrains.idea.svn.history.SvnChangeList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.idea.svn.integrate.MergeContext
import org.jetbrains.idea.svn.integrate.QuantitySelection
import org.jetbrains.idea.svn.mergeinfo.ListMergeStatus
import org.jetbrains.idea.svn.mergeinfo.MergeChecker
import org.jetbrains.idea.svn.mergeinfo.MergeCheckResult
import org.jetbrains.idea.svn.mergeinfo.OneShotMergeInfoHelper
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellRenderer

/**
 * 修订选择的**可复用面板**（第六轮新增）。
 *
 * 承载「版本选择」的**全部**逻辑与视图：搜索框、修订表格、工具栏四动作、`+N` 分页、
 * 合并状态（T8）、右栏变更树与 `[Already merged]` 标记。
 *
 * ## 为什么抽成面板（而非继续只放对话框里）
 *
 * 第六轮把「修订选择」做成 VCS 工具窗里的**常驻标签页**（`EnhanceMergePanel` 宿主本面板），
 * 与既有的对话框入口（`MergeFlow → RevisionSelectDialog`）复用同一份实现，避免复制粘贴约 800 行逻辑。
 * 因此本面板**不含任何 DialogWrapper 专属职责**（标题 / OK 按钮 / `getDimensionServiceKey`），
 * 那些仍留在 `RevisionSelectDialog` 里。
 *
 * ## 与对话框版本的差异（务必知悉）
 *
 * - 生命周期：本面板实现 [Disposable]，在 init 里 `Disposer.register(parentDisposable, this)`；
 *   所有子资源（搜索框防抖 Alarm、快捷键注册）直接注册到 `this`，随宿主（对话框或标签页）释放。
 * - 合并触发：本面板**不负责合并**。宿主（对话框在关闭后由 `MergeFlow` 读取 `selectedChangeLists()`；
 *   标签页在 `Merge Selected` 点击时读取）负责调 `MergeExecutor`。面板只通过
 *   [onSelectionChanged] 回传「是否有勾选」给宿主驱动合并按钮的可用态。
 * - `CommittedChangesBrowser` 在 2022.1 是 `JPanel`（非 `Disposable`、无 `dispose()`，javap 实证），
 *   因此与既有对话框一致**不显式释放**，仅随面板组件树销毁。
 *
 * ## 复用件（均为原版同构的 public API）
 *
 * - `TableView` + `ListTableModel` + 单列 `ColumnInfo`（原版 `FAKE_COLUMN`）
 * - `CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges`
 * - `QuantitySelection`（稀疏勾选语义）
 * - `OneShotMergeInfoHelper` / `MergeChecker` / `ListMergeStatus`（T8 合并状态）
 */
class RevisionSelectPanel(
    private val context: MergeContext,
    private val loader: LogPageLoader,
    initialItems: List<SvnChangeList>,
    parentDisposable: Disposable,
    private val onSelectionChanged: (Boolean) -> Unit,
) : Disposable {

    /**
     * 面板是否已释放（替代原对话框的 `DialogWrapper.isDisposed`）。
     *
     * 用 `@Volatile` 标志而非 `Disposer.isDisposed(...)`：后者已 `@Deprecated` +
     * `@ApiStatus.ScheduledForRemoval`（2022.1 即已标注，javap 实证）；本面板自己实现
     * [Disposable]、在 [dispose] 里置位即可，不依赖全局注册表反查。
     */
    @Volatile
    private var disposed = false

    private val isDisposed: Boolean get() = disposed

    /**
     * 勾选状态：稀疏表示，跨「加载更多」保留。
     * 按修订号（`Long`）而非行索引：翻页/过滤会改变行位置，修订号才是稳定标识。
     */
    private val wiseSelection = QuantitySelection<Long>(false)

    // ---- T8：合并状态（已合并 / 外来 / 刷新中），复用原版 MergeChecker + ListMergeStatus ----
    private val LOG = Logger.getInstance(RevisionSelectPanel::class.java)

    /** 插件设置（T9）：每页条数 / 是否计算合并状态。 */
    private val settings = EnhanceMergeSettings.getInstance()

    /**
     * 合并状态计算器（原版 `MergeChecker` 实现）。
     * **每个面板一个实例**：`OneShotMergeInfoHelper` 内部缓存 mergeinfo，跨分支复用会拿到过期缓存。
     */
    private val mergeChecker: MergeChecker = OneShotMergeInfoHelper(context)

    /** 修订号 → 状态。用 `ConcurrentHashMap`：后台线程写、EDT 读。 */
    private val mergeStatusMap = ConcurrentHashMap<Long, ListMergeStatus>()

    /**
     * mergeinfo 是否已就绪。`@Volatile`：由多个 `executeOnPooledThread` 任务读写。
     */
    @Volatile
    private var mergeCheckerPrepared = false

    /** [mergeChecker.prepare] 的互斥锁：保证 [mergeCheckerPrepared] 的"检查 + prepare"是原子的。 */
    private val mergeCheckerLock = Any()

    /** 当前选中修订中「已合并」的文件，用于右侧文件列表的 [Already merged] 标记（对齐原版）。 */
    private val alreadyMerged = HashSet<Change>()

    private val model: ListTableModel<SvnChangeList> =
        ListTableModel<SvnChangeList>(FAKE_COLUMN).apply { setItems(initialItems) }

    /** 已下载的**原始**修订全集（未过滤），跨「加载更多 / 改过滤条件 / 搜索」保留。 */
    private val loadedAll: MutableList<SvnChangeList> = CopyOnWriteArrayList(initialItems)

    /** `lateinit` 的 UI 组件：在 [buildComponent] 里创建。 */
    private lateinit var revisionsList: TableView<SvnChangeList>
    private lateinit var changesBrowser: CommittedChangesBrowser
    private lateinit var toolbar: ActionToolbar

    /** 「加载更多」进行中（防止重复触发；同时驱动工具栏动作的可用态）。 */
    private var loadingMore = false

    /** 表格上方的显式搜索框（第五轮唯一新增组件）。须在 `init` 之前声明。 */
    private val searchField = RevisionSearchField(this) { onFilterChanged() }

    /** 当前生效的过滤条件。`@Volatile`：EDT 写、后台线程读。 */
    @Volatile
    private var currentFilter: LogFilter = LogFilter()

    /** 当前加载任务的进度指示器：启动新任务前用它取消旧任务。 */
    private val loadIndicator = AtomicReference<ProgressIndicator?>(null)

    /** 取消正在跑的加载任务（若有）。 */
    private fun cancelLoadTask() {
        loadIndicator.getAndSet(null)?.cancel()
    }

    /**
     * 加载令牌：只接受最后一次加载任务的结果。
     * ⚠️ 当前无人递增（Refresh 已删），恒 0、守卫恒放行——`+N` 互斥实际靠 [loadIndicator] 取消完成。
     */
    private var reloadToken = 0

    /**
     * 过滤令牌：只用于丢弃过期的过滤结果。与 [reloadToken] 分开（改搜索词只重过滤，不牵连在途加载）。
     */
    private var filterToken = 0

    /** 动态「+N」动作。**必须在 `init` 之前声明**。 */
    private val loadHundredAction = createLoadMoreAction("action.load.hundred", LOAD_MORE_SMALL)

    private val loadFiveHundredAction = createLoadMoreAction("action.load.fiveHundred", LOAD_MORE_LARGE)

    /** 「+N」动作工厂（对齐原版 `ToBeMergedDialog.MoreXAction`）。 */
    private fun createLoadMoreAction(textKey: String, quantity: Int) = object : DumbAwareAction(
        EnhanceMergeBundle.message(textKey),
        EnhanceMergeBundle.message(textKey),
        null,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !loadingMore && !loader.allLoaded
            e.presentation.isVisible = !loadingMore && !loader.allLoaded
        }

        override fun actionPerformed(e: AnActionEvent) = loadMore(quantity)
    }

    /** 面板的根组件（`buildComponent` 只构建一次，init 里赋值）。 */
    val component: JComponent

    init {
        // 先把自己挂到宿主 disposable 上，再构建组件（组件内部会把子资源注册到 `this`）
        Disposer.register(parentDisposable, this)
        component = buildComponent()

        // 首屏过滤条件 = 空（无搜索词即不过滤）。
        currentFilter = LogFilter()

        // T8：按设置计算合并状态。关设置时完全不调 prepare()/checkList()。
        if (settings.computeMergeStatus) refreshMergeStatus(model.items)
    }

    override fun dispose() {
        // 子资源（搜索框 Alarm、快捷键注册）已注册到 `this`，由 Disposer 树随本面板一并释放
        if (disposed) return
        disposed = true
    }

    // ------------------------------------------------------------------ UI

    private fun buildComponent(): JComponent {
        val root = JPanel(BorderLayout())

        val listPanel = JPanel(BorderLayout())
        // 先建表格再建工具栏：工具栏需要把 revisionsList 作为 targetComponent
        val tableComponent = createRevisionTable()
        toolbar = createToolbar().apply { targetComponent = revisionsList }
        // 顶部两行：显式搜索框（第五轮唯一新增）+ 工具栏（原版四动作）
        val topPanel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(toolbar.component, BorderLayout.SOUTH)
        }
        listPanel.add(topPanel, BorderLayout.NORTH)
        listPanel.add(tableComponent, BorderLayout.CENTER)

        changesBrowser = CommittedChangesBrowser(context.project).apply {
            setChangeNodeDecorator(object : ChangeNodeDecorator {
                override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) = Unit

                override fun preDecorate(change: Change, renderer: ChangesBrowserNodeRenderer, showFlatten: Boolean) {
                    if (alreadyMerged.contains(change)) {
                        renderer.append(
                            " ${EnhanceMergeBundle.message("label.already.merged")} ",
                            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                        )
                    }
                }
            })
        }

        // 对齐原版：把 diff 动作快捷键注册到版本列表（在列表上按 Ctrl+D 查看所选修订的差异）
        changesBrowser.getDiffAction().registerCustomShortcutSet(revisionsList, this)

        val splitter = Splitter(false, 0.7f).apply {
            firstComponent = listPanel
            secondComponent = changesBrowser
            dividerWidth = JBUI.scale(SPLITTER_DIVIDER_WIDTH)
        }

        root.add(splitter, BorderLayout.CENTER)
        root.preferredSize = JBUI.size(1100, 620)
        return root
    }

    private fun createRevisionTable(): JComponent {
        revisionsList = TableView(model).apply {
            setExpandableItemsEnabled(false)
            setTableHeader(null)          // 无表头：单列整行渲染
            setShowGrid(false)           // 无网格线
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        }
        revisionsList.columnModel.getColumn(0).cellRenderer = OriginalCellRenderer()

        revisionsList.selectionModel.addListSelectionListener { onListSelectionChanged() }
        installCheckboxClick()
        installSpaceKeyToggle()
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
            loadHundredAction,
            loadFiveHundredAction,
        )
        return ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
    }

    /** 「加载更多」：继续向后翻页（后台执行，可取消），原始数据累积进 `loadedAll`。 */
    private fun loadMore(quantity: Int) {
        if (loadingMore || loader.allLoaded) return
        if (loadedAll.size >= MAX_LOADED_ALL) return
        cancelLoadTask()
        loadingMore = true
        refreshToolbar()
        val token = reloadToken
        val generation = loader.snapshot()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project,
            EnhanceMergeBundle.message("progress.loading.more"),
            true,
        ) {
            private val added = ArrayList<SvnChangeList>()
            private var myIndicator: ProgressIndicator? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                myIndicator = indicator
                loadIndicator.set(indicator)
                added.addAll(loader.loadNextPageRaw(quantity, generation))
            }

            override fun onSuccess() {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                onDataLoaded(added)
            }

            override fun onThrowable(error: Throwable) {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                if (error is ProcessCanceledException) return
                showLoadError(error)
            }

            override fun onFinished() {
                if (!loadIndicator.compareAndSet(myIndicator, null)) return
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                loadingMore = false
                refreshToolbar()
            }
        })
    }

    /** 一批新数据到手后的统一处理：并入全集 → 重过滤 → 重绘。 */
    private fun onDataLoaded(added: List<SvnChangeList>) {
        if (added.isNotEmpty()) {
            val existing = loadedAll.mapTo(HashSet()) { it.number }
            added.forEach { if (it.number !in existing) loadedAll.add(it) }
        }
        applyFilterToModel()
        refreshMergeStatus(added)
        refreshList()
    }

    private fun showLoadError(error: Throwable) {
        Messages.showErrorDialog(
            context.project,
            EnhanceMergeBundle.message(
                "error.load.revisions",
                error.message ?: error.javaClass.simpleName,
            ),
            EnhanceMergeBundle.message("title.enhance.merge"),
        )
    }

    private fun refreshToolbar() {
        if (::toolbar.isInitialized) toolbar.updateActionsImmediately()
    }

    /** 对内存中已下载的**原始全集**做客户端过滤并刷新表格（**不触发任何远端请求**）。过滤在后台线程执行。 */
    private fun applyFilterToModel() {
        val filter = currentFilter
        val token = filterToken
        val snapshot = loadedAll.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            val filtered = if (filter.hasSearchText) {
                snapshot.filter { filter.matches(it) }
            } else {
                snapshot
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (token != filterToken || isDisposed || context.project.isDisposed) return@invokeLater
                    model.setItems(filtered.toMutableList())
                    searchField.updateCount(filtered.size, snapshot.size)
                    updateStatus()
                },
                ModalityState.any(),
                context.project.disposed,
            )
        }
    }

    /** 过滤条件变化（防抖后回调）：只做客户端重过滤，零远端请求。 */
    private fun onFilterChanged() {
        currentFilter = LogFilter(searchField.currentText())
        filterToken++
        applyFilterToModel()
        updateStatus()
    }

    /** 点击行首复选框区域切换勾选（命中区从列左缘起算一个复选框宽，原版同构）。 */
    private fun installCheckboxClick() {
        val checkboxWidth = JCheckBox().preferredSize.width
        object : ClickListener() {
            override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
                val row = revisionsList.rowAtPoint(e.point)
                if (row < 0) return false
                val cell = revisionsList.getCellRect(row, 0, false)
                val hit = Rectangle(cell.x, cell.y, checkboxWidth, cell.height)
                if (!hit.contains(e.point)) return false
                toggleInclusion(model.getRowValue(row))
                revisionsList.repaint()
                return true
            }
        }.installOn(revisionsList)
    }

    /** 空格键切换当前选中行（对齐原版）。 */
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

    /** 已勾选的修订（稀疏语义）。 */
    fun selectedChangeLists(): List<SvnChangeList> {
        val selected = wiseSelection.selected
        val unselected = wiseSelection.unselected
        return if (wiseSelection.areAllSelected()) {
            model.items.filter { !unselected.contains(it.number) }
        } else {
            model.items.filter { selected.contains(it.number) }
        }
    }

    /** 选中行变化 → 刷新右栏变更文件列表。 */
    private fun onListSelectionChanged() {
        if (!::changesBrowser.isInitialized) return
        val chosen = revisionsList.selectedObjects
        alreadyMerged.clear()
        if (settings.computeMergeStatus) {
            for (list in chosen) {
                alreadyMerged.addAll(getAlreadyMergedPaths(list))
            }
        }
        changesBrowser.setChangesToDisplay(if (chosen.isEmpty()) emptyList() else collectChanges(chosen))
        updateStatus()
    }

    /** 收集变更列表（对齐原版 `collectChanges(changeLists, false)`）。 */
    private fun collectChanges(lists: List<SvnChangeList>): List<Change> {
        val casted: List<CommittedChangeList> = lists
        return CommittedChangesTreeBrowser.collectChanges(casted, false)
    }

    /** 对齐原版 `ToBeMergedDialog.getAlreadyMergedPaths`：某修订中已合并的文件。 */
    private fun getAlreadyMergedPaths(list: SvnChangeList): List<Change> {
        if (!mergeCheckerPrepared) return emptyList()
        val notMerged = try {
            synchronized(mergeCheckerLock) { mergeChecker.getNotMergedPaths(list) }
        } catch (e: Exception) {
            LOG.warn("Failed to get not-merged paths for r${list.number}", e)
            return emptyList()
        } ?: return emptyList()
        if (notMerged.isEmpty()) return emptyList()
        return list.affectedPaths
            .filter { !notMerged.contains(it) }
            .mapNotNull { list.getByPath(it) }
    }

    /** 刷新派生状态：合并按钮可用性（经 [onSelectionChanged] 回传宿主）+ 工具栏动作可用性。 */
    private fun updateStatus() {
        onSelectionChanged(selectedChangeLists().isNotEmpty())
        refreshToolbar()
    }

    // ------------------------------------------------------------- renderer

    /** 单行渲染：`JCheckBox`(WEST) + 平台 `CommittedChangeListRenderer`(CENTER)。 */
    private inner class OriginalCellRenderer : TableCellRenderer {
        private val panel = JPanel(BorderLayout())
        private val checkBox = JCheckBox().apply {
            isEnabled = true
            isOpaque = false
        }

        private val changeListRenderer = CommittedChangeListRenderer(
            context.project,
            listOf(CommittedChangeListDecorator { list ->
                if (settings.computeMergeStatus) {
                    mergeStatusMap[list.number]?.icon ?: ListMergeStatus.REFRESHING.icon
                } else {
                    null
                }
            }),
        )

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            panel.removeAll()
            changeListRenderer.clear()

            if (value is SvnChangeList) {
                changeListRenderer.renderChangeList(table, value)

                val bg = if (isSelected) UIUtil.getTableSelectionBackground(true) else UIUtil.getTableBackground()
                val fg = if (isSelected) UIUtil.getTableSelectionForeground(true) else UIUtil.getTableForeground()

                changeListRenderer.background = bg
                changeListRenderer.foreground = fg
                checkBox.background = bg
                checkBox.foreground = fg
                panel.background = bg
                panel.foreground = fg

                checkBox.isSelected = wiseSelection.isSelected(value.number)
                panel.add(checkBox, BorderLayout.WEST)
                panel.add(changeListRenderer, BorderLayout.CENTER)
            }
            return panel
        }
    }

    // ------------------------------------------------------ merge status (T8)

    /** 对齐原版 `ToBeMergedDialog.refreshListStatus`：后台线程分批计算每行的合并状态。 */
    private fun refreshMergeStatus(lists: List<SvnChangeList>) {
        if (lists.isEmpty()) return
        if (isDisposed || context.project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            var preparedHere = false
            synchronized(mergeCheckerLock) {
                if (!mergeCheckerPrepared) {
                    try {
                        mergeChecker.prepare()
                        mergeCheckerPrepared = true
                        preparedHere = true
                    } catch (e: Exception) {
                        LOG.warn("Failed to prepare merge checker", e)
                        return@executeOnPooledThread
                    }
                }
            }
            if (preparedHere) {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!isDisposed && !context.project.isDisposed) onListSelectionChanged()
                    },
                    ModalityState.any(),
                    context.project.disposed,
                )
            }
            var cnt = REPAINT_BATCH_SIZE
            for (list in lists) {
                val status = try {
                    synchronized(mergeCheckerLock) { toListMergeStatus(mergeChecker.checkList(list)) }
                } catch (e: Exception) {
                    LOG.warn("Failed to check merge status for r${list.number}", e)
                    null
                }
                if (status != null) mergeStatusMap[list.number] = status
                if (--cnt <= 0) {
                    cnt = REPAINT_BATCH_SIZE
                    repaintTable()
                }
            }
            repaintTable()
        }
    }

    private fun repaintTable() {
        if (::revisionsList.isInitialized) {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!isDisposed && !context.project.isDisposed) revisionsList.repaint()
                },
                ModalityState.any(),
                context.project.disposed,
            )
        }
    }

    /** 与原版 `ToBeMergedDialog.toListMergeStatus` 完全一致。 */
    private fun toListMergeStatus(result: MergeCheckResult): ListMergeStatus = when (result) {
        MergeCheckResult.MERGED -> ListMergeStatus.MERGED
        MergeCheckResult.NOT_EXISTS -> ListMergeStatus.ALIEN
        else -> ListMergeStatus.REFRESHING
    }

    private companion object {
        /** 已加载原始修订的总量上限（跨「+N」累计）。 */
        const val MAX_LOADED_ALL = 20_000

        /** 「+N」两个动作的每次追加条数（对齐原版 `ToBeMergedDialog.MoreXAction`）。 */
        const val LOAD_MORE_SMALL = 100
        const val LOAD_MORE_LARGE = 500

        /** 左右分栏的分隔条宽度（经 JBUI 缩放）。 */
        const val SPLITTER_DIVIDER_WIDTH = 2

        /** 每算完多少行就重绘一次。 */
        const val REPAINT_BATCH_SIZE = 10

        /** 工具栏的 place 标识。 */
        const val TOOLBAR_PLACE = "SvnEnhanceMergeRevisions"

        /** 原版同构的「单列」占位列。 */
        val FAKE_COLUMN: ColumnInfo<SvnChangeList, SvnChangeList> =
            object : ColumnInfo<SvnChangeList, SvnChangeList>("") {
                override fun valueOf(item: SvnChangeList): SvnChangeList = item
            }
    }
}
