# 方案 v3（第五轮）：版本选择对话框「原版化 + 显式搜索过滤」

> **实施状态：✅ 已实施（2026-10-21）**。验证：39 用例 0 失败 / zip 103KB / 字节码 major 55 /
> verifyOnIde 2022.1 PASS / runPluginVerifier 双端 Compatible。任务与验收见
> `.codebuddy/specs/add-revision-search-filter/`。
>
> **本文档取代前四轮中与「过滤栏 / 日期区间 / 高亮 / Show All / Refresh / 状态栏 / 隐式 speed search」相关的全部设计**。
> 前四轮记录（v2 方案、TortoiseSVN 复刻、R1~R4 修复、第三/四轮解耦）仍作为历史留档于
> `.codebuddy/plan/RevisionSelect-StandalonePlugin-plan.md` 与规则文档中，本文档只描述**第五轮的新方向**。

---

## 1. 背景与用户痛点

原版 IDEA 的 SVN 合并版本选择框（Merge From → Quick Manual Select → `ToBeMergedDialog`）在
**版本数量多、时间跨度大**的场景下，用户只能滚动列表逐个寻找目标版本，**没有任何关键词过滤手段**。

用户诉求（2026-10-20 确认）：

> 在原版界面上**增加一个显式的条件搜索过滤功能**；其余界面展示与功能**保持原版**。

因此本轮的定位是：**不是继续复刻 TortoiseSVN，也不是新造交互，而是把本插件已经跑偏的 UI 全部退回原版，
只增加「一个搜索框」**。

### 1.1 为什么现在做这件事

本插件自 v2 起为复刻 TortoiseSVN 的日志对话框，在版本选择界面上叠加了一整套 TSVN 式过滤设施
（By 字段勾选、搜索框 ✕、From/To 日期区间、正则/大小写开关、命中高亮、Show All、Refresh、
自定义状态栏），并与表格上的隐式 `TableViewSpeedSearch` 形成**双轨搜索**。四轮审查与重构
（见规则文档）表明：这套叠加是此前大量"奇怪问题"（日历与列表不一致、过滤态下 +N 跳段、
按键消费竞争、EDT 卡顿等）的共同土壤。用户明确不需要 TSVN 式界面，故一次性移除。

---

## 2. 目标形态

```
Merge from <branch>
┌──────────────────────────────────────────────────────────────┐
│ [☑Select all][☐Unselect all] [+100] [+500]     ← 工具栏：与原版逐项一致        │
│ [🔍 过滤版本（说明/路径/作者/版本号/日期）…        ✕]  ← 唯一新增：独立一行、宽度适度  │
│ ☑  61 conflict test                     2026/10/18 12:51  …/pojo  1 file        │
│ ☑  60 Merged from dev 1 [from revision 59]  2026/10/18 12:03  …                │
│ ☐ + 57 30                               2026/10/18 15:53  …                  │
│ ☐ + 56 29                               …                                    │
│                                          │ svn://…/Person.java（右栏变更树）     │
│                                          │                                    │
│                          [Merge Selected] [Cancel]                            │
└──────────────────────────────────────────────────────────────┘
```

**唯一新增**：表格上方独立一行的搜索框（不拉满宽度，左对齐，约 300px 量级）。
**其余一切**：布局、行渲染、工具栏四动作、+100/+500 语义、Splitter 0.7、右栏变更树、
`[Already merged]` 标记、合并状态图标、底部按钮——**与原版 `ToBeMergedDialog` 一致**。

---

## 3. 原版真实形态（源码证据，2026-10-20 核实）

