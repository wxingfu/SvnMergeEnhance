# Tasks — 版本选择对话框原版化 + 显式搜索过滤（第五轮）

> **实施状态：✅ 已完成（2026-10-21）**。T1~T13 全部执行完毕，验证结果见文末。
> 保存分支：`save/pre-v3-original-align`（第四轮完成态）；本轮改动全部在 `main` 上提交。

> 需求编号见同目录 `spec.md`（R1~R11）；方案与调研留痕见
> `.codebuddy/plan/RevisionSelect-AlignOriginal-AddSearch-plan.md`。
>
> **环境基线**（不可偏离）：Gradle JDK 11（`D:/softwares/Java/jdk-11.0.32.1`）、
> `GRADLE_USER_HOME=G:\GradleRepository`、`intellij { version = "2022.1"; type = "IC" }` +
> 内置 Subversion、不引入 SVNKit / 不引入任何外部 Maven 坐标（本轮**净移除** LGoodDatePicker）、
> 产物字节码 major 55。
>
> **执行纪律**：
> 1. 每个任务完成立即跑 `gradlew test`（红了不进入下一个任务）；
> 2. 同文件的多处修改**串行**编辑（并行 edit 会互相覆盖，历史教训）；
> 3. 每完成一组任务更新本文档勾选；
> 4. 最终统一跑 §T12 全量验证，不在中间反复 buildPlugin。

---

## T1 基线与分支准备

- [ ] T1.1 记录当前基线：`gradlew clean test` 用例数（预期 60）、`buildPlugin` 产物大小
- [ ] T1.2 确认工作树干净（`git status`），记录 HEAD

## T2 行渲染回归原版（R2）

- [ ] T2.1 `RevisionSelectDialog.kt`：删除 `RevisionCellRenderer` 全类、`appendHighlighted`、
      `HIGHLIGHT` / `HIGHLIGHT_SELECTED`、`STATUS_ICON_GAP`、`FIELD_SEPARATOR`、`ROW_HEIGHT` 及相关 import
      （`SimpleTextAttributes`、`SimpleColoredComponent`、`JLabel` 等按需清理）
- [ ] T2.2 新增 `OriginalCellRenderer`（私有内部类，复刻原版 `MyListCellRenderer`）：
      `JPanel(BorderLayout)` + `JCheckBox`(WEST) + `CommittedChangeListRenderer(project) { provider }`(CENTER)；
      `renderChangeList(table, value)`；配色 `UIUtil.getTableSelection*` / `getTable*`
- [ ] T2.3 状态图标 provider：`if (settings.computeMergeStatus) mergeStatusMap[number] ?: ListMergeStatus.REFRESHING else null`
- [ ] T2.4 复选框命中区保持 `Rectangle(cell.x, cell.y, JCheckBox().preferredSize.width, cell.height)`
- [ ] T2.5 删除 `revisionsList.rowHeight = JBUI.scale(ROW_HEIGHT)`（平台默认行高）
- [ ] T2.6 `gradlew test` 绿

## T3 工具栏与 +N 行为对齐原版（R5）

- [ ] T3.1 删除 `showAllAction` / `reloadAction` / `loadAll()` / `reloadAll()` 及其 import
      （`AllIcons.Actions.Download` / `Refresh` 等）
- [ ] T3.2 删除 `refreshToolbar()` 中对已删动作的引用；`ActionToolbar` place 保持 `"SvnEnhanceMergeRevisions"`
- [ ] T3.3 `createLoadMoreAction` 显隐对齐原版：`update` 中 `isVisible = !loadingMore && !loader.allLoaded`；
      加载中在 `loadMore(quantity)` 内对「另一个 +N」做隐藏 / 禁用（原版语义：加载中隐藏 +500、禁用 +100/+500）
- [ ] T3.4 确认 `createToolbar()` 动作集合恰好 4 个且顺序 = Select all / Unselect all / +100 / +500
- [ ] T3.5 `gradlew test` 绿

## T4 搜索框组件（R3）

