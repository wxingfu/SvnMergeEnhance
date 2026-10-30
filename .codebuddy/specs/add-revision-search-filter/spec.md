# Spec — 版本选择对话框原版化 + 显式搜索过滤（第五轮）

> 需求编号 R1~R11，供 `tasks.md` / `checklist.md` 引用。
> 方案背景与调研留档见 `.codebuddy/plan/RevisionSelect-AlignOriginal-AddSearch-plan.md`。

## R1 界面回归原版（布局与容器）

- **R1.1** 版本选择对话框的整体布局与原版 `ToBeMergedDialog` 一致：
  `Splitter(false, 0.7f)` + `dividerWidth = JBUI.scale(2)`；左栏 = 工具栏 + 搜索行 + 表格滚动面板；
  右栏 = `CommittedChangesBrowser`；底部标准 `Merge Selected` / `Cancel`。
- **R1.2** 表格：`TableView` + `ListTableModel(FAKE_COLUMN)` 单列、`setTableHeader(null)`、
  `setShowGrid(false)`、`setExpandableItemsEnabled(false)`、**平台默认行高**（删除自定义 22）。
- **R1.3** 无自定义底部状态栏（`createSouthAdditionalPanel` 移除）。
- **R1.4** 右栏变更树 + `[Already merged]` 装饰（`ChangeNodeDecorator.preDecorate`）保持原版行为。

## R2 行渲染回归原版

- **R2.1** 行渲染器 = `JPanel(BorderLayout)`：`JCheckBox`(WEST) + `CommittedChangeListRenderer`(CENTER)，
  背景/前景用 `UIUtil.getTableSelectionBackground(true)` / `getTableSelectionForeground(true)` /
  `getTableBackground()` / `getTableForeground()` 切换。
- **R2.2** 状态图标 provider：`settings.computeMergeStatus` 开启时返回
  `mergeStatusMap[number] ?: ListMergeStatus.REFRESHING`，关闭时返回 null；图标枚举与原版一致
  （`ListMergeStatus`：COMMON / MERGED / NOT_MERGED / ALIEN / REFRESHING）。
- **R2.3** 复选框贴单元格最左；点击命中区 = `Rectangle(cell.x, cell.y, checkboxWidth, cell.height)`
  （`checkboxWidth = JCheckBox().preferredSize.width`），与原版同构。
- **R2.4** 勾选语义 `QuantitySelection<Long>`（稀疏表示）不变；`Space` 键对选中行批量切换保留
  （`consume()` 写在 `keyPressed`）。
- **R2.5** **无命中高亮**：行文本不做任何分段着色。

## R3 显式搜索框（唯一新增）

- **R3.1** 位置：左栏**工具栏（Select All / +100 / +500）上方**独立一行（搜索框先入眼、操作按钮紧跟其下），不横跨右栏。
- **R3.2** 宽度：适度（`SEARCH_FIELD_WIDTH ≈ 450`，JBUI 缩放），左对齐；同一行内、紧随搜索框之后放轻量计数标签（R6，实现为 `BorderLayout.CENTER` 左缘，视觉上紧贴搜索框而非行尾）。
- **R3.3** 构成：`JBTextField`（1px 主题边框 + `UIUtil.getTextFieldBackground()`）+ 右侧内嵌 ✕ 按钮
  （`AllIcons.Actions.Close`，仅在有内容时可见；`accessibleContext.accessibleName` 必填）。
- **R3.4** placeholder 与 ✕ 的 tooltip 走文案包（`search.placeholder` / `search.clear`）。
- **R3.5** 输入防抖 300ms（平台 `Alarm(ThreadToUse.SWING_THREAD, disposable)`，禁用 Swing Timer）。
- **R3.6** `Esc` 或点 ✕ 清空并恢复完整列表；清空后焦点留在搜索框。
- **R3.7** 表格上的隐式 `TableViewSpeedSearch` **移除**（避免双轨与空格键消费竞争）。

## R4 过滤语义

- **R4.1** 匹配范围 = 全字段拼接串：提交说明 + 全部变更路径 + 作者 + 修订号 + 日期文本（`yyyy-MM-dd HH:mm`）。
- **R4.2** 语法：空格拆词，**全部 AND**；子串匹配；**大小写不敏感**。
- **R4.3** 空搜索词 = 不过滤。
- **R4.4** 不支持且不得引入：正则、大小写开关、`-`NOT、`+`OR、`"…"`短语、`!`取反、By 字段勾选、
  Bug-ID 单独字段、日期区间、版本区间。
- **R4.5** 过滤为**纯客户端**：只作用于已加载集合，**零远端请求**（对齐第四轮决策 1）；
  命中不足时通过 R6 计数标签提示，由用户自行 `+N` 加载更多。
- **R4.6** 过滤执行在**后台线程**（`executeOnPooledThread`），EDT 只 `setItems`；
  过期结果用 `filterToken` 丢弃；加载在途用 `reloadToken` + `loadIndicator.cancel()` 互斥。

## R5 工具栏与 +100/+500