| 项 | 原版事实 | 证据 |
|---|---|---|
| 列表容器 | `TableView` + `ListTableModel(FAKE_COLUMN)` 单列，`setTableHeader(null)`、`setShowGrid(false)`、`setExpandableItemsEnabled(false)`（运行时字节码已验证） | `ToBeMergedDialog.java:239-261`；`svn4idea.jar` 反汇编 `iconst_0` |
| 行渲染 | `MyListCellRenderer` = `JPanel(BorderLayout)`：`JCheckBox`(WEST) + 平台 **`CommittedChangeListRenderer`**(CENTER) | `ToBeMergedDialog.java:477-535` |
| 行内容 | `[复选框] [ListMergeStatus 图标] 修订号 提交说明 提交者 日期(yyyy/MM/dd HH:mm) 文件数摘要` | 用户截图 + `CommittedChangeListRenderer.renderChangeList` |
| 状态图标 | `ListMergeStatus`：COMMON(`SvnIcons.Common`) / MERGED(`Integrated`) / NOT_MERGED(`Notintegrated`，即截图中的 `+`) / ALIEN(null) / REFRESHING(`Nodes.Unknown`) | `ListMergeStatus.kt:8-14` |
| 工具栏 | 仅 4 个动作：`MySelectAll` / `MyUnselectAll` / `MoreXAction(100)` / `MoreXAction(500)` | `ToBeMergedDialog.java:296-300` |
| +N 行为 | 加载中：隐藏 +500、禁用 +100/+500；完成：恢复并 `addRows` 追加；到底：两按钮隐藏 | `ToBeMergedDialog.java:126-153, 375-392` |
| 布局 | `Splitter(false, 0.7f)`，`dividerWidth=2`；右栏 `CommittedChangesBrowser` + `[Already merged]` 装饰 | `ToBeMergedDialog.java:282-288, 311-324` |
| 搜索 | **无显式搜索框**；仅隐式 `TableViewSpeedSearch`（`getItemText` = commit comment，`setupListeners()`） | `ToBeMergedDialog.java:252-258` |
| 状态栏 | **无自定义状态栏** | 全文无 `createSouthAdditionalPanel` |
| 对话框外观 | 用户实测截图（2026-10-20 提供）：标题 `Merge from <branch>`、扁平列表、右下 `Merge Selected` / `Cancel` | — |

> **勘误留档**：调研过程中曾把截图行首的 `+` 误判为"树形展开手柄"、把 merge commit 说明误判为"分组标题"。
> 真相：原版是**扁平表格**，`+` 是 `ListMergeStatus.NOT_MERGED` 的状态图标，未勾选的空复选框在截图中不可见。

---

## 4. 本插件当前偏离清单（7 处，全部回归原版）

| # | 偏离 | 现行实现 | 处置 |
|---|---|---|---|
| 1 | 行渲染自绘 | `RevisionSelectDialog.RevisionCellRenderer`（`SimpleColoredComponent` 拼接 r号/作者/日期/说明 + 分段高亮；KDoc 自述"自绘是为了高亮"） | **删除**，回归 `CommittedChangeListRenderer` + `JCheckBox` |
| 2 | 顶部过滤栏整条 | `LogFilterPanel`：By 弹窗 + 搜索框✕ + From/To 日期 + 正则/大小写 | **删除**，只保留一个独立一行的搜索框 |
| 3 | 工具栏多两个动作 | `showAllAction` / `reloadAction` | **删除** |
| 4 | 自定义状态栏 | `createSouthAdditionalPanel` + `statusLabel` + `status.*` 文案 + 防抖路径数重算 | **删除** |
| 5 | 隐式 speed search | `RevisionSelectDialog.kt:438-446` 的 `TableViewSpeedSearch`（含 LinkageError 降级） | **删除**（与显式搜索框双轨；与空格切换勾选存在按键消费竞争，已字节码证实） |
| 6 | 自定义行高 | `ROW_HEIGHT = 22` | **删除**，用平台默认 |
| 7 | 第三方依赖 | LGoodDatePicker 11.2.1（JitPack）+ `THIRD-PARTY-LICENSES.txt` | **移除**，回到"零外部 Maven 坐标" |

---

## 5. 方案设计

### 5.1 搜索框 UI（唯一新增组件）

- **位置**：左侧列表栏内、工具栏**下方**、表格**正上方**独立一行（`listPanel` 的 `BorderLayout.NORTH` 叠在 toolbar 之下）。
  即紧跟表格，属于"表格的搜索框"，**不**横跨右栏、不放整个对话框顶部（那会重回"过滤栏"观感）。
