package com.weixf.svn.mergeenhance.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProcessCanceledException
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
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
     * 已下载的**原始**修订全集（未过滤），跨「加载更多 / 改过滤条件 / 搜索」保留。
     *
     * 为什么单独保留原始集：改搜索词时，之前下载过、上一轮没命中的修订在新词下可能命中，
     * 因此过滤必须作用在"原始全集"上，而不是每改一次词就清空重拉（那样既慢又会丢失深历史）。
     * 这正是 TortoiseSVN 的做法：过滤只作用于内存里已加载（=缓存）的全集，远端仅在"扩充"时被调用。
     * 该集合只在**版本区间变化**（数据来源失效，必须重拉）或对话框重建时才清空；
     * 改搜索词 / 改日期区间都**不会**清空它（那两个维度是纯客户端过滤）。
     */
    private val loadedAll: MutableList<SvnChangeList> = CopyOnWriteArrayList(initialItems)

    /**
     * `lateinit` 的三个 UI 组件：它们在 `createCenterPanel()` 里才被创建，
     * 而 DialogWrapper 的构造流程要求字段先就绪；用 `::x.isInitialized` 兜住
     * "构造期/早期事件先于 UI 创建到达"的情况（例如 init 里的默认日期触发重查）。
     */
    private lateinit var revisionsList: TableView<SvnChangeList>
    private lateinit var changesBrowser: CommittedChangesBrowser
    private lateinit var toolbar: ActionToolbar

    /** 「加载更多」进行中（防止重复触发；同时驱动工具栏动作的可用态）。 */
    private var loadingMore = false

    /**
     * 表格上方的显式搜索框（第五轮唯一新增组件）。
     *
     * **必须在 `init {}` 之前声明**：`init {}` 会调用 DialogWrapper.init()
     * → createCenterPanel()，而 Kotlin 属性初始化按**声明顺序**执行——
     * 声明在 init 之后会导致 createCenterPanel 里用到未初始化字段。
     */
    private val searchField = RevisionSearchField(disposable) { onFilterChanged() }

    /**
     * 当前生效的过滤条件（改搜索词 / 加载更多时复用）。
     *
     * `@Volatile`：EDT 写、后台线程读（过滤任务在启动前抓到一份不可变快照，
     * 不能在后台线程里直接读这个字段）。
     */
    @Volatile
    private var currentFilter: LogFilter = LogFilter()

    /**
     * 当前加载任务的进度指示器：启动新任务前用它**取消**旧任务。
     *
     * ## 为什么必须有
     *
     * 令牌只丢弃**回调结果**，后台的加载循环却仍在跑：两个任务会**共享同一个 [loader]**，
     * 互相推进 `cursor`、互相抢 `allLoaded` → 拉到的页被瓜分、大量作废，
     * 而**服务器请求量是 N 倍**。取消后旧任务在下一轮 `checkCanceled()` 处退出
     * （抛 `ProcessCanceledException`，平台不会弹错误框），新任务独占 loader。
     */
    private val loadIndicator = AtomicReference<ProgressIndicator?>(null)

    /** 取消正在跑的加载任务（若有）。 */
    private fun cancelLoadTask() {
        loadIndicator.getAndSet(null)?.cancel()
    }

    /**
     * 加载令牌：只接受最后一次加载任务的结果，防止旧结果覆盖新结果。
     *
     * ⚠️ **当前无人递增它**（「重新整理」随 Refresh 按钮一并删除，首屏数据由
     * `MergeFlow` 构造参数带入），恒 0、守卫恒放行——`+N` 互斥实际靠
     * [loadIndicator] 的 `cancel()` 完成。保留是为了将来重增"清空重拉"入口时恢复语义。
     * **切勿**把它与 [filterToken] 混用：曾因过滤回调误与它比对，导致输入首个
     * 搜索词后所有过滤结果被丢弃（列表毫无变化），见 [applyFilterToModel] 注释。
     *
     * 为什么需要：加载是异步的（后台线程），`+N` 连续点击可能有多个任务在飞，
     * 先发的后回会把列表写成过期内容。任务在 `onSuccess/onThrowable/onFinished` 里比对
     * 自己的令牌，不一致就直接丢弃。
     *
     * ⚠️ 令牌**只挡回调写回**，挡不住 loader 内部状态被旧任务污染——那由
     * [LogPageLoader] 的世代号（[LogPageLoader.snapshot]）负责。双层缺一不可。
     */
    private var reloadToken = 0

    /**
     * 过滤令牌：只用于丢弃**过期的过滤结果**（后台线程算完回来时条件已变）。
     *
     * ## 为什么必须与 [reloadToken] 分开
     *
     * [reloadToken] 只在「重新整理」（清空全集 + 重置游标 + 重拉）时递增——
     * 那时在途的加载任务确实该作废（数据来源已失效）。
     * 而**改搜索词**只是重过滤：正在加载的那批数据依然是有效的原始日志，
     * 若此时递增 [reloadToken]，加载任务回来时会被判为过期而丢弃——
     * **数据没进已加载集合，游标却已推进**，后续翻页会整段跳过这批修订。
     *
     * 所以过滤用独立的 [filterToken]，加载用 [reloadToken]，两者互不干扰。
     */
    private var filterToken = 0

    /**
     * 动态「+N」动作（对齐原版 `ToBeMergedDialog.MoreXAction`）：已取完时隐藏。
     *
     * **必须在 `init {}` 之前声明**：`init {}` 会调用 DialogWrapper.init()
     * → createCenterPanel() → createToolbar()，而属性初始化按声明顺序执行。
     */
    private val loadHundredAction = createLoadMoreAction("action.load.hundred", LOAD_MORE_SMALL)

    private val loadFiveHundredAction = createLoadMoreAction("action.load.fiveHundred", LOAD_MORE_LARGE)

    /**
     * 「+N」动作工厂（对齐原版 `ToBeMergedDialog.MoreXAction`）。
     *
     * `isVisible = !loader.allLoaded`：原版在取完后会**隐藏**这两个按钮
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
            e.presentation.isVisible = !loadingMore && !loader.allLoaded
        }

        override fun actionPerformed(e: AnActionEvent) = loadMore(quantity)
    }

    init {
        // 标题用 MergeContext 自带的 mergeTitle（形如 "Merge from <branch>"，与原版一致）
        title = context.mergeTitle
        // 允许拉伸：DialogWrapper 默认不可调整大小，而本对话框要同时容纳长提交说明、
        // 深路径与右栏变更树，固定尺寸只能靠滚动看；尺寸仍按 getDimensionServiceKey 记忆。
        setResizable(true)
        setOKButtonText(EnhanceMergeBundle.message("button.merge.selected"))
        // DialogWrapper.init() 会同步调 createCenterPanel()；因此上面所有"供 UI 使用"的
        // 字段必须在 init 之前声明（见各字段的声明顺序注释）。
        init()

        // 首屏过滤条件 = 空（无搜索词即不过滤）。日期区间维度已随第五轮整体移除。
        currentFilter = LogFilter()

        // T8：按设置计算合并状态（已合并 / 外来 / 刷新中）。
        // 关设置时**完全不调** prepare()/checkList()：不查 mergeinfo、不显示图标，
        // 大仓库首屏速度与纯 svn log 一致（这是该设置项存在的意义）。
        if (settings.computeMergeStatus) refreshMergeStatus(model.items)
    }

    // ------------------------------------------------------------------ UI

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())

        val listPanel = JPanel(BorderLayout())
        // 先建表格再建工具栏：工具栏需要把 revisionsList 作为 targetComponent，
        // 否则平台会告警 "toolbar by default uses any focused component to update its actions"
        val tableComponent = createRevisionTable()
        toolbar = createToolbar().apply { targetComponent = revisionsList }
        // 顶部两行：显式搜索框（第五轮唯一新增）+ 工具栏（原版四动作）。
        // 搜索框在上、工具栏在下——输入框先入眼，操作按钮紧跟其下（用户决策 2026-10-21）。
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

        root.add(splitter, BorderLayout.CENTER)
        root.preferredSize = JBUI.size(1100, 620)
        return root
    }

    private fun createRevisionTable(): JComponent {
        revisionsList = TableView(model).apply {
            setExpandableItemsEnabled(false)
            setTableHeader(null)          // 无表头：单列整行渲染，表头纯属噪音（原版同）
            setShowGrid(false)           // 无网格线：与 CommittedChangesBrowser 的树视觉一致
            // 多选间隔：支持 Ctrl/Shift 点选多行（Ctrl+D 看差异、Space 批量勾选都依赖多选）
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        }
        // 单列 FAKE_COLUMN：整行交给平台 CommittedChangeListRenderer（与原版 MyListCellRenderer 同构）
        revisionsList.columnModel.getColumn(0).cellRenderer = OriginalCellRenderer()

        // 选中行变化 → 刷新右栏变更文件与 [Already merged] 标记（原版在 valueChanged 里做同一件事）
        revisionsList.selectionModel.addListSelectionListener { onListSelectionChanged() }
        installCheckboxClick()
        installSpaceKeyToggle()
        // 隐式 speed search 已随第五轮移除：与表格上方显式搜索框形成双轨，
        // 且其按键处理会与空格切换勾选竞争（SpeedSearchBase.processKeyEvent 对空白字符
        // showPopup + consume，已字节码核实）。显式搜索框是唯一搜索入口。
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
            // 工具栏动作集合与原版一致：Select all / Unselect all / +100 / +500
            loadHundredAction,
            loadFiveHundredAction,
        )
        return ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
    }

    /** 「加载更多」：继续向后翻页（后台执行，可取消），原始数据累积进 `loadedAll`。 */
    private fun loadMore(quantity: Int) {
        if (loadingMore || loader.allLoaded) return
        // 总量触顶：静默停止加载（状态栏已随第五轮移除，原版亦无相关提示）
        if (loadedAll.size >= MAX_LOADED_ALL) return
        // 停掉在途的加载任务：新任务独占 loader 的游标
        // （旧任务继续跑会抢游标 → 拉到的页被瓜分、请求量翻倍）
        cancelLoadTask()
        loadingMore = true
        refreshToolbar()
        // 后台任务只能用**启动时的快照**：generation 变了就说明期间发生过 reset
        // （旧任务继续跑会污染 loader 的游标/缓冲）
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
                // 对话框可能已在后台加载期间被关闭（用户直接点了 Merge Selected）：
                // 此时 model / revisionsList 已随窗口销毁，再重绘会抛异常。
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                onDataLoaded(added)
            }

            override fun onThrowable(error: Throwable) {
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                // 被新任务取消：静默丢弃，不弹错误框
                if (error is ProcessCanceledException) return
                showLoadError(error)
            }

            override fun onFinished() {
                // ⚠️ 只有「自己仍是当前在飞任务」时才复位 loadingMore。
                // 被新任务取消的旧任务也会走到这里，且它的 token 校验必然通过
                // （reloadToken 无人递增、恒 0）——若无条件复位，就会把**新任务**的
                // "加载中"状态提前清掉：+N 按钮重新可见可点 → 用户再点 → 新任务被
                // cancel → 若取消发生在 loadNextPageRaw 的多批补数中途，就是
                // 「游标已推进、该批数据被丢弃」的跳段缺陷（规则文档反复警告的那类）。
                // CAS 失败（loadIndicator 已是 null 或别人的）说明自己已被顶替，直接让位。
                if (!loadIndicator.compareAndSet(myIndicator, null)) return
                // 对话框已关闭时不再触碰工具栏（ActionToolbar 也随窗口销毁）。
                if (token != reloadToken || isDisposed || context.project.isDisposed) return
                loadingMore = false
                refreshToolbar()
            }
        })
    }

    /**
     * 一批新数据到手后的统一处理：并入全集 → 重过滤 → 重绘。
     *
     * 三种入口（首屏 / +100 / +500）共用。日期区间维度已随第五轮移除，
     * 因此这里不再需要"先校准日历再更新条件"的顺序约束。
     */
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

    /**
     * 对内存中已下载的**原始全集**做客户端过滤并刷新表格（**不触发任何远端请求**）。
     *
     * 对应 TortoiseSVN 的对内存缓存重过滤：改搜索词即时、免费（TortoiseSVN 的
     * `m_logEntries.Filter(...)` 同样只作用于内存集合）。
     *
     * ## 为什么过滤必须放在后台线程
     *
     * 全集可达两万条，每条的 [LogFilter.matches] 都要拼接字段文本（提交说明 + **所有**变更路径）、
     * 可能的 Bug-ID 正则、以及一次整串 `lowercase()`。这在 EDT 上做就是**界面冻结**：
     * 两万条约需数百毫秒到数秒，期间 IDEA 完全无响应——这正是"输入搜索词时 IDEA 卡住"的
     * 直接成因（后台的请求只是慢，**卡的是 EDT**）。
     *
     * 因此这里：EDT 只做一次快照（引用拷贝，极快）→ 后台过滤 → 回 EDT `setItems`
     * （`ListTableModel` 换列表 + 只渲染可见行，开销可忽略）。
     */
    private fun applyFilterToModel() {
        val filter = currentFilter
        // 用**过滤令牌**校验：只丢弃"条件已变"的过期过滤结果，不牵连在途加载
        val token = filterToken
        // 快照在 EDT 上取：只是引用拷贝（O(n) 但极快），避免后台遍历时集合被 EDT 改动
        val snapshot = loadedAll.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            val filtered = if (filter.hasSearchText) {
                snapshot.filter { filter.matches(it) }
            } else {
                snapshot
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    // ⚠️ 必须与 **filterToken** 比对（曾误写成 reloadToken，导致输入首个搜索词后
                    // 所有过滤结果都被判"过期"丢弃——列表毫无变化，2026-10-21 用户实测发现）。
                    // reloadToken 属于加载令牌，与过滤结果的有效性无关。
                    if (token != filterToken || isDisposed || context.project.isDisposed) return@invokeLater
                    model.setItems(filtered.toMutableList())
                    // 搜索框同行的计数标签：匹配 M / 已加载 N
                    searchField.updateCount(filtered.size, snapshot.size)
                    updateStatus()
                },
                ModalityState.any(),
                context.project.disposed,
            )
        }
    }

    /**
     * 过滤条件变化（防抖后回调）：**只做客户端重过滤，零远端请求**。
     *
     * ## 两个维度，两种处理（第四轮，对齐 TortoiseSVN）
     *
     * | 变化 | 影响"查什么"吗 | 处理 |
     * |---|---|---|
     * | 搜索词 | 否 | 只对已加载集合重过滤；**命中不足也不再自动请求**（状态栏提示可手动加载更多） |
     * | 日期区间 | **否**（纯客户端过滤） | 只对已加载集合重过滤，绝不重新查询 |
     *
     * 这正是 TortoiseSVN 的做法：搜索词与日期区间都只写进 `CLogDlgFilter`、
     * 只用于 `m_logEntries.Filter(...)` 内存过滤；只有 `OnClickedNext`(+100) /
     * `OnBnClickedShowAll` 才向服务端要更多，且**必须用户显式点击**。
     */
    private fun onFilterChanged() {
        // 搜索词唯一来源 = 表格上方显式搜索框；空词 = 不过滤
        currentFilter = LogFilter(searchField.currentText())
        // 过滤令牌递增：让**在途的过滤结果**（后台线程）作废，避免旧条件的结果覆盖新条件。
        // 注意**不动** reloadToken：那会让在途的加载任务被丢弃（数据丢失 + 游标跳过修订）
        filterToken++
        applyFilterToModel()
        updateStatus()
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
     *
     * 两处与并发相关的约束：
     * - 本方法在 **EDT** 调用，而 `myPartiallyMerged` 由后台 [refreshMergeStatus] 写入，
     *   故读必须落在同一个 [mergeCheckerLock] 上（逐行加锁后这里最多等一行的时间）；
     * - prepare 未就绪时直接返回空，不进锁 —— prepare 是一次 svn 调用，EDT 等它会冻 UI；
     *   就绪后 [refreshMergeStatus] 会补刷一次右栏，标记不会丢。
     */
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

    /**
     * 刷新派生状态：OK 按钮可用性 + 工具栏动作可用性。
     *
     * 底部自定义状态栏已随第五轮整体移除（原版亦无）；过滤命中数改由表格上方
     * 搜索框同行右侧的计数标签展示（见 [RevisionSearchField.updateCount]）。
     */
    private fun updateStatus() {
        isOKActionEnabled = selectedChangeLists().isNotEmpty()
        // 「加载更多」的可用状态依赖 allLoaded / loadingMore
        refreshToolbar()
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
     * 单行渲染：与原版 `ToBeMergedDialog.MyListCellRenderer` 同构——
     * `JCheckBox`(WEST) + 平台 `CommittedChangeListRenderer`(CENTER)。
     *
     * 复选框必须贴单元格最左：`installCheckboxClick()` 的命中区从 `cell.x` 起算一个复选框宽
     * （原版同构），右移会让"点到的位置"与"判定命中的位置"错位（T8 踩坑结论）。
     *
     * 状态图标经 [CommittedChangeListDecorator] provider 提供（与原版一致）：
     * 开启「计算已合并状态」时取 `mergeStatusMap`（缺省 REFRESHING），关闭时返回 null。
     */
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

    /**
     * 对齐原版 `ToBeMergedDialog.refreshListStatus`：后台线程分批计算每行的合并状态，
     * 每批完成后触发重绘。状态枚举与图标均复用原版 `ListMergeStatus`。
     *
     * ## 为什么 `checkList()` 也要进锁（原版没有这一步）
     *
     * `OneShotMergeInfoHelper` 内部 `myPartiallyMerged` 是**普通 HashMap**：
     * `checkList()` 会 `put`（后台线程）、`getNotMergedPaths()` 会 `get`（EDT，见
     * [getAlreadyMergedPaths]）。原版只有一个后台任务在算，本插件却可能同时有首屏 / +N /
     * 搜索扩充**多个**任务并发 `put`，再叠加 EDT 的并发 `get` —— 非同步 HashMap 并发写会丢数据、
     * 扩容期间并发读在极端情况下还会成环。故这里把每行的 `checkList` 放进同一个
     * [mergeCheckerLock]（**逐行加锁**，不锁整批：否则 EDT 侧读一个 map 要等几百行算完，卡 UI）。
     *
     * `prepare()` 仍独占该锁（它是 svn 调用，耗时），保证只执行一次且没有别的线程
     * 拿着半初始化的缓存去查。
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
                    } catch (e: Exception) {
                        // 不只是 VcsException：prepare 走的是 svn propget + 属性解析，
                        // 任何非受检异常逃出后台任务都会被平台记成插件内部错误并上报。
                        LOG.warn("Failed to prepare merge checker", e)
                        return@executeOnPooledThread
                    }
                }
            }
            if (preparedHere) {
                // mergeinfo 就绪后刷新一次右侧「已合并」标记（首次选择时 prepare 可能尚未完成）
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!isDisposed && !context.project.isDisposed) onListSelectionChanged()
                    },
                    // 官方要求：invokeLater 必须显式给 ModalityState（默认状态在模态框关闭动画期间
                    // 可能尚未恢复），过期条件在 lambda 内判定
                    ModalityState.any(),
                    context.project.disposed,
                )
            }
            var cnt = REPAINT_BATCH_SIZE
            for (list in lists) {
                // 逐行加锁：既消除并发 put，又让 EDT 侧的 get 最多等一行的时间
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
        // 守卫必须在 invokeLater 的 lambda 内：后台线程排队到 EDT 期间对话框可能已被关闭
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

    /** 与原版 `ToBeMergedDialog.toListMergeStatus` 完全一致：只区分 已合并 / 外来 / 其余(刷新中)。 */
    private fun toListMergeStatus(result: MergeCheckResult): ListMergeStatus = when (result) {
        MergeCheckResult.MERGED -> ListMergeStatus.MERGED
        MergeCheckResult.NOT_EXISTS -> ListMergeStatus.ALIEN
        else -> ListMergeStatus.REFRESHING
    }

    private companion object {
        /**
         * **已加载原始修订的总量上限**（跨「+N」累计）。
         *
         * 为什么需要总量闸：每条 `SvnChangeList` 都带着全部变更路径，
         * 而**每次过滤都要遍历全集**（O(n) 次字符串拼接），n 越大越慢、
         * 内存越多。触顶后不再向服务端拉取（静默停止，原版亦无相关提示）。
         */
        const val MAX_LOADED_ALL = 20_000

        /**
         * 「+N」两个动作的每次追加条数（对齐原版 `ToBeMergedDialog.MoreXAction`）。
         * 与设置里的「每页条数」无关：那两个按钮是固定步进。
         */
        const val LOAD_MORE_SMALL = 100
        const val LOAD_MORE_LARGE = 500

        /** 左右分栏的分隔条宽度（经 JBUI 缩放）。 */
        const val SPLITTER_DIVIDER_WIDTH = 2

        /** 每算完多少行就重绘一次（避免每行都 repaint）。 */
        const val REPAINT_BATCH_SIZE = 10

        /** 工具栏的 place 标识：ActionToolbar 用它区分"同组件上的多个工具栏"。 */
        const val TOOLBAR_PLACE = "SvnEnhanceMergeRevisions"

        /**
         * 原版同构的「单列」占位列：整行交给平台 renderer 绘制。
         * 列名为空字符串 —— 表头已隐藏，这个名字不会显示，但 ListTableModel 要求非 null。
         */
        val FAKE_COLUMN: ColumnInfo<SvnChangeList, SvnChangeList> =
            object : ColumnInfo<SvnChangeList, SvnChangeList>("") {
                override fun valueOf(item: SvnChangeList): SvnChangeList = item
            }
    }
}