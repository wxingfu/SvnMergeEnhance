# Spec — 复刻 Working Copies Information 面板，Merge From… 走 Enhance Merge（第七轮）

> 需求编号 R1~R12，供 `tasks.md` / `checklist.md` 引用。
> 方案背景与调研留档见 `.codebuddy/plan/EnhanceMerge-ToolwindowTab-plan.md`。
> **实施状态：✅ 已实施（2026-10-22）**。

## R1 注册与可见性（沿用第六轮，不变）

- **R1.1** `plugin.xml` 的 `<changesViewContent>`（`tabName="Enhance Merge"` + `className` +
  `predicateClassName` + `displayNameSupplierClassName`）不变；`postStartupActivity`（`EnhanceMergeStartup`）不变。
- **R1.2** 可见性 predicate = `ProjectLevelVcsManager.getInstance(project).checkVcsIsActive(SvnVcs.VCS_NAME)`。

## R2 工作副本列表（复刻 WC Info 面板核心）

- **R2.1** 标签页默认显示**工作副本列表**：`SvnVcs.getInstance(project).getAllWcInfos()`（公开 API）。
- **R2.2** 枚举在**后台线程**执行，`invokeLater` 回 EDT 填充；`project.isDisposed` 守卫写在 lambda 内；
  不得在 EDT 同步触发 `svn` 探测。
- **R2.3** 每个工作副本渲染为**独立信息块**（复刻原版 `WorkingCopyInfoPanel`）：
  `path`（加粗）/ `URL:`（出错时红色错误信息）/ `Format:` / `Depth:` 两列表 +
  `Working copy root`（斜体，仅 `isWcRoot`）+ 嵌套副本类型（external/switched 时斜体）；
  块内**左对齐、整体自上而下排列**（禁止居中/拉伸）。
- **R2.3.1** 信息块同时是**分支弹窗的锚点**（原版 `CopiesPanel.mergeFrom` 把 `infoPanel` 传给
  `SelectBranchPopup`，其内部 `showInCenterOf(myComponent)` → 弹窗显示在信息块中心）。
- **R2.4** `⟳ 刷新` 重新枚举（纯图标按钮，`accessibleContext.accessibleName` 必填）。
- **R2.5** 空列表 → 空态提示（`wc.no.working.copies`），不崩溃。

## R3 「Merge From…」入口（替换「Select Branch…/Enhance Merge…」）

- **R3.1** 每个工作副本行渲染**加粗的「Merge From…」链接**（对齐原版 `mergeFromRow()` 的 `.bold()`）。
- **R3.2** 文案 key = `wc.merge.from`（「Merge From…」/「合并自…」），走 `EnhanceMergeBundle`；**不使用**
  「Enhance Merge…」标签。
- **R3.3** 点击 → `MergeFlow.selectBranch(project, wcInfo, 该链接, onContext)`，锚点用该链接。
- **R3.4** URL 换算与两道守卫（`getRelativeUrl` 为 null / 同层级）沿用 `MergeFlow.BranchSelectedHandler`，语义不变。
- **R3.5** 点「Merge From…」**不调用**原版 `QuickMerge` 的任何流程方法。

## R4 修订选择：模态对话框（对齐原版 Merge From 体验，用户拍板 2026-10-22）

- **R4.1** 标签页**无修订选择态**；点「Merge From…」选完分支后，`onContext` 回调走
  `MergeFlow.openRevisionSelect(context)`。
- **R4.2** `openRevisionSelect(context)`：后台加载首屏（`LogPageLoader`）→ EDT 弹出**模态
  `RevisionSelectDialog`** → `showAndGet()` → `selectedChangeLists()` → `MergeExecutor.mergeWithLocalChangesPrompt(...)`。
- **R4.3** `RevisionSelectDialog` 恢复为 `DialogWrapper` 薄包装：`createCenterPanel() = RevisionSelectPanel.component`、
  `selectedChangeLists()` 委托面板、`isOKActionEnabled` 由 `onSelectionChanged` 回传。
- **R4.4** **恢复** `MergeVariantDialog`（对齐原版 `QuickMerge` 的「Select Merge Variant」步骤）：
  分支选定后先弹该对话框（仅 `Revision Select` + `Cancel`），选 `Revision Select` 才进入版本选择；
  `Cancel` 则无副作用退出。
- **R4.5** 对话框关闭 / 项目关闭的 dispose 守卫沿用 `RevisionSelectPanel` 既有实现。

