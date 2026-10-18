# Enhance Merge（增强合并）增强插件 Spec — v2

> v2 依据 `svn4idea/plugins/svn4idea` 源码逐类核实后重写，**取代 v1**。
> 详细技术设计见 `.codebuddy/plan/RevisionSelect-StandalonePlugin-plan.md`。

## Why

开发者在 IDEA + SVN 做分支合并时，常需「只合并我关心的若干次提交」。原插件 `Quick Manual Select`(`showLatest`)
提供的修订列表**没有任何条件过滤能力**，难以在大量提交中定位目标；而 TortoiseSVN 的合并日志界面有成熟的条件过滤栏
（路径 / 作者 / 版本 / Bug-ID / 日期范围），是用户已习惯的交互。

因此需要一种**与原生「Merge From…」并列、且保留原版整体流程**的第二种合并方式：

```
Merge From 下方的「Enhance Merge…」
  → 原版 Select Branch 弹窗（不变）
  → Select Merge Variant（只保留 Revision Select + Cancel）
  → Merge from <branch>（原版布局 + 顶部 TortoiseSVN 式过滤栏 + 实时分页加载）
```

同时修正 v1 的两个实现问题：
- v1 的日志是**一次性全量** `doLog(HEAD → r1, limit=0)`，大仓库首屏极慢；实际需要的是**分页实时**加载（首屏 30 条）。
- v1 自绘的分支选择/合并方式/修订列表与原版界面**不一致**，本次统一复用原版公开 API 与渲染组件。

**不 fork、不修改 `plugins/svn4idea` 源码**，仅依赖其公开 API。

## What Changes

- **删除** v1 的菜单/右键入口（`SubversionGroup` + `Vcs.Operations.Popup` 注册）。
  入口**只保留**：「Version Controls」工具窗 →「Subversion Working Copies Information」面板中，
  每个工作副本的「Merge From…」下方的 **Enhance Merge…** 链接。
- **删除** v1 自绘的 `BranchSelectionDialog` / `MergeMethodSelectionDialog` / `RevisionSelectDialog`
  / `RevisionLogDataSource` / `RevisionItem` / `SvnRevisionMergeExecutor`（全量拉取 + 自绘表格）。
- **新增 / 重写**：
  - `wcinfo/WcInfoLinkInstaller`：向原版 `WorkingCopyInfoPanel` 注入 Enhance Merge 行（改用 `insertBeforeEnd(table, …)`）。
  - `flow/MergeFlow`：编排 分支 → 方式 → 修订 → 执行。
  - `ui/MergeVariantDialog`：仅 Revision Select + Cancel。
  - `ui/RevisionSelectDialog`：原版 `ToBeMergedDialog` 同构布局 + 顶部过滤栏 + 分页。
  - `ui/LogFilterPanel`、`log/LogFilter`、`log/LogPageLoader`。
  - `merge/MergeExecutor`：复用 `SvnIntegrateChangesTask` + `ChangeListsMergerFactory`。
  - `settings/EnhanceMergeSettings` + `settings/EnhanceMergeConfigurable`。
  - `messages/EnhanceMergeBundle.properties`（+ `_zh_CN`）。
- **不引入 SVNKit**；不写入 `SvnConfiguration`（避免影响原版 Merge From 行为）。

## Impact