- **宽度**：适度，不拉满。`preferredSize = JBUI.size(SEARCH_FIELD_WIDTH, …)`，`SEARCH_FIELD_WIDTH ≈ 300`，
  左对齐；同行右侧可放一个轻量计数标签（见 5.6）。
- **构成**：`JBTextField`（无边框内嵌 1px 主题边框，样式沿用现有 `searchBox` 做法：`UIUtil.getTextFieldBackground()` +
  `JBUI.Borders.customLine(JBColor.border(), 1)`）+ 右侧内嵌 ✕ 清除按钮（`AllIcons.Actions.Close`，
  仅在有内容时可见，`accessibleContext.accessibleName` 必填，官方无障碍要求）。
- **placeholder**：进文案包（`search.placeholder`，如「过滤版本（说明 / 路径 / 作者 / 版本号 / 日期）」）。
- **行为**：
  - 输入 300ms 防抖（平台 `Alarm(ThreadToUse.SWING_THREAD, disposable)`，**禁** Swing Timer）；
  - Esc 或 ✕ 清空 → 恢复完整列表；
  - 清空后焦点留在搜索框（不抢占表格）。
- **不**带回车触发、不有历史记录、不有不区分大小写开关（固定不敏感）。

### 5.2 过滤语义（`LogFilter` 退化版）

- **匹配范围**：全字段拼接串——提交说明 + 全部变更路径 + 作者 + 修订号 + 日期文本（`yyyy-MM-dd HH:mm`）。
  理由：用户痛点是"版本多、跨度大找目标"，只搜说明会漏掉按路径/作者/日期找的场景（原版隐式 speed search 的硬伤）。
- **语法**：按空格拆多个 token，**全部 AND**；子串匹配；**大小写不敏感**。
- **删除的语义**：`-`NOT / `+`OR / `"…"`短语 / `!` 取反 / 正则 / 大小写开关 / By 字段勾选 / Bug-ID 单独字段 /
  日期区间 / `matchesText` 与 `matches` 的拆分（第四轮为日期引入，随日期一并消失）。
- **无高亮**：命中行不做任何分段着色（用户明确不需要）。
- **空搜索词** = 不过滤。

### 5.3 过滤 × 分页协同（本轮唯一必须新增的机制）

原版 `+100/+500` 从 `getLastNumber()`（**model 可见末行**的修订号）继续加载
（`ToBeMergedDialog.java:138-142`）。引入过滤后，model 末行是"过滤后最早的一条"，
从它继续会**跳过中间整段未加载的修订**。因此：

- **游标跟踪"已加载全集的最早修订号"**（复用现有 `LogPageLoader` 的游标与世代号机制，正是为此而建）；
- `+N`：`loader.loadNextPageRaw(size, generation)` 从游标继续 → 新页并入 `loadedAll`（原始全集）
  → 按当前搜索词**重过滤** → `model.setItems(过滤结果)`；
- 「已到底」判定以 loader 为准（`allLoaded`），不是 model 行数；
- 切换/清空搜索词只重过滤，**零请求**（对齐第四轮决策 1）；
- 加载任务互斥取消（沿用 `loadIndicator` + `reloadToken`/`filterToken` 双层令牌）。

### 5.4 行渲染回归（`CommittedChangeListRenderer`）

复刻原版 `MyListCellRenderer`：

```kotlin
private inner class OriginalCellRenderer : TableCellRenderer {
  private val panel = JPanel(BorderLayout())
  private val checkBox = JCheckBox().apply { isEnabled = true }
  private val renderer = CommittedChangeListRenderer(context.project) { list ->
    if (settings.computeMergeStatus)
      mergeStatusMap[list.number] ?: ListMergeStatus.REFRESHING
    else null
  }
  // getTableCellRendererComponent: panel.removeAll(); renderer.clear();
  // renderer.renderChangeList(table, value); 配色 UIUtil.getTableSelection*/getTable*；
  // checkBox.isSelected = wiseSelection.isSelected(number);
  // panel.add(checkBox, WEST); panel.add(renderer, CENTER)
}
```

