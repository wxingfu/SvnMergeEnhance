# Tasks — Enhance Merge v2

> 顺序执行。包 `com.weixf.svn.mergeenhance`，根目录 `d:\WorkSpace\AIProjects\SubversionEnhance\`。
> 构建 JDK 11（`D:\softwares\Java\jdk-11.0.32.1`），`GRADLE_USER_HOME=G:\GradleRepository`，
> `intellij { version="2022.1"; type="IC"; plugins=listOf("Subversion") }`，不引入 SVNKit。
>
> **前置认知**：本插件截至 v1 为止**从未在真实 IDE 中运行验证过**（沙箱日志只有 `buildSearchableOptions`）。
> 因此 **T0 的第一要务是打通 `runIde` 验证闭环**，而不是继续堆功能。

---

## T0 打通验证 + 图1 入口（Spec R1）

- [x] T0.1 用真实 SVN 工作副本工程执行 `gradlew runIde`，确认沙箱启动、插件加载
      （判据：`idea.log` 出现 `Loaded custom plugins: SVN Enhance Merge`）
      ✅ 2026-10-18：`gradlew runIde --args=D:\WorkSpace\ideaProjects\drools_learn` 启动成功，
      日志含 `Loaded custom plugins: SVN Enhance Merge (1.0.0)` 且 `args: [D:\WorkSpace\ideaProjects\drools_learn]`
- [x] T0.2 新建 `wcinfo/WcInfoLinkInstaller.kt`，**删除**旧 `WcInfoPanelLinkInjector.kt`
- [x] T0.3 组件树定位：遍历 VCS 工具窗各 Content 的组件树，按类名
      `org.jetbrains.idea.svn.dialogs.WorkingCopyInfoPanel` 收集面板（2s 兜底定时器 + `ToolWindowManagerListener`）
- [~] T0.4 注入：定位文档内最后一个 `<table>` 元素，用 **`HTMLDocument.insertBeforeEnd(...)`**
      ❌ **已回退**：插入能成功，但 href 属性不在元素自身而挂在 `HTML.Tag.A` 嵌套集（且挂在 content 叶子）上，
      属性检测失配 → 2s 兜底定时器重复插入，界面出现 **4 个** Enhance Merge。
      已改用 T1 组件方案（见 plan §5.1「实测结论」）。
- [x] T0.5 幂等与自愈：`hasOurLink(doc)` 按 href 属性检测；挂 `DocumentListener` 应对
      `CopiesPanel.updateList()` 每次刷新重建面板
- [x] T0.6 可观测性：成功打 `LOG.info`，失败打 `LOG.warn`（含异常堆栈）
- [x] T0.7 点击处理：反射取 `getInfo()` → `WCInfo` → `rootInfo.virtualFile`，
      合成 `AnActionEvent` 调用 `EnhanceMergeAction`
- [x] T0.8 `plugin.xml`：删除 `<actions>` 全部内容；把 `postStartupActivity` 移入 `<extensions>`
- [ ] T0.9 **验收**（部分完成）：
      - [x] 链接已注入 ✅ 日志：`WcInfoLinkInstaller - Injected Enhance Merge link into Subversion WC info panel: D:\WorkSpace\ideaProjects\drools_learn`（0 条 WARN）
      - [ ] 人工确认：图1 中每个工作副本的「Merge From…」下方**肉眼可见**「Enhance Merge…」
      - [ ] 刷新工作副本列表后链接仍在且不重复
      - [ ] 多工作副本项目点击第 2 个 → 目标正确（drools_learn 单 WC，需另备多 WC 用例）

## T1 图1 组件方案（Spec R1）——**已采用，替代 T0.4**

> T0.4 的 HTML 注入因 `href` 属性检测失配导致重复注入（4 个链接），已回退。
> 本任务由"条件性兜底"升级为**正式实现**。

- [x] T1.1 找到直接包含 `WorkingCopyInfoPanel` 的容器（原版 `CopiesPanel` 中是 `JBPanel(VerticalLayout(8))`）
- [x] T1.2 对每个面板，在其后 `container.add(LinkLabel(...), index + 1)`；倒序遍历避免索引漂移
- [x] T1.3 幂等判定：只看"下一个兄弟是否为自己的 `LinkLabel`"（客户端属性 `svn.customMerge.label`），不做任何属性/文本解析
- [x] T1.4 挂 `ContainerListener`（`componentAdded` 跳过自己的 label 以防递归）+ 工具窗弹出 + 2s 兜底定时器
- [x] T1.5 `LinkLabel` 用非弃用构造器 `LinkLabel(String, Icon, LinkListener)`，避免 `create()` 的 deprecation 警告
- [x] T1.6 校验日志：`Enhance Merge link ensured: panels=N, labels=N`
- [x] T1.7 **验收**：`panels=1, labels=1`；40+ 秒（20+ 次定时器）无新增，幂等成立

## T2 图2 分支选择（Spec R2）

- [x] T2.1 新建 `flow/MergeFlow.kt`：`start(project, wcInfo, anchor)`
- [x] T2.2 校验 `rootInfo.virtualFile.isValid()`，无效则提示中止
- [x] T2.3 调用 `SelectBranchPopup.showForBranchRoot(project, root, callback, 标题, anchor)`；
      锚点 = 面板链接本身（由 `LinkListener` 的 source 参数取得）
- [x] T2.4 回调内：`relative = configuration.getRelativeUrl(wcInfo.url)`；
      null → 明确错误提示并中止（**不**用 `notNullize`）
- [x] T2.5 `sourceUrl = branchUrl.appendPath(relative, false)`
- [x] T2.6 自身合并守卫：`SvnUtil.isAncestor(a,b) || isAncestor(b,a)` → 提示「Cannot merge from self」
- [x] T2.7 构造 `MergeContext(vcs, sourceUrl, wcInfo, url.tail, root)`
- [x] T2.8 `BranchSelectionDialog.kt` 已删除；`EnhanceMergeAction` 改为只解析 WCInfo 后委托 `MergeFlow`
- [x] T2.9 附带的本地化基建：`EnhanceMergeBundle` + `messages/EnhanceMergeBundle.properties`（**仅英文基包**），
      `plugin.xml` 注册 `<resource-bundle>`
- [x] T2.11 修复点击路径在 EDT 上执行 `svn info` 的问题：
      链接已持有精确 `WCInfo`，改用 `EnhanceMergeAction.performFor(project, wcInfo, anchor)` 直接进入流程，
      不再走 `SvnVcs.allWcInfos`（后者可能触发工作副本重新探测 → 平台断言
      `Synchronous execution on EDT`，且把本插件列为 blame）
- [x] T2.12 语言一致性：`EnhanceMergeBundle` 改为**语言包感知**解析（不再 `extends DynamicBundle`）。
      - `DynamicBundle.findLanguageBundle() != null` ⇒ 用 `DynamicBundle.getLocale()` 精确匹配；
      - 否则用 **`Locale.ROOT`** 取英文基包（实测：请求 `Locale.ENGLISH` 会被默认 locale `zh_CN` 回退成中文）；
      - 结果语言与目标语言不符时也退回基包；properties 用 UTF-8 解码。
      - 已实测：无语言包 → `bundleLocale=`（英文）；装中文包后 → `bundleLocale=zh_CN`。
      详见 plan §5.7 / spec R12
- [ ] T2.10 **验收（需人工点击）**：弹窗项与原版逐一致（trunk 带 `(trunk)`、branches、分隔线、
      Configure Branches…）；选分支后弹出 T3 占位提示且源 URL 正确；异常场景均有提示不崩溃
      > 注意：`drools_learn` 若未配置分支，弹窗只会显示 `Configure Branches…`（与原版行为一致），
      > 需先 Configure Branches 才能看到 trunk/branches

## T3 图3 合并方式（Spec R3）

- [x] T3.1 新建 `ui/MergeVariantDialog.kt`（`DialogWrapper`，标题 `Select Merge Variant`）
- [x] T3.2 仅两个按钮：`Revision Select`（+ 多行描述）、`Cancel`；
      说明文字用 `<html>…<br>…</html>` 承载换行（比 `MultiLineLabelUI` 稳定，`JLabel.ui` 是只读属性无法赋值）
- [x] T3.3 `createActions()` 返回空（不生成标准 OK/Cancel 行，避免出现第二组按钮）；
      默认焦点 = Revision Select；ESC 显式绑定 `doCancelAction()`
- [x] T3.4 删除 `MergeMethodSelectionDialog.kt`
- [ ] T3.5 **验收（需人工点击）**：标题/按钮/描述正确；点 Revision Select 后弹出 T4 占位提示；
      点 Cancel 或按 ESC 无副作用结束

## T4 图4 列表骨架（Spec R5 / R8）

- [x] T4.1 新建 `ui/RevisionSelectDialog.kt`（删除 v1 同名文件）：
      `TableView(model)` + `ListTableModel(FAKE_COLUMN)` + `setTableHeader(null)` + `setShowGrid(false)` + 单列自定义 renderer
- [x] T4.2 行渲染器：`JCheckBox`(WEST) + `CommittedChangeListRenderer.renderChangeList(table, list)`(CENTER)，
      背景/前景跟随选中态；状态图标暂时返回 null（T8 接入）
- [x] T4.3 勾选交互：`ClickListener` 命中首列 checkbox 宽度区域切换 + `Space` 键对选中行切换
- [x] T4.4 勾选语义：`QuantitySelection<Long>(false)`；工具栏 全选 / 全不选
- [x] T4.5 右侧：`CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges`；
      `Splitter(false, 0.7f)`，`dividerWidth=2`
- [x] T4.6 状态栏：`createSouthAdditionalPanel()` + `status.revision.select`（显示条数 / 修订区间 / 已选 / 路径数）
- [x] T4.7 动作：`setOKButtonText("Merge Selected")`，空选禁用；`getDimensionServiceKey()`
- [x] T4.8 接线：`MergeFlow` 在图3 确认后用 `Task.Backgroundable` 调
      `MergeCalculatorTask.loadChangeLists(context, -1, 30)`，EDT 打开对话框；加载失败弹错误
- [x] T4.9 清理 v1 遗留：`RevisionSelectDialog`/`RevisionFilter`/`RevisionRangeFormatter`/
      `RevisionLogDataSource`/`SvnRevisionMergeExecutor` 及其 2 个单测全部删除
- [ ] T4.10 **验收（需人工点击）**：首屏 30 条已加载；复选框/全选/全不选/空格切换正常；
      右侧路径随选中变化；状态栏数字正确；未选时 Merge Selected 禁用

> 平台细节备忘：`DialogWrapper.createSouthAdditionalPanel()` 返回类型必须是 `JPanel`（不是 `JComponent`）；
> `ActionToolbar` 不设 `targetComponent` 会告警 "uses any focused component to update its actions"。

## T5 分页实时加载（Spec R4）

- [ ] T5.1 新建 `log/LogPageLoader.kt`：封装 `MergeCalculatorTask.loadChangeLists(ctx, cursor, pageSize)`（**游标单通道**）
- [ ] T5.2 游标：首屏 `cursor = -1`（恒从 HEAD 开始）；每页后 `cursor = 本页最后一条修订号`
      （第四轮：删除「版本 到 → `cursor = revTo + 1`」与「版本 从 → 客户端早停」）
- [ ] T5.3 「加载更多」按钮：追加；`allLoaded` 为真时禁用
- [ ] T5.4 后台执行：`Task.Backgroundable` + `ProgressIndicator`（可取消）；结果在 EDT 回填
- [ ] T5.5 删除 `RevisionLogDataSource.kt`、`RevisionItem`
- [ ] T5.6 **验收**：首屏（默认 100）在 1s 内返回；翻页游标正确不重复不遗漏；到底后按钮禁用

## T6 过滤栏（Spec R7 / R4）

> ⚠️ 第四轮（2026-10-20）**作废** T6.3 双模式分流与 T6.4 版本区间（用户决策：不需要版本区间；
> 日期区间改为纯客户端过滤）。下列已作废条目保留仅为历史可追溯，**勿再按其实现**。

- [x] T6.1 新建 `log/LogFilter.kt`（纯逻辑）：路径 / 作者 / Bug-ID / 日期区间 / 包含-排除
      （第四轮：删除「版本区间」字段；新增 `matchesText()` 与 `tokenize` 缓存）
- [x] T6.2 新建 `ui/LogFilterPanel.kt`：两行布局，字段顺序对齐图5；`Reset` 按钮
- [x] T6.3 ~~**双模式分流**（用户决策：先日期，后版本区间）~~ **已作废（第四轮）**
      - [x] T6.3.1 ~~日期任一已填 → **模式 A**~~ **已作废**：日期不再走服务端查询
      - [x] T6.3.2 ~~日期均空 → **模式 B**~~ **已作废**：统一为游标单通道
      - [x] T6.3.3 日期与版本不同时写入 `ChangeBrowserSettings` 同一边界 **已作废**（版本维度已删）
- [x] T6.4 ~~版本区间：上界走服务端起始游标 `revTo + 1`；下界走客户端 `number >= revFrom`~~ **已作废（第四轮）**
- [x] T6.5 防抖：每个输入框 `DocumentListener` + 300ms `Alarm` → **对已加载集合重过滤**（零远端请求）
      （第四轮改：不重置游标、不清空列表、不重查服务器）；用 `reloadToken` 防止旧结果覆盖新结果
- [x] T6.6 循环补页：单次翻页内最多 10 批原始页（保证单次翻页扫描量有限）
- [x] T6.7 客户端过滤顺序固定：日期区间 → 路径 → 作者 → Bug-ID → 包含 → 排除
      （第四轮：删除「版本下界/上界」）
- [x] T6.9 「重置过滤」按钮 + 空结果提示（`status.no.match`）
- [x] T6.11 修复 `ListTableModel` 只读列表崩溃：`setItems(emptyList())` → `setItems(mutableListOf())`
      （`addRows` 内部 `addAll` 会抛 `UnsupportedOperationException`，已在沙箱复现并修复）
- [ ] T6.8 ~~模式 A 大结果保护~~ **已作废**（模式 A 已删）
- [ ] T6.12 **验收（需人工）**：5 类条件单独/组合正确；改条件只重过滤、不重查服务器；
      过滤不丢勾选；空结果有提示

## T6' 第四轮：搜索只搜「已加载」+ 删除版本区间（**已实施**）

> 用户决策（2026-10-20）：① 搜索不再自动加载「不在当前页」的 Log（但不是不加载——只过滤已加载集合，
> 命中不足时提示用户手动加载更多）；② 版本区间功能不需要，整体删除。
> 详细分析见 `.codebuddy/rules/svn-enhance-merge.mdc` 第四轮节与 `plan.md` §5.4.4。

- [x] T6'.1 删除 `ui/RevRangeDialog.kt`；删除 `LogFilter.revFrom`/`revTo`/`hasRevisionRange()`；
      删除工具栏「版本区间…」动作与 `showRevRangeDialog()`；删除 `revrange.*` / `action.revision.range*` 文案
- [x] T6'.2 `LogPageLoader`：`loadNextPageRaw(size, generation)`（不再需要 filter）、`reset()`（无参，游标恒 -1）；
      删 `revFrom` 下界早停
- [x] T6'.3 删除 `runExtendSearch` 与 `SEARCH_MATCH_TARGET` / `MAX_SEARCH_ROUNDS` / `MAX_SEARCH_TOTAL_RAW`；
      `onFilterChanged` 只做重过滤
- [x] T6'.4 **过滤移到后台线程**（EDT 只 `setItems`），结果用 `reloadToken` 校验；`loadedAll` 改 `CopyOnWriteArrayList`
- [x] T6'.5 加载/搜索任务**互斥取消**（`ProgressIndicator.cancel()`），`onThrowable` 忽略 `ProcessCanceledException`
- [x] T6'.6 `loadedAll` 总量闸 `MAX_LOADED_ALL` + 状态栏触顶提示（`status.load.limit`）；
      搜索命中不足提示（`status.search.partial`）
- [x] T6'.7 `LogFilter`：`tokenize` 结果缓存（`by lazy`）；`highlightRanges` 用同一份 token（修 `!` 取反的高亮不一致）
- [x] T6'.8 单测同步：`LogFilterTest` 删版本区间用例、加 `matchesText` 用例；`LogPageLoaderTest` 删 `revTo+1`/`revFrom` 用例
- [x] T6'.9 文档同步：`README.md`（删版本区间能力、改搜索描述）、`CHANGELOG.md`（变更节）、
      规则文档第四轮节标记为「已实施」、本文档与 spec/checklist 勾选
- [x] T6'.10 **验收**：`gradlew test` / `buildPlugin`（字节码 major 55）/ `verifyOnIde -PideaVersion=2022.1` 全通过；
      ≥5 万条仓库连续输入 10 个字符 → svn 请求数 0、界面不冻结

## T7 合并执行（Spec R9）

- [ ] T7.1 新建 `merge/MergeExecutor.kt`：`FileDocumentManager.saveAllDocuments()` →
      `ChangeListsMergerFactory(selected, false, false, true)` →
      `SvnIntegrateChangesTask(vcs, WorkingCopyInfo(path, true), factory, sourceUrl, mergeTitle, false, branchName).queue()`
- [ ] T7.2 删除 `SvnRevisionMergeExecutor.kt`、`RevisionRangeFormatter.kt`
- [ ] T7.3 **验收**：选中 3 条不连续修订可正确合并；冲突进入 IDEA 原生解决器；
      Local Changes 自动刷新；取消无副作用

## T8 已合并状态图标（Spec R10，默认开启）

- [x] T8.1 合并状态计算：`RevisionSelectDialog` 内持有 `OneShotMergeInfoHelper(context)`（`MergeChecker`），
      后台 `prepare()` 后逐条 `checkList()` → `ListMergeStatus`（`MERGED` / `NOT_EXISTS→ALIEN` / 其余→`REFRESHING`），
      映射与原版 `ToBeMergedDialog.toListMergeStatus` 一致
- [x] T8.2 状态存 `ConcurrentHashMap<Long, ListMergeStatus>`；每 10 条 `invokeLater` 批量重绘
- [x] T8.3 渲染器图标：`mergeStatusMap[revision]?.icon` → `JLabel`，置于**复选框之后**（原版同构布局，
      保证 `installCheckboxClick()` 的命中区 `[cell.x, cell.x+checkboxWidth]` 不偏移）
- [x] T8.4 默认开启：对话框 `init` 即触发计算（无设置开关；T9 若提供开关则默认勾选）
- [x] T8.5 「加载更多」/「全部显示」/ 过滤重查后对新条目补算状态（重查前先 `clear()`）
- [x] T8.6 右栏 `[Already merged]`：`CommittedChangesBrowser.setChangeNodeDecorator(...)` +
      `getNotMergedPaths` 补集；`prepare()` 完成后补触发一次 `onListSelectionChanged()`
- [x] T8.7 快速搜索：`TableViewSpeedSearch`（2022.1 为单参构造且自动安装监听器；`getItemText` 参数非空、返回 `String?`）
- [x] T8.8 diff 快捷键：`changesBrowser.getDiffAction().registerCustomShortcutSet(revisionsList, null)`
- [ ] T8.9 **验收**：图标正确、分批刷新不卡顿；已合并项仍可勾选；右栏已合并文件带 `[Already merged]`

## T9 设置项与本地化（Spec R11 / R12）

- [x] T9.1 `settings/EnhanceMergeSettings.kt`：`@State(name="EnhanceMergeSettings")` +
      `PersistentStateComponent`，字段 `pageSize`（默认 100，范围 10–500）、
      `computeMergeStatus`（默认 **true**，随 T8 默认开启）、`bugIdPattern`
      （默认 `[A-Z][A-Z0-9]+-\d+`）、`defaultDateRangeDays`（默认 0）；
      注册为 **`applicationService`**（原计划为 projectService，改为应用级的原因见该文件注释：
      IDEA 的 VCS 设置惯例是应用级，且 `projectConfigurable` 的实例由平台无参构造、拿不到 Project）
- [x] T9.2 `settings/EnhanceMergeConfigurable.kt`：注册 `applicationConfigurable`，
      `parentId="vcs"`、`displayName` 由 `getDisplayName()` 走 bundle；
      `FormBuilder` 搭建四项表单（JSpinner / JBCheckBox / JBTextField），含 isModified/apply/reset
- [x] T9.3 `messages/EnhanceMergeBundle.properties` + `_zh_CN`；`plugin.xml` 有 `<resource-bundle>`
      > 语言包感知：未装语言包强制英文基包（避免"英文 IDE 弹中文"），装了语言包跟随平台语言。
- [x] T9.4 抽取全部自绘 UI 硬编码英文到 bundle：
      `title.enhance.merge`（MergeFlow.TITLE + 3 处对话框标题）、
      `link.text.enhance.merge`（面板链接文字）、
      `error.unexpected.changes.provider`（取日志 provider 不符，会展示给用户）；
      设置页 10 条文案（`settings.*`）。剩余硬编码仅 `EnhanceMergeBundle` 内部
      "bundle is missing" 兜底（bundle 本身损坏时无法本地化，属开发者错误信息）
- [x] T9.5 设置接入：`MergeFlow`/`RevisionSelectDialog` 首屏与补页用 `settings.pageSize`；
      `computeMergeStatus` 控制 T8 图标与 `[Already merged]`（关闭即完全不查 mergeinfo）；
      `defaultDateRangeDays>0` 时过滤栏默认填最近 N 天并立即生效；
      `LogFilterPanel.buildFilter()` 用 `settings.bugIdRegex`（无效正则回退默认值）
- [ ] T9.6 **验收**：`gradlew verifyPlugin` 通过（已通过）；沙箱中打开
      `Settings → Version Control → SVN Enhance Merge` 可见四项配置、改后持久化；
      改 pageSize 首屏条数变化；关闭合并状态开关后无图标且不查 mergeinfo

## T10 测试、文档与收尾

- [x] T10.1 `LogFilterTest`（**24 用例**，第四轮后数字会再变）：空条件、单/多词 AND、`-` 排除、`+` 或组、引号短语、`!` 取反、
      大小写、正则（含非法正则）、Bug-ID 字段（默认/自定义正则）、日期区间含边界、字段选择、高亮区间
      > 第三轮已删「版本区间各形态 / `parseRevisionRange` / `looksLikeRevisionRange`」相关用例
      > （搜索框不再解析版本区间）；第四轮再删「版本区间为硬边界」用例，并新增 `matchesText` 用例。
- [x] T10.2 `QuantitySelectionTest`（**9 用例**）：全不选起点增删清、全选起点排除与恢复、
      再全选、全清；并**固化原版语义** `areAllSelected()` 在 selectAll 起点恒为 `true`
      （`RevisionSelectDialog.selectedChangeLists()` 依赖它走排除集分支）
- [x] T10.3 `LogPageLoaderTest`（**15 用例**，第四轮后数字会再变）：游标单通道推进 / 空响应到底 /
      整页保守判定 / 第一批不足补批 / `MAX_BATCHES` 上限 / `reopenIfExhausted` /
      **世代号**（stale 世代号不发包）
      > 第三轮已删「日期模式一次拉取 + 内存分页」「`revTo+1` 边界」「`revFrom` 早停」用例；
      > 第四轮再删与版本区间相关的用例。
      > 为此把 `LogPageLoader` 的仓库访问抽成 `cursorSource` 一个可空注入点
      > （默认仍走原版 `MergeCalculatorTask`，行为不变）。
- [x] T10.4 `README.md` 按 v2 重写：新流程图（面板链接 → 分支弹窗 → 方式 → 修订选择 → 原版合并）、
      功能特性表、使用指南、技术实现（复用 API 表 + 两种日志模式 + 合并状态 + 勾选解耦）、
      项目结构（v2 类清单）、设置项、测试（46 用例明细）、回归验证步骤、兼容性说明
- [x] T10.5 `.codebuddy/rules/svn-enhance-merge.mdc` 入口描述已准确（唯一入口 = 面板注入链接，
      不注册 `<action>`；`EnhanceMergeAction` 仅为内部可调用类）；本次补充验证命令与参考源码位置
- [x] T10.6 保留参考源码（用户决策 2026-10-18）：svn4idea 最新版在仓库外
      `G:\gitDownload\svn4idea`（不参与构建、不提交），README「与原插件的关系」与 rules 均已说明；
      新建 `.gitignore`（忽略 `build/`、`.gradle/`、`.idea/`、沙箱与临时产物）
- [x] T10.7 `gradlew clean test buildPlugin verifyPlugin` 全通过（46 用例 0 失败，zip 2.1MB）；
      `gradlew verifyOnIde -PideaVersion=2022.1` → PASS（0 插件异常）
- [ ] T10.8 **人工验收**（沙箱已在运行）：按图1–图5 走通一次真实合并——
      分支弹窗 → 版本选择（状态图标/复选框点击/快速搜索/Ctrl+D）→ Merge Selected
      （本地改动提示 → 进度 → 冲突解决 → 更新信息树）；确认不依赖任何 fork 源码

---

## T11 官方插件规范合规整改（2026-10-18，全部已完成）

> 来源：一次对照 IntelliJ 官方插件开发规范（https://plugins.jetbrains.com/docs/intellij/）的审查整改。
> 需求条目见 spec 的 **R13 / R14 / R15**。

- [x] T11.1 **生命周期守卫**：`loadMore` / `loadAll` / `onFilterChanged` 的 `onSuccess`/`onThrowable`/`onFinished`
      与所有 `invokeLater` 加 `isDisposed || project.isDisposed`（守卫写在 lambda 内）
- [x] T11.2 **并发正确性**：`mergeCheckerPrepared` 加 `@Volatile` + `mergeCheckerLock` 互斥，
      保证 `prepare()` 只执行一次（用 `preparedHere` 保持"仅首次刷新右栏"语义）
- [x] T11.3 **按键消费**：`e.consume()` 移到 `keyPressed`（写在 `keyReleased` 不生效）
- [x] T11.4 **分发包瘦身**：`kotlin.stdlib.default.dependency=false`，不再打包 stdlib + annotations
      （zip 2.10MB → 434KB；官方 `verifyPluginConfiguration` 警告清零）
- [x] T11.5 **上架元数据**：`pluginIcon.svg` / `pluginIcon_dark.svg`（40×40，浅/深主题）、`<vendor>` email+url、
      `<change-notes>`、描述页第三方声明；并配置 `buildPlugin`（Zip 任务）把包级文件放到**插件目录根部**
- [x] T11.6 **第三方许可**：`THIRD-PARTY-LICENSES.txt`（LGoodDatePicker MIT 全文，上游 jar 未内置 LICENSE）
      + README「第三方许可」小节
- [x] T11.7 **泄漏修复**：`registerCustomShortcutSet` 传对话框 `disposable`；dispose 时移除注入的 `LinkLabel`
- [x] T11.8 **死代码清理**：删 `LogFilter.dateMatchTexts`、两份 properties 的 `filter.by.tooltip`；
      `EnhanceMergeAction` 去掉 `AnAction` 继承（从未注册到任何菜单）
- [x] T11.9 **命名对齐**：文件改名 `WcInfoLinkInjector.kt`，private 类改名 `LinkInstaller`
- [x] T11.10 **体验与无障碍**：过滤栏勾选/日期开关/正则/大小写持久化到 `EnhanceMergeSettings`；
      纯图标清除按钮加 `accessibleContext.accessibleName`
- [x] T11.11 **工具链**：配置 `runPluginVerifier`（支持 `-PverifierLocalIde=<已解压 ideaIC>` 免下载）
- [x] T11.12 **文档同步**：README / rules / plan / spec / tasks / checklist

**验证（2026-10-18 实跑）**

- [x] `gradlew test` → 46/46 通过
- [x] `gradlew verifyPlugin` → 通过；`verifyPluginConfiguration` → **零告警**
- [x] `gradlew buildPlugin` → zip 434KB，`pluginIcon.svg` / `pluginIcon_dark.svg` / `THIRD-PARTY-LICENSES.txt` 在插件目录根部
- [x] `gradlew runPluginVerifier -PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1` → **Compatible**（IC-221.5080.210）
- [x] `gradlew verifyOnIde -PideaVersion=2022.1` → **PASS**（issues 0）

**有意不做**

- `getDimensionServiceKey` 按项目隔离：带 `Project` 参数的重载仅 IDEA 2023.2+ 提供，与 `since-build=221` 冲突
- 入口的反射 + 2s 定时器注入 UI：待官方 `ToolWindowFactory`/扩展点调研结论 + 是否上架 Marketplace 的决策后再动

---

# Task Dependencies

- T0 是全局前置（验证闭环）
- T1 依赖 T0（仅在其失败时执行）
- T2 ← T0；T3 ← T2；T4 ← T3
- T5 ← T4（列表骨架）
- T6 ← T5（分页接口就绪后才谈过滤与补页）
- T7 ← T4（需选中结果）
- T8 ← T4（需列表渲染）
- T9 可与 T5–T8 并行
- T10 ← 全部

# 备注

- 所有 `svn4idea` 复用类集中在 `LogPageLoader`（取日志）与 `MergeExecutor`（执行合并）两个薄封装中，
  未来 IDEA 大版本 API 变化只需改这两处。
- 本插件**不写** `SvnConfiguration`，避免污染原版「Merge From」行为。
