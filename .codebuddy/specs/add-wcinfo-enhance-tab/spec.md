# Spec — Subversion Working Copies Information Enhance（并列标签页入口）

> v3。目标：**Marketplace 上架**——以官方 `changesViewContent` 扩展点注册并列标签页，
> 取代 v2 的反射注入入口（`add-revision-select-merge-ui` spec 的 R1）。
> 技术设计见 `.codebuddy/plan/WcInfoEnhanceTab-plan.md`（所有 svn4idea 事实已逐类核实）。

## Why

原入口通过反射调用 svn4idea internal 类 `WorkingCopyInfoPanel.getInfo()`、并定时扫描组件树
向原版面板注入 `LinkLabel`，违反官方「仅用公开 API / 不得侵入其他插件」的审核守则：
Plugin Verifier 探测不到反射（自动校验全 PASS），人工审核却是明确红线；且 svn4idea 已
unbundle 独立演进，面板一旦重写，注入静默失效，上架产品将直接收获差评。

因此改为与原版完全同构的官方做法：

```
VCS 工具窗
 ├─ Subversion Working Copies Information            ← 原版标签页，保持原样
 └─ Subversion Working Copies Information Enhance    ← 本插件标签页（Merge From → Enhance Merge…）
```

复刻原版面板的数据层、刷新节奏与全部链接行为，仅把「Merge From…」换成「Enhance Merge…」；
**不 fork、不修改 `plugins/svn4idea` 源码，零 internal API、零反射、零组件树扫描**。

## What Changes

- **删除** `wcinfo/WcInfoLinkInjector.kt`（含 private `LinkInstaller`：Timer / ContainerListener /
  组件树扫描 / 反射 / `removeInjectedLinks`）；`plugin.xml` 删除对应 `<postStartupActivity>`。
- **新增**：
  - `wcinfo/EnhanceWcInfoContent`：实现平台接口 `ChangesViewContentProvider`，
    含 `VisibilityPredicate`（`ProjectLevelVcsManager.checkVcsIsActive(SvnVcs.VCS_NAME)`）
    与 `DisplayNameSupplier`（走 `EnhanceMergeBundle`，语言包感知）。
  - `wcinfo/EnhanceCopiesPanel`：复刻原版 `CopiesPanel`——订阅 `SvnVcs.ROOTS_RELOADED`、
    `invokeRefreshSvnRoots()` + `isRefreshing` 防重入、后台线程
    `getWcInfosWithErrors()` / `getErrorRoots()` / `getSupportedFormats()`、按 path 排序、
    `removeAll()` 重建列表；`HyperlinkListener` 分发各链接。
  - `wcinfo/WcInfoRowBuilder`：`HtmlBuilder`/`HtmlChunk` 拼信息行（行序对齐原版，
    末行为 Enhance Merge…）。
  - `actions/RefreshEnhanceWcInfosAction` + toolbar group
    `SvnEnhanceMerge.WcInfoToolbar`（原版刷新动作硬编码找 `CopiesPanel`，不可复用）。
- **修改**：`plugin.xml` 增 `<changesViewContent>` 与工具栏 group；`messages/EnhanceMergeBundle*.properties`
  增面板文案 key；README / rules 同步入口描述。
- **不变**：`MergeFlow` / `EnhanceMergeAction` / 修订选择 / 合并执行链零改动；
  不写 `SvnConfiguration`；不引入 SVNKit。

## Impact