- [ ] T4.1 新建 `ui/RevisionSearchField.kt`：
      - `class RevisionSearchField(parentDisposable: Disposable, onSearch: (String) -> Unit) : JPanel()`
      - `JBTextField`（无边框）+ 内嵌 ✕（`AllIcons.Actions.Close`，有内容才可见，`accessibleContext.accessibleName`）
      - 外框：`UIUtil.getTextFieldBackground()` + `JBUI.Borders.customLine(JBColor.border(), 1)`
      - `preferredSize = JBUI.size(SEARCH_FIELD_WIDTH, preferredSize.height)`，`SEARCH_FIELD_WIDTH ≈ 300`
      - `Alarm(ThreadToUse.SWING_THREAD, parentDisposable)` 300ms 防抖；`cancelAllRequests()` + `addRequest`
      - Esc（`KeyEvent.VK_ESCAPE`）清空 + 恢复；清空后焦点留在输入框
      - `fun clearText()` / `fun currentText(): String` 供外部调用
      - 右侧计数标签 `JBLabel`（初始不可见），`fun updateCount(matched: Int, loaded: Int)`
- [ ] T4.2 `RevisionSelectDialog.createCenterPanel()`：`listPanel` 结构改为
      `add(toolbar, NORTH)` → `add(searchField, NORTH)` → `add(tableScroll, CENTER)`
      （顺序：工具栏、搜索行、表格；用中间容器或嵌套 BorderLayout 保证三者纵向排列）
- [ ] T4.3 `searchField` 防抖回调 → `onFilterChanged()`（过滤词变化路径）
- [ ] T4.4 文案：`search.placeholder` / `search.clear` / `status.search.count`（中英双包）
- [ ] T4.5 `gradlew test` 绿（搜索框为纯 UI，无单测；编译通过即验收）

## T5 过滤引擎退化（R4）

- [ ] T5.1 `log/LogFilter.kt` 重写为退化版：
      - 保留 `searchText`；`by lazy` token 缓存；`matches(list)`（全字段拼接 + 多词 AND + 大小写不敏感子串）
      - 全字段拼接：`comment` + `affectedPaths` + `committerName` + `number` + `dateText(commitDate)`
      - 删除：`fields` / `dateRangeEnabled` / `fromDate` / `toDate` / `hasDateRange()` / `isFiltering()`
        的日期半边（保留 `hasSearchText`）/ `useRegex` / `caseSensitive` / `TokenType.OR` / `AND_NOT` /
        引号与 `!` 解析 / 正则分支 / `highlightRanges` / `isFieldMatched` / `substringRanges` / `mergeRanges` /
        `fieldText` / `bugIdPattern` / `DEFAULT_BUG_ID_PATTERN` / `matchesText` 拆分
      - `isFiltering()` 语义简化为 `hasSearchText`
- [ ] T5.2 **删除** `log/LogFilterField.kt` 整文件
- [ ] T5.3 `dateText()` / `DATE_TIME_FORMATTER` 保留（全字段拼接与日期匹配仍需要）
- [ ] T5.4 清理 `LogFilter` KDoc：改写为「单关键词 + 空格 AND + 大小写不敏感」语义说明，
      删除 TortoiseSVN 对齐叙述（保留「纯客户端、零请求」结论）
- [ ] T5.5 `gradlew test` 红（预期：LogFilterTest 旧用例失败）→ 进入 T10 前先修 LogFilterTest

## T6 过滤 × 分页协同（R7）

- [ ] T6.1 `RevisionSelectDialog.onDataLoaded(added)`：并入 `loadedAll`（去重按 revision number）
      → `applyFilterToModel()` → `refreshMergeStatus(added)` → `refreshList()`；
      **删除** `syncDateRangeToLoaded()` 与 `currentFilter.copy(日期…)` 分支
- [ ] T6.2 `init {}`：删除 `filterPanel.syncDateRangeToData(...)`、`currentFilter = filterPanel.buildFilter()`、
      `settings.defaultFromDate()` 块；`currentFilter = LogFilter()`（空条件）
- [ ] T6.3 `applyFilterToModel()`：`filterToken` 语义保留；后台 `snapshot.filter { it.matches }`；
      EDT `model.setItems` + `lastMatchCount` + `searchField.updateCount(...)`