- 复选框必须贴单元格最左（`installCheckboxClick()` 命中区从 `cell.x` 起算一个复选框宽，T8 踩坑结论保留）；
- 状态图标 provider 语义对齐原版：`myAllStatusesCalculated ? NOT_MERGED : statusMap[n] ?: REFRESHING`
  （本插件常显计算中，简化取 `mergeStatusMap[n] ?: ListMergeStatus.REFRESHING`；关设置时返回 null）；
- 删除：`RevisionCellRenderer` 全类、`appendHighlighted`、`HIGHLIGHT`/`HIGHLIGHT_SELECTED`、`STATUS_ICON_GAP`、
  `FIELD_SEPARATOR`、`ROW_HEIGHT`、`LogFilterField` 的渲染侧用法；
- 补回原版有的 `AbstractBaseTagMouseListener`（行内提交说明超链接可点，原版 `ToBeMergedDialog.java:262-273`）
  ——列为 **P2 可选项**（不影响过滤主线，截图显示原版支持）。

### 5.5 工具栏与 +N 行为对齐原版

- 动作集合：`MySelectAll` / `MyUnselectAll` / `MoreXAction(100)` / `MoreXAction(500)`（图标同原版：
  `AllIcons.Actions.Selectall` / `Unselectall`，+N 两个用文字 `+100`/`+500`）。
- 显隐语义对齐原版：`loader.allLoaded` → 两个 +N **隐藏**（不是置灰）；
  加载中 → 隐藏 +500、禁用 +100（原版 `MoreXAction.perform()` 行为）；
- 「合并前本地改动提示」链路（`LocalChangesPromptTask`）与 `MergeExecutor` **不动**。

### 5.6 过滤反馈（轻量）

- 搜索框同行右侧显示 `匹配 M / 已加载 N`（`status.search.count`，P1，帮助用户理解"为什么过滤后变少"——
  因为过滤只作用于已加载集合，深历史需 +N 加载）；
- 原版无状态栏，故**不恢复**任何底部状态栏；「已选 N 个」信息由 OK 按钮文案
  `Merge Selected` 保持原版（本插件现有 `isOKActionEnabled` 启用逻辑保留，不改文案）。

### 5.7 设置收敛（`EnhanceMergeSettings`）

| 字段 | 处置 | 理由 |
|---|---|---|
| `pageSize` | **保留** | 首屏条数（原版固定 100，本插件可配置是既有增强，无害） |
| `computeMergeStatus` | **保留** | T8 合并状态开关 |
| `bugIdPattern` / `DEFAULT_BUG_ID_PATTERN` | **删除** | Bug-ID 字段取消后无消费者（全字段子串已覆盖） |
| `defaultDateRangeDays` | **删除** | 日期区间取消 |
| `dateRangeEnabled` | **删除** | 同上 |
| `filterFields` | **删除** | By 字段勾选取消 |
| `useRegex` / `caseSensitive` | **删除** | 开关取消 |

> 持久化兼容：`PersistentStateComponent` 对已删除字段直接忽略，旧 `svnEnhanceMerge.xml` 无需迁移。

### 5.8 文案（messages）变更

- **新增**：`search.placeholder`、`search.clear`（✕ 无障碍名）、`status.search.count`（`匹配 {1} / 已加载 {0}`）。
- **删除**：`filter.by.*`（4）、`filter.field.*`（8）、`filter.from`/`filter.to`、`filter.clear.search`、
  `filter.search.placeholder`、`settings.default.date.range*`（2）、`status.no.match`、`status.loading`、
  `status.search.partial`、`status.load.limit`、`action.show.all*`、`action.reload*`、`action.load.*`
  （+N 改回原版文案 `button.load.quantity` 风格：`action.load.hundred`/`action.load.fiveHundred` 保留但语义对齐原版）、
  `label.already.merged`（**保留**，右栏装饰仍用）。
- `BundleKeysTest` 的 key 集合同步更新。

---

## 6. 删除清单（代码级）