- Affected specs：合并（integrate/merge）能力层的**独立增强插件**。
- Affected code（包 `com.weixf.svn.mergeenhance`，根目录 `d:\WorkSpace\AIProjects\SubversionEnhance\`）：

| 文件 | 变更 |
|---|---|
| `plugin.xml` | 去掉 `<actions>`；新增 `<postStartupActivity>`、`<applicationService>`、`<applicationConfigurable>`（标题用 `key` 走 bundle 本地化）、`<resource-bundle>` |
| `EnhanceMergeAction.kt` | 保留为内部可调用类（合成事件调用），**不再注册** |
| `wcinfo/WcInfoLinkInstaller.kt` | 重写（替换 `WcInfoPanelLinkInjector.kt`，删除旧文件） |
| `flow/MergeFlow.kt` | 新增 |
| `ui/MergeVariantDialog.kt` | 替代 `MergeMethodSelectionDialog.kt`（删除旧文件） |
| `ui/RevisionSelectDialog.kt` | 重写 |
| `ui/LogFilterPanel.kt` | 新增 |
| `log/LogFilter.kt`、`log/LogPageLoader.kt` | 新增（替代 `RevisionLogDataSource.kt`，删除旧文件） |
| `merge/MergeExecutor.kt` | 替代 `SvnRevisionMergeExecutor.kt`（删除旧文件） |
| `settings/*` | 新增 |
| `BranchSelectionDialog.kt`、`RevisionItem`、`RevisionRangeFormatter.kt` | 删除（前两者被复用原版替代；Formatter 由状态栏文案替代） |
| `README.md` | 同步重写 |

- 技术基线：`IntelliJ IDEA 2022.1`（build 221）起；构建 JDK 11；Kotlin JVM + `org.jetbrains.intellij` 1.17.x；
  `intellij { plugins.set(listOf("Subversion")) }`。

## 关键接入点（已从源码核实可见性）

| # | 能力 | 接入点 | 可见性 |
|---|---|---|---|
| R1 | 面板入口注入 | `org.jetbrains.idea.svn.dialogs.WorkingCopyInfoPanel`（**internal**，按类名 + 反射 `getInfo()` 定位） | internal → 反射 |
| R2 | 分支弹窗 | `SelectBranchPopup.showForBranchRoot(project, vcsRoot, BranchSelectedCallback, title, anchor)` | **public** ✅ |
| R2 | 目标分支对应 URL | `SvnBranchConfigurationNew.getRelativeUrl(url)` + `Url.appendPath(rel, false)` | **public** ✅ |
| R3 | 合并上下文 | `new MergeContext(vcs, sourceUrl, wcInfo, branchTail, root)` | **public** ✅ |
| R4 | 分页取日志 | `MergeCalculatorTask.loadChangeLists(ctx, beforeRevision, size)` → `Pair<List<SvnChangeList>, Boolean>` | **public static** ✅ |
| R4 | 底层查询 | `SvnCommittedChangesProvider.getCommittedChangesWithMergedRevisions(settings, location, maxCount, consumer)` | **public** ✅ |
| R5 | 行模型 | `org.jetbrains.idea.svn.history.SvnChangeList` | **public** ✅ |
| R5 | 列表渲染 | `TableView` + `ListTableModel` + `CommittedChangeListRenderer` + `CommittedChangesBrowser` + `Splitter` | **public** ✅ |
| R6 | 勾选语义 | `QuantitySelection<Long>(startFromSelectAll)` | **public** ✅ |
| R9 | 合并执行 | `SvnIntegrateChangesTask(vcs, WorkingCopyInfo, MergerFactory, sourceUrl, title, dryRun, branchName)` + `ChangeListsMergerFactory(lists, false, false, true)` | **public** ✅ |
| R10 | 已合并状态 | `OneShotMergeInfoHelper(mergeContext)` / `MergeChecker` / `MergeCheckResult` / `ListMergeStatus` | **public** ✅ |
| — | 不可使用 | `SvnBranchConfigurationManager`、`QuickMerge`、`QuickMergeInteractionImpl`、`ToBeMergedDialog`（可复用思路但无法改造） | internal / 不可改造 ❌ |

> **v1 结论更正**：v1 认为"分支枚举依赖 internal 的 `SvnBranchConfigurationManager`，独立插件无法复用"。
> 实际上 `SelectBranchPopup` 是 public，回调直接提供 `SvnBranchConfigurationNew`，图2 可 100% 原样复刻。

## ADDED Requirements

### Requirement: R1 面板入口「Enhance Merge…」

系统 SHALL 在「Version Controls」→「Subversion Working Copies Information」面板中，
为每个工作副本的「Merge From…」行**下方**注入一行 **Enhance Merge…** 链接；点击后启动本合并流程。
系统 SHALL NOT 在 `Subversion` 主菜单或右键菜单中注册任何入口。

#### Scenario: 链接可见且位置正确
- **WHEN** 打开 Version Controls 工具窗的 Subversion Working Copies Information 标签页
- **THEN** 每个正常（非错误）工作副本的信息表末尾，在「Merge From…」之后显示「Enhance Merge…」

#### Scenario: 面板刷新后自愈
- **WHEN** 用户刷新工作副本列表（原版 `CopiesPanel.updateList()` 会重建面板）
- **THEN** 链接自动重新注入，不会消失、不会重复

#### Scenario: 多工作副本定位准确
- **WHEN** 项目含多个 SVN 工作副本，用户点击第 2 个副本的 Enhance Merge…
- **THEN** 合并目标 = 第 2 个副本（不是第 1 个）

#### Scenario: 注入失败可诊断
- **WHEN** 注入过程发生任何异常（解析失败、结构不符、反射失败）
- **THEN** 记录 WARN 日志（含异常），不影响原版面板其它功能；成功时记录 INFO 日志

### Requirement: R2 分支选择（复用原版 Select Branch）

系统 SHALL 在点击 Enhance Merge… 后调用原版 `SelectBranchPopup`，展示与原插件**完全一致**的分支弹窗
（`trunk (trunk)` / `branches…` / 分隔线 / `Configure Branches…`），并在选定分支后构造 `MergeContext` 进入下一步。

#### Scenario: 弹窗内容与原版一致
- **WHEN** 分支配置已存在
- **THEN** 弹窗列出 trunk（带 `(trunk)` 后缀）、各 branch 及 `Configure Branches…` 项（带分隔线）

#### Scenario: 选定 trunk / 分支
- **WHEN** 用户选择 trunk 或某个具体分支
- **THEN** 按 `configuration.getRelativeUrl(wcInfo.url)` 计算**该分支下与当前工作副本对应的 URL**，
  用作合并源 `sourceUrl`，进入图3

#### Scenario: 分支配置缺失 / 无法解析相对路径
- **WHEN** `getRelativeUrl()` 返回 null（当前工作副本不在此分支配置树内）
- **THEN** 显示明确错误提示并中止（**不**像原版 `CopiesPanel` 那样用 `notNullize` 抛异常）

#### Scenario: 不能从自身合并
- **WHEN** 所选源 URL 与当前工作副本 URL 存在祖先关系（同层级）
- **THEN** 提示「Cannot merge from self」并中止，不发起任何 SVN 请求

#### Scenario: 工作副本根无效
- **WHEN** 目标工作副本的 VirtualFile 失效
- **THEN** 提示并不继续

### Requirement: R3 合并方式选择（仅 Revision Select + Cancel）

系统 SHALL 在选定分支后弹出 `Select Merge Variant` 对话框，**只包含**两个按钮：
`Revision Select`（附多行描述）+ `Cancel`。

#### Scenario: 按钮与描述
- **WHEN** 对话框展示
- **THEN** 显示 `Revision Select` 按钮及其下方描述
  （语义沿用原版 `Quick Manual Select`：显示目标分支的全部修订，已合并与未合并，供手动选择）

#### Scenario: 确认 / 取消
- **WHEN** 点击 `Revision Select` → **THEN** 进入图4 修订选择
- **WHEN** 点击 `Cancel` → **THEN** 直接结束，无副作用

### Requirement: R4 修订日志实时分页加载

系统 SHALL 在进入图4 时**直连 SVN 仓库**拉取修订日志，**首屏只加载 `settings.pageSize` 条（默认 100，对齐 TortoiseSVN）**；
后续通过「加载更多」按同一页大小继续向后加载，直到取完。系统 SHALL NOT 使用任何本地日志缓存，
也 SHALL NOT 一次性拉取全量历史。

#### Scenario: 首屏加载
- **WHEN** 打开修订选择对话框
- **THEN** 后台任务发起一次带 `--limit` 的 `svn log`（通过 `MergeCalculatorTask.loadChangeLists`），
  返回至多 30 条；加载中显示进度与「可取消」；完成后列表按修订号降序渲染

#### Scenario: 加载更多（游标模式，未填日期）
- **WHEN** 用户点击「加载更多」
- **THEN** 以上一页最后一条修订号为游标（`CHANGE_BEFORE`）继续查询并**追加**到列表；
  已到底时按钮禁用并提示

#### Scenario: 日期区间模式（已填日期）
- **WHEN** 过滤栏填写了日期区间
- **THEN** 一次 `svn log -r {dateTo}:{dateFrom} limit=0` 拉取该区间全部日志（后台任务、可取消），
  之后「加载更多」从已拉取结果中**内存分页**取下一页，不再重复访问仓库；
  单次结果 > 5000 条时状态栏提示「结果过多，建议缩小日期范围」但不阻断

#### Scenario: 过滤后不足一页（循环补页）
- **WHEN** 当前过滤条件下，一次原始查询匹配不足一页
- **THEN** 自动继续向后翻页补齐（最多 10 批），或确认已到底后结束，**不**出现"有匹配却显示为空"

#### Scenario: 加载失败
- **WHEN** 网络 / 认证 / 权限 / URL 不可达
- **THEN** 显示错误说明并提供**重试**；关闭对话框无副作用

### Requirement: R5 修订列表展示（原版布局 + 右侧变更路径）

系统 SHALL 以原版 `Merge from <branch>` 的布局展示：左侧修订列表（无表头、无网格，单行显示
勾选框 + 修订号 + 状态图标 + 日期 + 作者 + 提交说明），右侧为该修订的**变更路径浏览器**，
中部 `Splitter(0.7f)` 可拖动；上方为工具栏，下方为状态栏与动作按钮。

#### Scenario: 列表行内容
- **WHEN** 一页加载完成
- **THEN** 每行渲染修订号、提交日期、作者、提交说明（复用 `CommittedChangeListRenderer`），
  行首为勾选框

#### Scenario: 右侧变更路径
- **WHEN** 用户在列表中选中（高亮）某条修订
- **THEN** 右侧展示该修订影响的路径树（复用 `CommittedChangesBrowser`），可展开/查看差异

### Requirement: R6 勾选与批量选择

系统 SHALL 支持：单行点击勾选/取消、`Space` 键对选中行切换、工具栏「全选」/「全不选」；
勾选状态以修订号记录，**跨分页、跨过滤条件持久**（被过滤隐藏的已勾选项仍计入已选集合）。

#### Scenario: 单行勾选
- **WHEN** 点击行首勾选区域 / 选定行后按 Space
- **THEN** 切换该修订的勾选状态，状态栏与 OK 按钮实时更新

#### Scenario: 全选后排除个别
- **WHEN** 先点「全选」再取消其中 2 条
- **THEN** 已选集合 = 全部 - 2（采用 `QuantitySelection` 的稀疏表示，不需枚举全部修订号）

#### Scenario: 分页/过滤不丢勾选
- **WHEN** 勾选若干项后点击「加载更多」或修改过滤条件
- **THEN** 之前的勾选状态完整保留

### Requirement: R7 条件过滤（TortoiseSVN 式，新增）

系统 SHALL 在列表**顶部**提供过滤栏，支持以下条件的组合（AND）：

| 字段 | 语义 | 生效层 |
|---|---|---|
| 路径 | 变更路径包含/前缀匹配 | 客户端 |
| 作者 | 提交者（忽略大小写） | 客户端（IDEA log handler 内） |
| 版本 从 / 到 | 修订号闭区间 | **服务端上界**（起始游标）+ **客户端下界**（见 R4 模式 B） |
| Bug-ID | 由可配置正则从提交说明提取后比较，默认 `[A-Z][A-Z0-9]+-\d+` | 客户端 |
| 日期 从 / 到 | 提交日期闭区间（到日含当天全天） | **服务端**（`svn log -r {date}:{date}`），**优先级高于版本区间** |
| 信息 包含 / 排除 | 提交说明关键字，逗号分隔（包含为 AND，排除为 OR） | 客户端 |

日期控件采用 LGoodDatePicker：配色融入 IDEA 主题、隐藏自带切换按钮（点击日期框即弹日历）、
语言跟随界面文案（未装语言包 = 英文）；**一周起始日固定为周一**（中国习惯：周一在首列、
周日在末列），不随 locale 漂移——否则会出现"中文 IDE 周一起始、英文 IDE 周日起始"的不一致。

并 SHALL 提供「重置过滤」与（可选）「仅显示未合并」。

#### Scenario: 实时过滤
- **WHEN** 用户修改任一过滤条件（300ms 防抖）
- **THEN** **重置分页游标并从 HEAD 重新查询第一页**，列表与状态栏同步刷新；期间显示忙碌态

#### Scenario: 日期区间与版本区间并存（先日期，后版本）
- **WHEN** 日期区间与版本区间**同时**填写
- **THEN** ① **先用日期区间**从服务端限定拉取范围（模式 A：一次 `svn log -r {dateTo}:{dateFrom}`，`limit=0`）；
  ② **再在结果上按版本区间二次过滤**（客户端）；
  ③ 系统 SHALL NOT 把日期与版本条件同时塞给 `ChangeBrowserSettings` 的同一边界
  （源码 `createRevision(date, change, default)` 中 date 会静默覆盖 change，且会使分页游标失效导致死循环翻页）

#### Scenario: 仅填版本区间
- **WHEN** 只填版本区间（日期为空）
- **THEN** 「版本 到」作为服务端起始游标（须传 `revTo + 1`，因 `loadChangeLists` 会排除该边界修订号）；
  「版本 从」在客户端过滤，并在扫描到 `< revFrom` 时判定已到底（早停）

#### Scenario: 组合过滤
- **WHEN** 同时设置作者 + 日期区间 + 包含关键字
- **THEN** 仅保留同时满足全部条件（AND）的修订

#### Scenario: 过滤不丢勾选
- **WHEN** 已勾选项被过滤条件隐藏
- **THEN** 勾选状态保留并仍计入已选集合与状态栏

#### Scenario: 空结果
- **WHEN** 过滤后无任何匹配
- **THEN** 列表显示空提示，状态栏显示 0，并提供「重置过滤」

### Requirement: R8 结果汇总与回传

系统 SHALL 在对话框底部状态栏实时显示：当前已加载/显示条数、修订号区间、已选条数、已选修订影响的路径数；
`Merge Selected` 按钮在已选为 0 时禁用。

#### Scenario: 确认
- **WHEN** 已选 ≥1 条并点击 `Merge Selected`
- **THEN** 关闭对话框并把选中的 `List<SvnChangeList>` 交给合并执行阶段

#### Scenario: 已选 0 条
- **WHEN** 已选 0 条
- **THEN** `Merge Selected` 禁用

#### Scenario: 取消
- **WHEN** 点击 Cancel / 关闭对话框
- **THEN** 丢弃本次选择，不执行任何合并

### Requirement: R9 合并执行（复用原版执行链）

系统 SHALL 在确认后调用 `SvnIntegrateChangesTask` + `ChangeListsMergerFactory` 执行合并
（连续段自动压缩、`Revision.of(lowest-1) → Revision.of(highest)` 语义、进度、冲突解决、VFS 与 Local Changes 刷新）。

#### Scenario: 不连续修订
- **WHEN** 所选修订包含多段不连续区间
- **THEN** 按连续段逐段合并（`groupSequentialChangeLists = true`）

#### Scenario: 执行前保存
- **WHEN** 触发合并
- **THEN** 先 `FileDocumentManager.saveAllDocuments()`

#### Scenario: 冲突
- **WHEN** 合并产生冲突
- **THEN** 进入 IDEA 原生冲突解决流程（`ResolveWorker`），不静默失败

#### Scenario: 完成刷新
- **WHEN** 合并完成
- **THEN** Local Changes / 变更视图自动刷新（由 `SvnIntegrateChangesTask` 负责，本插件不再手工 `markEverythingDirty`）

### Requirement: R10 已合并 / 未合并 / 外来修订 状态图标（默认开启）

系统 SHALL **默认开启**合并状态计算，列表每行显示该修订相对当前分支的合并状态图标：
`已合并`（✔）/ `未合并`（无）/ `外来修订`（↳ 蓝箭头）/ `计算中`（占位）。
状态由后台线程复用原版 `OneShotMergeInfoHelper` / `MergeChecker` + `ListMergeStatus` 计算，
与 `ToBeMergedDialog.refreshListStatus` 完全一致；**不引入自创的状态判定或图标**。
状态图标**不影响**该行是否可勾选（复选框固定于行首最左，保证鼠标点击命中）。

#### Scenario: 默认开启
- **WHEN** 打开修订选择对话框
- **THEN** 后台任务先 `prepare()`，再逐条 `checkList()` 计算状态；每 10 条批量重绘一次；
  未完成期间显示 `REFRESHING` 占位图标

#### Scenario: 已合并文件标记
- **WHEN** 选中某修订
- **THEN** 右侧变更路径中，该修订内**已合并**的文件（`getNotMergedPaths` 的补集）追加 `[Already merged]` 标记

#### Scenario: 新增页补算
- **WHEN** 开启状态下点击「加载更多」
- **THEN** 新条目先显示占位图标，随后后台补算并刷新

#### Scenario: 仍可勾选已合并项
- **WHEN** 某条被标记为已合并
- **THEN** 该行仍可勾选并参与合并（SVN 对重复合并自行处理），仅作视觉区分

### Requirement: R11 设置项

系统 SHALL 提供设置页（`Settings/Preferences → Version Control → SVN Enhance Merge`），至少包含：

| 项 | 默认值 | 范围/说明 |
|---|---|---|
| 每页条数（首屏 + 加载更多步长） | `100` | 10 – 500（对齐 TortoiseSVN `NumberOfLogs`） |
| 计算已合并状态 | `true`（开启） | 见 R10 |
| Bug-ID 正则 | `[A-Z][A-Z0-9]+-\d+` | 可编辑 |
| 默认日期范围（天） | `0`（不限制） | >0 时过滤栏默认填「最近 N 天」 |

> 实现说明：注册为**应用级**服务（`applicationService` + `applicationConfigurable`）而非项目级——
> IDEA 的 VCS 设置惯例是应用级（内置 Subversion 的 `SvnConfigurable` 同处 Version Control 分组），
> 且 `projectConfigurable` 的实例由平台无参构造、拿不到 `Project` 句柄。
> 原表中「每页条数默认 30」已更正为 100（与 TortoiseSVN 及既有实现一致）。

#### Scenario: 修改每页条数
- **WHEN** 将每页条数改为 100 并重新打开修订选择
- **THEN** 首屏加载至多 100 条

### Requirement: R12 本地化（语言包感知）

系统 SHALL 将所有本插件自绘 UI 的可见字符串放入 `messages/EnhanceMergeBundle.properties`（英文基包）
与可选的语言变体（如 `_zh_CN.properties`），不在代码里硬编码；
并 SHALL 依据**平台是否装载语言包**决定使用哪个语言版本，使插件界面语言与 IDE 界面一致。

#### Scenario: 未装语言包的英文 IDE
- **WHEN** 中文 Windows（`Locale.getDefault()=zh_CN`）上运行未装语言包的 IDEA 2022.1
- **THEN** 本插件所有自绘文案为**英文**，SHALL NOT 出现「英文 IDE 里只有本插件弹中文」
- 实现要点：`DynamicBundle.findLanguageBundle() == null` ⇒ 用 **`Locale.ROOT`** 取基包
  （实测：请求 `Locale.ENGLISH` 会被默认 locale 回退成 `_zh_CN`，只有 `Locale.ROOT` 稳定拿基包）

#### Scenario: 安装中文语言包后自动切换
- **WHEN** 同一台机器装上中文语言包并重启 IDE
- **THEN** `findLanguageBundle() != null` 且 `DynamicBundle.getLocale()=zh` ⇒ 自动使用 `_zh_CN`
- 实测日志：`languagePack=true, platformLocale=zh … bundleLocale=zh_CN`

#### Scenario: 语言包存在但本插件无对应译文
- **WHEN** 装了某语言包（如日文），但本插件没有 `_ja.properties`
- **THEN** 回退到英文基包（SHALL NOT 因 JDK 默认 locale 回退而显示中文）

#### Scenario: 资源编码
- **WHEN** 读取含中文的 properties
- **THEN** 按 UTF-8 解码（IntelliJ properties 为 UTF-8，`ResourceBundle` 默认 ISO-8859-1 会乱码）

## MODIFIED Requirements

无（独立插件，不修改原插件任何既有行为；特别地**不写** `SvnConfiguration`，不影响原版 Merge From）。

## REMOVED Requirements

- 原「fork `plugins/svn4idea` 源码并修改」方案整体废弃（v1 已废弃，继续有效）。
- **删除**菜单/右键入口：不再挂 `SubversionGroup`（after `IntegrateFiles`）与 `Vcs.Operations.Popup`。
- **删除** v1 的「自绘分支 URL 输入框」「自绘合并方式确认页」「全量 HEAD→r1 日志拉取」「自绘 5 列 JBTable」
  「自建 dry-run → 确认 → `MergeClient` 逐段合并」「`RevisionItem.merged` 占位」「Shift 区间连选」
  （改为复用原版组件与原版交互语义）。
- **删除** v1 的 `RevisionRangeFormatter` 区间串展示（由状态栏文案替代）。

## ADDED Requirements（2026-10-18 官方插件规范对齐）

> 本节为一次面向 IntelliJ 官方插件规范的审查整改新增的需求，全部已实现并通过官方校验任务验证。

### Requirement: R13 分发包与上架元数据合规

#### Scenario: 不打包 Kotlin 标准库
- **WHEN** 执行 `gradlew buildPlugin`
- **THEN** zip 内不出现 `kotlin-stdlib*.jar` / `annotations*.jar`（由 `kotlin.stdlib.default.dependency=false` 保证）
- **AND** `gradlew verifyPluginConfiguration` 输出**零告警**（此前会报 stdlib 冲突）

#### Scenario: 图标与元数据齐备
- **THEN** zip 内 `<plugin>/pluginIcon.svg`（40×40）与 `pluginIcon_dark.svg` 位于**插件目录根部**（不是 jar 内 `META-INF/`）
- **AND** `plugin.xml` 含 `<vendor>`（email + url）、`<change-notes>`、`<description>`（含第三方库声明）

#### Scenario: 第三方许可可追溯
- **THEN** zip 内含 `THIRD-PARTY-LICENSES.txt`，含 LGoodDatePicker(MIT) 的许可全文
  （上游 jar 未内置 LICENSE，需由本插件补齐）

#### Scenario: 官方 API 兼容性校验可执行
- **WHEN** 执行 `gradlew runPluginVerifier`
- **THEN** 校验支持范围两端（`IC-2022.1` / `IC-2025.2`）；支持 `-PverifierLocalIde=<已解压 ideaIC>` 免下载复用
- **AND** 失败等级为 `COMPATIBILITY_PROBLEMS`（deprecated 只报告不阻断）

### Requirement: R14 生命周期与并发正确性

#### Scenario: 异步回调不写已销毁界面
- **WHEN** 后台任务（加载更多 / 全部显示 / 过滤重查）运行期间用户关闭了修订选择对话框
- **THEN** `onSuccess` / `onThrowable` / `onFinished` 及 `invokeLater` 块内均判 `isDisposed || project.isDisposed` 后直接返回

#### Scenario: mergeinfo 只初始化一次
- **WHEN** 多个后台任务并发触发合并状态计算
- **THEN** `mergeChecker.prepare()` 仅执行一次（`@Volatile` + 互斥锁保证"检查+prepare"原子化）
- **AND** 未执行 prepare 的线程在锁上等待后再 `checkList`，不使用半初始化的缓存

#### Scenario: 关闭项目不留残留
- **WHEN** 项目关闭
- **THEN** dispose 释放 Timer 与 messageBus 订阅，**并移除**注入到 SVN 面板的 `Enhance Merge…` 链接

#### Scenario: 快捷键注册不泄漏
- **THEN** `registerCustomShortcutSet` 传入对话框自身的 `Disposable`（禁止传 `null`）

#### Scenario: 按键消费生效
- **WHEN** 在版本列表上按空格切换勾选
- **THEN** `e.consume()` 写在 `keyPressed`（`keyReleased` 中消费无效），且切换逻辑只在 `keyReleased` 执行一次

### Requirement: R15 界面状态持久化与无障碍

#### Scenario: 过滤栏选项不丢
- **WHEN** 用户修改了过滤栏的勾选字段 / 日期区间开关 / 正则 / 大小写开关后关闭对话框
- **THEN** 下次打开沿用上次选择（持久化于应用级 `svnEnhanceMerge.xml`，设置页不提供 UI）

#### Scenario: 图标按钮可被朗读
- **THEN** 纯图标按钮（过滤栏清除按钮）设置 `accessibleContext.accessibleName`

### 明确不做（2026-10-18 决策）

- `getDimensionServiceKey` **不做**按项目隔离：带 `Project` 参数的重载仅 IDEA 2023.2+ 提供，
  与 `since-build=221` 冲突，用了会在 2022.1 上失败。
- 入口实现（反射调内部类 + 2s 定时扫组件树注入 UI）**暂不改**：待官方 `ToolWindowFactory` /
  相关扩展点调研结论 + 最终是否上架 Marketplace 的决策。