- [ ] T6.4 删除 `loadLimitReached` / `status.load.limit` 提示路径；`MAX_LOADED_ALL` 闸保留但静默停止
- [ ] T6.5 `loadMore` / `runFirstPage`：确认游标取自 `loader`（不是 model 末行）；
      `loadIndicator` 互斥取消保留；`reloadToken` 仅 Refresh 用——Refresh 删除后 `reloadToken` 仅保护
      在途加载任务，保留不动
- [ ] T6.6 `gradlew test` 绿

## T7 删除日期维度与第三方依赖（R8.1 / R8.2 / R9）

- [ ] T7.1 `settings/EnhanceMergeSettings.kt`：删除 `defaultDateRangeDays` / `dateRangeEnabled` /
      `filterFields` / `useRegex` / `caseSensitive` / `bugIdPattern` / `DEFAULT_BUG_ID_PATTERN` /
      `defaultFromDate()` / `dateRangeEnabledOnOpen()` / `bugIdRegex`
- [ ] T7.2 `settings/EnhanceMergeConfigurable.kt`：删「默认日期范围（天）」「Bug-ID 正则」两行；
      复核设置页只剩「每页版本数」「计算已合并状态」
- [ ] T7.3 `build.gradle.kts`：删除 LGoodDatePicker 依赖与 JitPack 仓库（确认无其他 JitPack 坐标后）
- [ ] T7.4 删除 `src/main/resources/THIRD-PARTY-LICENSES.txt`；若 `buildPlugin` 任务有 `from(...) include("THIRD-PARTY-LICENSES.txt")` 一并删
- [ ] T7.5 `gradlew clean build`（确认无残留引用编译错误）

## T8 删除状态栏 / 隐式 speed search / 过滤栏残留（R8.6 / R8.7 / R3.7）

- [ ] T8.1 `RevisionSelectDialog.kt`：删除 `createSouthAdditionalPanel` / `statusLabel` / `updateStatus` 的
      状态栏部分 / `refreshStatusLabel` / `schedulePathCountRecalc` / `pathCountAlarm` / `pathCount` /
      `PATH_COUNT_DEBOUNCE_MS`
      —— 注意 `updateStatus()` 中 `isOKActionEnabled` 与工具栏刷新逻辑**保留**，只删状态栏文字
- [ ] T8.2 删除 `createRevisionTable()` 中 `TableViewSpeedSearch` 安装块（含 try-catch LinkageError）
- [ ] T8.3 **删除** `ui/LogFilterPanel.kt` 整文件
- [ ] T8.4 清理 `RevisionSelectDialog` 中 `filterPanel` / `LogFilterPanel` import 与字段
- [ ] T8.5 删除 `EnhanceMergeSettingsTest` 中 `defaultFromDate` / `dateRangeEnabledOnOpen` 用例
- [ ] T8.6 `gradlew test`（修到绿）

## T9 文案清理（R10）

- [ ] T9.1 `messages/EnhanceMergeBundle.properties` 与 `_zh_CN.properties`：
      删除 `filter.by.*`（4）、`filter.field.*`（8）、`filter.from`、`filter.to`、`filter.clear.search`、
      `filter.search.placeholder`、`settings.default.date.range*`（2）、`status.no.match`、`status.loading`、
      `status.search.partial`、`status.load.limit`、`action.show.all*`、`action.reload*`、
      `status.revision.select`、`status.revision.range`、`label.already.merged` **保留**
- [ ] T9.2 `action.load.hundred` / `action.load.fiveHundred` 复核（+N 文案对齐原版 `+100` / `+500` 风格）
- [ ] T9.3 `BundleKeysTest` 更新 key 集合（`filter.field.dateRange/regex/caseSensitive` 条目删除）
- [ ] T9.4 全仓 `search_content` 复核无硬编码用户可见文本（规范：文本一律进文案包）

## T10 测试更新与收口（R11）