| 文件 | 删除内容 |
|---|---|
| `ui/LogFilterPanel.kt` | **整文件删除**（搜索框逻辑并入新 `ui/RevisionSearchField.kt` 或 `RevisionSelectDialog` 内部） |
| `ui/RevisionSelectDialog.kt` | `RevisionCellRenderer` 全类、`appendHighlighted`、高亮常量、`ROW_HEIGHT`、`STATUS_ICON_GAP`、`FIELD_SEPARATOR`；`filterPanel` 字段及 `init{}` 中的 `syncDateRangeToData`/`applyDefaultDateRange`；`onDataLoaded` 日期分支；`syncDateRangeToLoaded`/`dateRangeFilter` 调用；`refreshStatusLabel`/`statusLabel`/`createSouthAdditionalPanel`/`updateStatus`/`schedulePathCountRecalc`/`pathCountAlarm`/`pathCount`；`TableViewSpeedSearch` 安装块（438-446）；`showAllAction`/`reloadAction`/`loadAll`；`lastMatchCount`/`loadLimitReached`/`MAX_LOADED_ALL` 触顶提示（或简化为静默停止，见任务 T6.4） |
| `log/LogFilter.kt` | `dateRangeEnabled/fromDate/toDate/hasDateRange`、`fields` 集合、`useRegex/caseSensitive`、`TokenType.OR/AND_NOT`、`tokenize` 的 `-`/`+`/`"`/`!` 解析、`matchCombined` 正则分支、`highlightRanges/isFieldMatched/substringRanges/mergeRanges/fieldText`、`bugIdPattern/DEFAULT_BUG_ID_PATTERN`、`matchesText` 拆分；保留：`searchText` 分词 AND + 大小写不敏感子串匹配 + `matches` |
| `log/LogFilterField.kt` | **整文件删除**（By 字段枚举无消费者） |
| `settings/EnhanceMergeSettings.kt` | 5.7 表所列字段与 `defaultFromDate()`/`dateRangeEnabledOnOpen()`/`bugIdRegex` |
| `settings/EnhanceMergeConfigurable.kt` | 「默认日期范围」「Bug-ID 正则」两个设置行 |
| `build.gradle.kts` | LGoodDatePicker 依赖（JitPack repository 一并评估移除） |
| `src/main/resources/THIRD-PARTY-LICENSES.txt` | **整文件删除** |
| `messages/*.properties` | 5.8 节所列 key（中英双包同步） |
| 测试 | `LogFilterTest` 删日期/正则/高亮/字段用例；`EnhanceMergeSettingsTest` 删 `defaultFromDate`/`dateRangeEnabledOnOpen` 用例；`LogPageLoaderTest` 删日期条件构造（若有）；`BundleKeysTest` 更新集合 |

## 7. 保留清单

- `flow/MergeFlow`、`merge/MergeExecutor`、`wcinfo/WcInfoLinkInjector`、`EnhanceMergeBundle`（语言包感知）——不动；
- `log/LogPageLoader`（游标/世代号/分页）——不动（5.3 的核心依赖）；
- T8 合并状态全链路（`OneShotMergeInfoHelper`/`mergeCheckerLock`/`mergeStatusMap`/`toListMergeStatus`/`[Already merged]` 装饰）——不动；
- `QuantitySelection` 稀疏勾选、复选框点击命中、空格键切换勾选（删 speed search 后无竞争）——不动；
- `MergeVariantDialog`、入口链路、`plugin.xml` 结构——不动。

## 8. 新增清单

| 项 | 说明 |
|---|---|
| `ui/RevisionSearchField.kt`（新） | 搜索框组件：输入框 + ✕ + 防抖 + Esc + 计数标签；纯 UI，过滤逻辑委托 `RevisionSelectDialog` |
| `LogFilter`（退化版） | 分词 AND + 大小写不敏感子串；`by lazy` token 缓存保留 |
| `+N` 追加后重过滤 | `onDataLoaded` 统一入口：并入全集 → 重过滤 → setItems |
| 3 条新文案 | `search.placeholder` / `search.clear` / `status.search.count` |
| 单测 | `LogFilterTest` 新用例：多词 AND、大小写不敏感、全字段（路径/作者/日期）命中、空词不过滤 |