- **R5.1** 工具栏动作且仅这些：Select all（`AllIcons.Actions.Selectall`）、Unselect all（`AllIcons.Actions.Unselectall`）、
  `+100`、`+500`。**删除** Show All / Refresh。
- **R5.2** `+N` 行为对齐原版：加载中隐藏 +500、禁用 +100/+500；完成后恢复并追加数据；
  `loader.allLoaded` 时两个 +N **隐藏**（非置灰）。
- **R5.3** 「全选/全不选」语义不变（`QuantitySelection.setAll/clearAll`）。
- **R5.4（2026-10-30 补充）** `+N` 两个按钮的**渲染方式必须与原版一致**：
  原版 `MoreAction` 实现 `CustomComponentAction`，工具栏里放的是**真实 Swing 按钮 `JButton`**
  （带边框的立体按钮，文字 `Load +100` / `Load +500`），**不是**平台默认的扁平 `ActionButton`；
  加载中按钮位置显示灰色 `Loading...`（`CommonBundle.getLoadingTreeNodeText()`）。
  实现见 `ui/RevisionSelectPanel.LoadMoreAction`（文案 key = `action.load.quantity`，`Load +{0}`）。

## R6 过滤反馈

- **R6.1** 搜索框同行右侧计数标签：`匹配 M / 已加载 N`（`status.search.count`）；
  无搜索词时不显示。
- **R6.2** 不恢复任何其它状态栏文字（原版没有）。

## R7 过滤 × 分页协同

- **R7.1** `+N` 的加载游标 = **已加载全集的最早修订号**（`LogPageLoader` 游标），
  **禁止**使用 model 可见末行（原版 `getLastNumber()` 在过滤下会跳段）。
- **R7.2** 新页并入 `loadedAll` → 按当前搜索词重过滤 → `model.setItems`；
  四种入口（首屏 / +100 / +500 / 改搜索词）统一走 `onDataLoaded`。
- **R7.3** 「已到底」以 `loader.allLoaded` 为准；总量上限（现有 `MAX_LOADED_ALL = 20_000`）保留但
  **不弹状态栏提示**（状态栏已删），可静默停止加载。
- **R7.4** 「重新整理」随 Refresh 按钮一并删除；数据刷新入口仅剩关闭重开对话框。

## R8 删除项汇总

- **R8.1** 日期区间全链路：开关、From/To 日历、`LogFilter` 日期字段、设置项 `defaultDateRangeDays` /
  `dateRangeEnabled`、相关文案与单测。
- **R8.2** LGoodDatePicker 依赖（含 JitPack 仓库评估）与 `THIRD-PARTY-LICENSES.txt`。
- **R8.3** By 字段弹窗、`LogFilterField` 枚举、`filterFields` 设置。
- **R8.4** 正则 / 大小写开关（`useRegex` / `caseSensitive`）与 `bugIdPattern` / Bug-ID 字段。
- **R8.5** 命中高亮全链路（`highlightRanges` 等）。
- **R8.6** Show All / Refresh 动作、自定义状态栏及其文案（`status.revision.select` 等）。
- **R8.7** 表格隐式 speed search 及其 LinkageError 降级块。
- **R8.8** 自绘 `RevisionCellRenderer`、自定义行高。

## R9 设置收敛

- **R9.1** 保留：`pageSize`、`computeMergeStatus`。
- **R9.2** 删除：`defaultDateRangeDays`、`dateRangeEnabled`、`filterFields`、`useRegex`、`caseSensitive`、
  `bugIdPattern`。
- **R9.3** 设置页 `EnhanceMergeConfigurable` 移除「默认日期范围（天）」「Bug-ID 正则」两行；
  持久化兼容（旧配置字段被忽略）须有单测或说明佐证。

## R10 文案与资源

- **R10.1** 新增：`search.placeholder`、`search.clear`、`status.search.count`（中英双包，UTF-8）。
- **R10.2** 删除 R8 所列全部 key；`BundleKeysTest` 双向对齐守护更新。
- **R10.3** 用户可见文本一律进文案包，代码内不得拼装半截翻译内容（既有规范保留）。

## R11 测试与构建

- **R11.1** `LogFilterTest`：新语义用例（多词 AND / 大小写不敏感 / 全字段命中：路径、作者、日期 / 空词不过滤）；
  删除日期、正则、高亮、字段、token 前缀语法用例。
- **R11.2** `LogPageLoaderTest`：保留游标/世代号用例；补「过滤态下追加不跳段」相关语义用例（可纯函数化验证）。
- **R11.3** `EnhanceMergeSettingsTest`：删 `defaultFromDate` / `dateRangeEnabledOnOpen` 用例。
- **R11.4** 构建：`gradlew clean test buildPlugin` 通过；产物字节码 `major = 55`；
  `verifyOnIde 2022.1` PASS；`runPluginVerifier`（221 + 262 两端）Compatible。

## 非目标（明确不做）

- 不引入 ToolWindow 级搜索、不改入口链路、不改合并执行链路、不做深历史服务端搜索、
  不做 Git Log 式多维度过滤、不恢复 TortoiseSVN 任何交互。