## R5 修订选择与合并（复用，不改内部逻辑）

- **R5.1** 修订选择复用 `ui/RevisionSelectPanel`（搜索框 / 表格 / 工具栏四动作 / `+N` 分页 / 合并状态 /
  右栏变更树 / `[Already merged]`），**不改其内部逻辑**。
- **R5.2** 合并：对话框关闭后由 `MergeFlow` 读 `selectedChangeLists()` 并调 `MergeExecutor`；空选择不合并。
- **R5.3** 合并链路（本地改动提示 → `SvnIntegrateChangesTask` → 冲突解决/结果树）完全复用，不新增自创提示。

## R6 生命周期与资源释放（沿用第六轮）

- **R6.1** `EnhanceMergeTabContent`：`initContent()` 缓存面板、`disposeContent()` 释放。
- **R6.2** `EnhanceMergePanel : Disposable`，子资源注册到 `this`；`isDisposed` 用 `@Volatile` 标志。
- **R6.3** dispose 路径不因 `project.isDisposed` 提前返回（try/catch 兜 `AlreadyDisposedException`）。

## R7 入口唯一化（D1 ②，已实施，本轮延续）

- **R7.1** 标签页为唯一入口；无反射注入、无 `<action>`、零轮询。
- **R7.2** `EnhanceMergeStartup` 仅打印 i18n 日志（verify 就绪信号），不做 UI 注入。

## R8 其它 WC Info 动作（决策项 D2 —— 用户拍板 2026-10-22：Cleanup 复刻，Configure Branches 不复刻）

- **R8.1** **复刻 Cleanup**：复用原版**公开**的 CleanupWorker(SvnVcs, List<VirtualFile>) + execute()
  （2022.1 与 2026.x 签名一致，Plugin Verifier 双端 Compatible），不自行实现 svn 清理逻辑；
  链接仅在 ormat >= 1.7 时显示（对齐原版 cleanupRow()）；根无效时提示并中止（对齐原版 checkRoot）。
- **R8.2** **不复刻** Configure Branches（依赖 internal 的 SvnBranchConfigurationManager）与
  Properties（需 AnAction 宿主）；二者仍由原版 WC Info 面板提供。

## R9 本地化与文案

- **R9.1** 新增 key：`wc.merge.from`、`wc.no.working.copies`（中英双包，UTF-8）。
- **R9.2** 删除 key：`tab.select.root`、`tab.select.branch`、`tab.change.branch`、`tab.empty.hint`。
- **R9.3** 用户可见文本（含标点）一律进文案包，代码内不拼装半截翻译内容。

## R10 兼容性（since-build=221，公开 API 约束）

- **R10.1** 仅用公开 API：`changesViewContentEP`、`SvnVcs.getAllWcInfos`、`WCInfo`（getPath/getUrl 等）、
  `SelectBranchPopup`、`MergeContext`、`MergeChecker`、`SvnIntegrateChangesTask` 等。
- **R10.2** 禁用 2022.1 不存在的 API；禁用 internal（`WorkingCopyInfoPanel`、`SvnBranchConfigurationManager`）。
- **R10.3** 产物字节码 `major = 55`；不引入任何外部 Maven 坐标。

## R11 测试与构建

- **R11.1** 既有 40 用例 0 失败；`BundleKeysTest` 更新（删 `tab.select.*`/`tab.change.branch`/`tab.empty.hint`，
  新增 `wc.*`）。
- **R11.2** 可补纯函数化用例：工作副本列表排序/去重逻辑（若抽出）；其余走人工验收。
- **R11.3** `clean test buildPlugin` 通过；`verifyOnIde 2022.1` PASS；`runPluginVerifier` 双端 Compatible。

## R12 上架合规

- **R12.1** 纯公开 API、零反射、零轮询；入口标签「Merge From…」不冒充原版（独立 key、独立 tab）。
- **R12.2** 主题色走 `UIUtil.*`/`JBColor`；像素走 `JBUI.scale`；图标按钮有无障碍名。

## 非目标（明确不做）

- 不复刻 Cleanup / Configure Branches / Properties（R8）；不替换/隐藏原版 WC Info 标签；
- 不调用原版 `QuickMerge` 流程；不改 `RevisionSelectPanel` / `MergeExecutor` / `LogPageLoader` / `LogFilter` 内部逻辑；
- 不恢复 `MergeVariantDialog`（R4.4）；不展示工作副本修订号/变更状态（`WCInfo` 无 `getRevision()`）。