- Affected code（包 `com.weixf.svn.mergeenhance`，根目录 `d:\WorkSpace\AIProjects\SvnMergeEnhance\`）：

| 文件 | 变更 |
|---|---|
| `plugin.xml` | 删 `<postStartupActivity>`；增 `<changesViewContent>` + toolbar `<group>`/`<action>` |
| `wcinfo/WcInfoLinkInjector.kt` | **删除** |
| `wcinfo/EnhanceWcInfoContent.kt` | 新增 |
| `wcinfo/EnhanceCopiesPanel.kt` | 新增 |
| `wcinfo/WcInfoRowBuilder.kt` | 新增 |
| `actions/RefreshEnhanceWcInfosAction.kt` | 新增 |
| `messages/EnhanceMergeBundle.properties`（+ `_zh_CN`） | 增面板/标签页文案 key（英文、中文两份同步） |
| `README.md` | 入口描述改写（唯一入口 = Enhance 标签页） |
| `.codebuddy/rules/svn-enhance-merge.mdc` | 入口章节重写（去反射注入描述；澄清「不注册 action」仅指菜单入口） |
| 旧 spec `add-revision-select-merge-ui` | R1 标注被本 spec 取代（不删除历史记录） |

- 技术基线不变：since-build 221（IDEA 2022.1）~ 262.*；JDK 11；`plugins.set(listOf("Subversion"))`。

## 关键接入点（已从 svn4idea 源码核实可见性，2026-10-18）

| # | 能力 | 接入点 | 可见性 |
|---|---|---|---|
| E1 | 标签页注册 | `com.intellij.changesViewContent` EP（原版 plugin.xml:206-209 同款） | 平台公开 ✅ |
| E1 | 标签页宿主 | `ChangesViewContentProvider.initContent()` | 平台公开 ✅ |
| E1 | 可见性谓词 | `ProjectLevelVcsManager.checkVcsIsActive(SvnVcs.VCS_NAME)` | 公开 ✅ |
| E2 | WC 枚举 | `SvnVcs.getWcInfosWithErrors()` / `getAllWcInfos()`（SvnVcs.java:579） | public ✅ |
| E2 | 自动刷新 | `SvnVcs.ROOTS_RELOADED`（public Topic，SvnVcs.java:140）+ `invokeRefreshSvnRoots()`（:208） | public ✅ |
| E2 | 错误根 | `SvnFileUrlMapping.getErrorRoots()`（:31） | public 接口 ✅ |
| E2 | 升级格式 | `ClientFactory.createUpgradeClient().getSupportedFormats()`（ClientFactory.java:183）；`CopiesPanel.getUpgradeFormats(info, formats)`（public static） | public ✅ |
| E3 | 行数据 | `WCInfo`：`getPath/getUrl/getRepoUrl/getFormat/getStickyDepth/getType/isIsWcRoot/getRootInfo/hasError/getErrorMessage` | 全 public ✅ |
| E4 | Cleanup | `CleanupWorker(vcs, roots).execute()` | public ✅ |
| E4 | Configure Branches | `BranchConfigurationDialog.configureBranches(project, root)` | public static ✅ |
| E4 | Change Format | `ChangeFormatDialog` + `SvnFormatWorker` | public ✅ |
| E4 | Fix Depth | `SvnCheckoutProvider.checkout(project, file, url, HEAD, INFINITY, false, null, format)` | public ✅ |
| E5 | 合并入口 | `EnhanceMergeAction.performFor(project, wcInfo, anchor)`（本插件现有） | 本插件 ✅ |
| E2 | 刷新定位 | `ChangesViewContentManager.getActiveComponent(Class)` | 平台公开 ✅ |
| — | 不可引用 | `WorkingCopyInfoPanel`（internal）、`CopiesPanel` 的实例与内部面板（public 类但**不**引用其实例，仅复用其 public static `getUpgradeFormats`） | internal / 不引用 ❌ |
| — | 不可复用 | 原版刷新 action `Svn.RefreshWorkingCopies`（硬编码 `CopiesPanel`，RefreshWorkingCopiesAction.kt:9-11） | ❌ |

## ADDED Requirements

### Requirement: R1 并列标签页注册

系统 SHALL 通过 `com.intellij.changesViewContent` 扩展点注册标签页
**Subversion Working Copies Information Enhance**，位于 VCS 工具窗内原版标签页之后；
系统 SHALL 在 Subversion 未激活的项目中不显示该标签页（谓词与原版一致）。

#### Scenario: SVN 激活时可见
- **WHEN** 打开启用了 SVN 的项目并展开 VCS 工具窗
- **THEN** 出现标签页「Subversion Working Copies Information Enhance」，
  与原版「Subversion Working Copies Information」并列

#### Scenario: 无 SVN 时不可见
- **WHEN** 项目未启用 SVN（`checkVcsIsActive` 为 false）
- **THEN** 该标签页不出现

#### Scenario: 原版标签页不受影响
- **WHEN** 本插件启用
- **THEN** 原版标签页内容与行为完全不变（不注入任何组件）

### Requirement: R2 工作副本数据与刷新

系统 SHALL 复刻原版数据层：构造即订阅 `SvnVcs.ROOTS_RELOADED`；
`refresh()` 调 `invokeRefreshSvnRoots()` 并以 `isRefreshing` 防重入；
roots 变更后**在 pooled 线程**执行 `getWcInfosWithErrors()`（按 path 排序）+
`getErrorRoots()` + `getSupportedFormats()`，再经 `invokeLater(nonModal)` 回 EDT 重建列表。
系统 SHALL 提供工具栏刷新按钮（自建 action，定位本插件面板）。
系统 SHALL NOT 在 EDT 上触发任何 svn 调用。

#### Scenario: 首次打开
- **WHEN** 首次切到 Enhance 标签页
- **THEN** 面板已（或正在）加载工作副本列表，顺序按 path 升序，与原版一致

#### Scenario: roots 变更自动刷新
- **WHEN** 工作副本集合变化（检出/新增 root 等）触发 `ROOTS_RELOADED`
- **THEN** 列表后台自动重建，无需手动刷新

#### Scenario: 手动刷新
- **WHEN** 点击工具栏刷新按钮
- **THEN** 走与自动刷新相同的链路；刷新进行中重复点击无效（防重入）

#### Scenario: 错误副本
- **WHEN** 某工作副本 `hasError()`
- **THEN** 该副本信息行正常列出，url 行以红色显示错误信息，操作行（Cleanup/Configure Branches/Enhance Merge）不显示

#### Scenario: EDT 纯净
- **WHEN** 任意打开/刷新/点击路径执行后检查 `idea.log`
- **THEN** 不出现 `Synchronous execution on EDT`

### Requirement: R3 信息行展示（对齐原版 + Enhance Merge 替代 Merge From）

系统 SHALL 以 `HtmlBuilder`/`HtmlChunk` 渲染与原版一致的信息行，行序：
path（加粗）→ url → format → depth → nested type（external/switched 斜体）→
isWcRoot（斜体）→ Cleanup（format ≥ 1.7）→ Configure Branches → **Enhance Merge…**；
Change Format 链接在 `upgradeFormats.size > 1` 时附于 format 行，
Fix Depth 链接在非 `INFINITY` 深度且无错时附于 depth 行。
所有文案 SHALL 来自 `EnhanceMergeBundle`（**不**引用 `SvnBundle` 的 key）。

#### Scenario: 行序与条件行
- **WHEN** 渲染一个 1.8 格式、非 INFINITY 深度、有多候选升级格式的正常副本
- **THEN** 行序与原版逐行一致，且 format 行含 Change Format、depth 行含 Fix Depth、末行为 Enhance Merge…

#### Scenario: 精简格式 / INFINITY 深度
- **WHEN** 格式 < 1.7 或深度为 INFINITY
- **THEN** 对应条件行不出现（Cleanup 仅 ≥1.7 显示；Fix Depth 仅非 INFINITY 显示）

#### Scenario: Enhance Merge 醒目
- **WHEN** 渲染末行链接
- **THEN** 文案「Enhance Merge…」加粗显示（对齐原版 Merge From 的 bold）

### Requirement: R4 链接行为与原版一致

系统 SHALL 使 Cleanup / Configure Branches / Change Format / Fix Depth 的行为与原版
`CopiesPanel` 完全一致（含前置的 root 有效性检查 `checkRoot`）：
Cleanup → `CleanupWorker(vcs, [root]).execute()`；Configure Branches →
`BranchConfigurationDialog.configureBranches(project, root)`；Change Format →
`ChangeFormatDialog` 确认后 `SvnFormatWorker` 执行、成功后刷新列表；Fix Depth →
确认对话框后 `SvnCheckoutProvider.checkout(..., INFINITY, ...)`。
系统 SHALL NOT 在 Enhance 标签页提供原版 Merge From 入口。

#### Scenario: Cleanup
- **WHEN** 点击 Cleanup
- **THEN** 先保存文档，后台执行 svn cleanup，行为与原版一致

#### Scenario: Configure Branches
- **WHEN** 点击 Configure Branches
- **THEN** 打开与原版相同的分支配置对话框

#### Scenario: Change Format
- **WHEN** 点击 Change Format 并确认新格式
- **THEN** 后台转换格式，完成后列表自动刷新

#### Scenario: Fix Depth
- **WHEN** 点击 Fix Depth 并确认
- **THEN** 按原版语义以 INFINITY 深度重新 checkout（更新视图由 roots 变更事件触发）

#### Scenario: root 失效
- **WHEN** 任一链接操作时副本 root 的 VirtualFile 已失效
- **THEN** 弹出与原版一致的警告并中止，不发起 svn 调用

### Requirement: R5 Enhance Merge 入口

系统 SHALL 在点击信息行的 Enhance Merge… 时调用现有
`EnhanceMergeAction.performFor(project, wcInfo, anchor)` 启动 v2 合并流程
（分支弹窗 → Revision Select → 修订选择 → 合并），`anchor` 为本面板组件；
`wcInfo` 来自本面板自己的枚举结果（**零反射**）。

#### Scenario: 启动增强合并
- **WHEN** 点击某副本的 Enhance Merge…
- **THEN** 弹出与原版一致的 Select Branch 分支弹窗，后续流程与 v2 完全相同

#### Scenario: 多副本定位准确
- **WHEN** 项目含多个工作副本，点击第 2 个副本的 Enhance Merge…
- **THEN** 合并目标为第 2 个副本

### Requirement: R6 注入入口移除

系统 SHALL 删除 `wcinfo/WcInfoLinkInjector.kt` 及 `plugin.xml` 中的
`<postStartupActivity>` 注册；原版「Subversion Working Copies Information」面板中
SHALL NOT 再出现本插件注入的任何组件。

#### Scenario: 原版面板无残留
- **WHEN** 启用本插件并打开原版标签页
- **THEN** 每个工作副本仅有原版行（Cleanup / Configure Branches / Merge From），无 Enhance Merge 链接

#### Scenario: 文档同步
- **WHEN** 本需求实现完成
- **THEN** README 与 rules 的入口描述已改为「唯一入口 = Enhance 标签页」，并澄清「不注册 action」仅指菜单入口（自有工具栏 action 除外）

### Requirement: R7 本地化（语言包感知）

系统 SHALL 将标签页显示名（`DisplayNameSupplier`）与面板全部可见文案（含标点）放入
`messages/EnhanceMergeBundle.properties` 与 `_zh_CN.properties`，沿用
`EnhanceMergeBundle` 的语言包感知判定；`plugin.xml` 维持**不声明** `<resource-bundle>`。
新增 key SHALL 两份 properties 同步补齐（`BundleKeysTest` 对齐守护扩展到新 key）。

#### Scenario: 标签名随语言包切换
- **WHEN** 未装语言包 / 装中文语言包
- **THEN** 标签名分别为英文与中文（与既有对话框语言判定一致）

#### Scenario: 行文案完整
- **WHEN** 渲染面板
- **THEN** 无任何硬编码可见字符串（含冒号、省略号等标点）

### Requirement: R8 生命周期与规范合规

系统 SHALL：面板实现 `Disposable` 并 `Disposer.register(project, this)`，
dispose 时解绑 messageBus 订阅；所有 `invokeLater` 带显式 `ModalityState` 且守卫
（`isDisposed || project.isDisposed`）写在 lambda 内；`LOG.error` 仅用于真正内部错误；
符合项目既有规范（Alarm 非 Swing Timer、HiDPI 走 `JBUI`、主题色走 `UIUtil`、无硬编码颜色）。

#### Scenario: 项目关闭无泄漏
- **WHEN** 关闭项目
- **THEN** messageBus 订阅随面板 dispose 解绑，无定时器残留（本方案无兜底轮询）

#### Scenario: 异步回填守卫
- **WHEN** 后台枚举完成时项目/面板已销毁
- **THEN** `invokeLater` 块内守卫直接返回，不触碰已销毁组件

## MODIFIED Requirements

无（`MergeFlow` / 修订选择 / 合并执行链零改动；不写 `SvnConfiguration`）。

## REMOVED Requirements

- **移除** v2 spec R1「面板入口注入」：反射调 internal 类 + 组件树注入 + 2s 兜底定时器，
  由本 spec R1–R5（并列标签页）整体取代。
- **移除** v2 入口相关的全部配套机制：`ContainerListener` 自愈、`ToolWindowManagerListener`
  扫描、`removeInjectedLinks()`、`WcInfoLinkInjector` 及其 `postStartupActivity` 注册。

## 明确不做

- 不复刻原版「Svn Roots Detection Errors」通知组（错误在面板内红字呈现即可）。
- 不复用原版刷新 action（硬编码 `CopiesPanel`，见接入点表末行）。
- 不在 Enhance 标签页提供原版 Merge From（本标签页定位即"Enhance Merge 版工作副本信息"）。
- 首启「入口迁移引导」通知：默认不做，待定（见 plan §7）。