- [ ] T10.1 `LogFilterTest` 重写：多词 AND（全命中/缺一/大小写混合）、大小写不敏感、
      全字段命中（说明/路径/作者/修订号/日期四种各至少一例）、空词不过滤、`hasSearchText`
- [ ] T10.2 `LogPageLoaderTest`：删日期条件构造（若有）；确认游标/世代号用例仍成立
- [ ] T10.3 `EnhanceMergeSettingsTest`：只保留 `pageSize` / `computeMergeStatus` 相关；
      补一条「旧配置含已删字段时 loadState 不炸」用例（R9.3 佐证）
- [ ] T10.4 `QuantitySelectionTest` / `EnhanceMergeBundleTest` 不动，跑通即可
- [ ] T10.5 记录最终用例数（预期：60 - 删除约 15 + 新增约 6 ≈ 51，以实跑为准）

## T11 自检清单（代码规范回归）

- [ ] T11.1 无 `javax.swing.Timer`；Alarm 均绑 Disposable
- [ ] T11.2 所有像素值 `JBUI.scale(...)`；主题色走 `UIUtil.*` / `JBColor.border()`
- [ ] T11.3 无 `catch (Throwable)`；反射分 `ReflectiveOperationException` / `LinkageError`
- [ ] T11.4 `invokeLater` 均带 `ModalityState` + lambda 内过期条件
- [ ] T11.5 纯图标按钮有 `accessibleContext.accessibleName`
- [ ] T11.6 `LOG.error` 仅内部错误；可恢复失败 `LOG.warn`
- [ ] T11.7 无 2022.1 不存在的 API（`JButton` components 包、`ActionUpdateThread` 等）

## T12 全量验证

- [ ] T12.1 `gradlew clean test`：0 失败，记录用例数
- [ ] T12.2 `gradlew buildPlugin`：BUILD SUCCESSFUL；`javap -verbose -cp build/classes/kotlin/main
      com.weixf.svn.mergeenhance.flow.MergeFlow | grep "major version"` = 55
- [ ] T12.3 `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyTimeout=600"`：PASS（0 issues）
- [ ] T12.4 `gradlew runPluginVerifier -PverifierLocalIde="G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3"`：两端 Compatible
- [ ] T12.5 人工沙箱（`drools_learn`，分支含 ≥50 修订）：见 `checklist.md` M 节逐项过

## T13 文档同步

- [ ] T13.1 `README.md`：能力表（删 TortoiseSVN 复刻叙述，改「原版界面 + 关键词过滤」）、
      架构表、文件树（删 `LogFilterPanel.kt` / `LogFilterField.kt`，加 `RevisionSearchField.kt`）、测试清单
- [ ] T13.2 `CHANGELOG.md`：新增「第五轮」条目
- [ ] T13.3 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc`：新增第五轮实施记录；
      实际类清单同步；单测数字同步；「2026-10-20 第四轮」节标记为被第五轮取代的部分
- [x] T13.4 本文档与 `checklist.md` 全部勾选；`plan` 文档标注「已实施」并补验证结果

---

## 实施记录（2026-10-21）

- T1 基线：60 用例 0 失败（6 suite）；
- T2~T11 全部完成：行渲染回归 `CommittedChangeListRenderer`、工具栏四动作、
  新增 `ui/RevisionSearchField.kt`、`LogFilter` 重写为单参全字段 AND、删 `LogFilterField.kt` /
  `LogFilterPanel.kt` / LGoodDatePicker / JitPack / `THIRD-PARTY-LICENSES.txt`、
  设置收敛为两项、文案两包重写、单测 60 → 39；
- T12 验证：`clean test buildPlugin` → **39 用例 0 失败**、zip **103KB**、字节码 **major 55**；
  `verifyOnIde 2022.1`（timeout 900）→ **PASS 0 ERROR**；
  `runPluginVerifier` 本地双端 → **Compatible**（262 侧 1 处 deprecated 不阻断）；
- T13 文档同步：README / CHANGELOG / 规则文档（新增第五轮节）/ 本文档 / checklist / plan 均已更新；
- 遗留：checklist B/C/D 节（沙箱人工走查）待用户在真实 IDE 中过一遍。