---

## 9. 调研留痕（备选方案与未采用原因）

| 候选 | 结论 | 依据 |
|---|---|---|
| A. 复刻 Repository 面板的 speed search（`TreeSpeedSearch` 语义）到表格 | **未采用**：隐式入口、只跳转不过滤，与"过滤出 Log"的诉求不符；且 `SpeedSearchAction` 绑定 ToolWindow（IDEA-332527 起才支持模态对话框），本插件 since-build=221 不可用；底层 `ElementFilter/FilteringTableModel` 221 虽在，但会与现有过滤通路重复建设 | 第一~五轮调研；`SpeedSearchAction` 2023-09 才独立化（GitHub 提交史） |
| B. 改用平台 `FilteringTableModel` 包装 | **未采用**：其 `List<List<T>>` 结构为树形/分组数据设计；丢失已建的防抖、令牌、合并状态、右栏联动 | javap 2022.1/2026.2.3 签名比对 |
| C. 保留 TSVN 过滤栏但只删日期 | **未采用**：双轨搜索与按键竞争仍在；用户明确"界面保持原版" | 用户决策 2026-10-20 |
| D. 搜索框放工具栏右侧同行 | **未采用**：用户选择"表格上方单独一行，不必那么长" | 用户决策 2026-10-20 |
| E. 过滤后高亮命中行 | **未采用**：用户明确不需要 | 用户决策 2026-10-20 |

---

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 过滤态下 +N 跳段（原版 `getLastNumber()` 缺陷在过滤下放大） | 5.3 游标跟踪已加载全集；`LogPageLoaderTest` 补用例 |
| `CommittedChangeListRenderer` 回归后复选框命中区错位 | 复选框贴 WEST；命中区宽度 = `JCheckBox().preferredSize.width`（原版同构） |
| 删除设置字段导致旧配置加载失败 | `PersistentStateComponent` 忽略未知字段；`loadState` 已有 normalize |
| 删 LGoodDatePicker 后 `THIRD-PARTY-LICENSES.txt` 空文件残留 | 整文件删除 + `buildPlugin` 追加的 from(...) 一并移除 |
| 单测数量波动 | 任务 T10 明确新增/删除用例数，收口后跑全量 |
| `plugin.xml` `<change-notes>`/README 描述过期 | T12 文档同步清单 |

---

## 11. 验证计划

1. `gradlew clean test`：全量单测（目标用例数见 tasks T10 收口值）；
2. `gradlew buildPlugin`：产物字节码 `major version = 55` 自检（`javap -verbose … MergeFlow \| grep "major version"`）；
3. `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyTimeout=600"`：沙箱 PASS（0 issues）；
4. `gradlew runPluginVerifier -PverifierLocalIde=…ideaIC-2022.1,…ideaIC-2026.2.3`：两端 Compatible；
5. 人工沙箱（`drools_learn` 项目，含 ≥50 修订的分支）：
   - 列表样式与原版截图逐项一致（复选框/状态图标/日期/文件摘要/无表头/无网格）；
   - 搜索框输入关键词 → 即时过滤；Esc/✕ → 恢复；
   - 过滤态下点 +100 → 不跳段、新命中并入；
   - 工具栏只有 4 个动作；无日期控件；无高亮；表格上直接打字不弹任何搜索条；
   - 勾选/空格切换/右栏变更树/`[Already merged]`/Merge Selected 全链路正常。

---

## 12. 文档同步清单（实施完成后）

- [ ] `README.md`：能力表、架构表、文件树、测试清单同步第五轮形态
- [ ] `CHANGELOG.md`：新增「第五轮」条目
- [ ] 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc`：新增「第五轮」实施记录节；实际类清单删 `LogFilterPanel`/`LogFilterField`、加 `RevisionSearchField`；单测数字同步
- [ ] 本文档标注「已实施」并补验证结果
