# 方案 v5（第七轮）：复刻 Working Copies Information 面板，Merge From… 走 Enhance Merge

> **方向调整（2026-10-22）**：前几轮（v4）做的是「常驻 Enhance Merge 标签页 = 根选择器 + Select Branch… + 在标签页内修订选择」。
> 本轮**改为复刻「Subversion Working Copies Information」面板的功能**：标签页里列出项目的工作副本，
> 每行一个**原版样式的「Merge From…」链接**；点「Merge From…」后的**后续步骤仍走 Enhance Merge**
> （分支选择 → **模态**修订选择对话框 → 合并），修订选择**弹模态对话框**（对齐原版 Merge From 的体验，用户拍板 2026-10-22）。
>
> **实施状态：✅ 已实施（2026-10-22）**。验证：40 用例 0 失败 / 字节码 major 55 /
> verifyOnIde 2022.1 PASS / runPluginVerifier IC-221 + IC-262 均 Compatible。
> 需求 / 任务 / 验收见 `.codebuddy/specs/add-enhance-merge-tab/`。

---

## 1. 背景与目标

### 1.1 为什么调整方向

前几轮把标签页做成了「根选择器下拉框 + Select Branch… 按钮 + 标签页内修订选择」的工作台形态。
用户反馈希望更贴合**原版「Subversion Working Copies Information」面板**的形态：直接看到工作副本列表，
从每个工作副本的「Merge From…」链接进入合并——入口用原版标签（「Merge From…」而非「Enhance Merge…」），
点进去之后的步骤仍是本插件的 Enhance Merge（逐版本勾选 + 搜索过滤 + 合并状态 + 原版合并引擎），
且**修订选择弹模态对话框**（对齐原版 Merge From 的体验，而非常驻在标签页里）。

### 1.2 目标

1. 在 VCS 工具窗里提供一个**复刻 WC Info 面板功能**的标签页：列出项目全部工作副本及其关键信息。
2. 每个工作副本行提供**加粗的「Merge From…」链接**（对齐原版 `WorkingCopyInfoPanel.mergeFromRow()`）。
3. 点「Merge From…」→ [MergeFlow.selectBranch]（分支选择）→ **模态 [RevisionSelectDialog]**
   （复用 [RevisionSelectPanel]）→ [MergeExecutor]（合并），**后续步骤完全是 Enhance Merge**，不走原版 QuickMerge。
4. 保持上架合规：仅公开 API、零反射、零轮询（D1 ② 已落地，本轮延续）。

### 1.3 与原版面板的关系（并存，不替换）

- 本插件**无法也不应**替换/隐藏原版 WC Info 标签（它是 svn4idea 经同一 EP 注册的，且用户依赖它的
  Cleanup / Configure Branches / Properties 等动作）。
- 本标签页与原版 WC Info **并存**，只提供「工作副本信息块 + Cleanup + Merge From…（走 Enhance Merge）」这一
  增量能力；Configure Branches / Properties **不复刻**（见 §4.6 决策项 D2）。

---

## 2. 目标形态

```
┌ Subversion 工具窗（平台）─────────────────────────────────────────────┐
│ [Enhance Merge] [Local Changes] [Repository] [Incoming] [WC Info]   │
│ ┌ 标签页（本插件唯一入口）────────────────────────────────────────────┐ │
│ │ [⟳ 刷新]  ← 工作副本列表（复刻 WC Info 面板）                         │ │
│ │ D:\proj\trunk            svn://…/trunk                            │ │
│ │   Merge From…                                                    │ │
│ │ D:\proj\branches\feat     svn://…/branches/feat                   │ │
│ │   Merge From…                                                    │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
   ── 点「Merge From…」→ SelectBranchPopup → Select Merge Variant → RevisionSelectDialog（模态）→ MergeExecutor
```

- 标签页 = **纯工作副本列表**（path + URL + 每行「Merge From…」），**无状态机、无标签页内修订选择**。
- 点「Merge From…」后，修订选择在**模态对话框**里完成（对齐原版 Merge From 的体验）。

---

## 3. 原版 WC Info 面板实证（2026-10-22 核实）

| 事实 | 证据 |
|---|---|
| WC Info 面板的 tab 由 svn4idea 经 `changesViewContentEP` 注册，`initContent()` 返回 `new CopiesPanel(project)` | `WorkingCopiesContent.java`（2022.1 jar 与最新源码一致） |
| 外层列表容器 `CopiesPanel` 是 `public class`（extends `SimpleToolWindowPanel`） | 最新源码 `CopiesPanel.java:70` |
| 每个工作副本的**信息行** `WorkingCopyInfoPanel` 是 Kotlin **`internal`** 类，编译期不可引用 | 最新源码 `WorkingCopyInfoPanel.kt:27` |
| 每行动作链接：Cleanup / Configure Branches / **Merge From（加粗）** / Properties | `WorkingCopyInfoPanel.kt:49-52`（`mergeFromRow()` 用 `message("action.name.merge.from.ellipsis")` + `.bold()`） |
| 工作副本枚举公开 API：`SvnVcs.getAllWcInfos()` → `List<WCInfo>` | 2022.1 `SvnVcs` javap（`public List<WCInfo> getAllWcInfos()`） |
| `WCInfo` 公开 getter：`getPath()` / `getUrl()` / `getRepoUrl()` / `getRootInfo()` / `getFormat()` / `getType()` / `hasError()` 等（**无 `getRevision()`**） | 2022.1 `WCInfo` javap |

> 结论：**不复用** `CopiesPanel` / `WorkingCopyInfoPanel`（后者 internal；前者虽 public，但复用后无法
> 在不反射的前提下把「Merge From…」指向我们的流程），改用 `getAllWcInfos()` + `WCInfo` 公开 API 自建列表。

---

## 4. 方案设计

### 4.1 注册与可见性（沿用 v4，不变）

`plugin.xml` 的 `<changesViewContent>`（`tabName="Enhance Merge"` + `className` + `predicateClassName` +
`displayNameSupplierClassName`）**不变**；`postStartupActivity`（`EnhanceMergeStartup`，仅打印 i18n 日志）不变。
可见性 predicate = `checkVcsIsActive(SvnVcs.VCS_NAME)`（SVN 项目才显示）。

### 4.2 工作副本列表（新，替换 v4 的「根选择器 + Select Branch…」）

- 用 `SvnVcs.getInstance(project).getAllWcInfos()`（公开）枚举工作副本，**后台线程**执行（`allWcInfos`
  可能触发工作副本重探测，不得在 EDT 同步跑 svn 命令），`invokeLater` 回 EDT 填充。
- 每行展示：`path`（本地路径）+ `url`（仓库 URL）；`format`/`type` 作为可选次要信息（`WCInfo` 有 getter）。
- `⟳ 刷新` 重新枚举（纯图标按钮，`accessibleContext.accessibleName` 必填）。
- 空列表（非 SVN 项目，双保险）→ 空态提示。

### 4.3 「Merge From…」链接（入口，替换 v4 的「Select Branch…」按钮）

- 每个工作副本行渲染一个**加粗的「Merge From…」链接**（对齐原版 `mergeFromRow()` 的 `.bold()`）。
- 点击 → `MergeFlow.selectBranch(project, wcInfo, 锚点, onContext)`（现有方法）。
- **锚点 = 该工作副本的整个信息块**（不是「Merge From…」链接）：原版 `CopiesPanel.mergeFrom` 把
  `infoPanel` 传给 `SelectBranchPopup.showForBranchRoot`，而后者内部是
  `listPopup.showInCenterOf(myComponent)`——弹窗显示在**信息块中心**（随窗口宽度变化）。
  传链接会退化成「弹窗贴着左上角的链接」，与原版不符。
- 文案 key 走本插件 `EnhanceMergeBundle`（`wc.merge.from`），文本对齐原版 `action.name.merge.from.ellipsis`
  （「Merge From…」/「合并自…」），**不使用「Enhance Merge…」**。

### 4.4 修订选择：模态对话框（用户拍板 2026-10-22，对齐原版 Merge From 体验）

- 标签页**无修订选择态**；点「Merge From…」选完分支后，`onContext` 回调里走
  `MergeFlow.openRevisionSelect(context)`：后台加载首屏 → **弹出模态 `RevisionSelectDialog`**。
- 因此需**恢复** D1 ② 已删除的两个元素（作为「模态入口」而非「注入入口」，仍合规）：
  - `ui/RevisionSelectDialog`：`DialogWrapper` 薄包装，`createCenterPanel() = RevisionSelectPanel.component`；
  - `MergeFlow.openRevisionSelect(context)`：后台首屏加载 → `RevisionSelectDialog.showAndGet()` →
    `selectedChangeLists()` → `MergeExecutor.mergeWithLocalChangesPrompt(...)`。
- **恢复** `MergeVariantDialog`（**第七轮走查后修正**：原版分支选择后走
  `QuickMerge.execute() → selectMergeVariant`，即先弹「Select Merge Variant」再进版本选择；
  本插件同样提供该步骤，仅保留 `Revision Select` 一个选项 + `Cancel`）。
- 对话框关闭 / 项目关闭的 dispose 守卫沿用 `RevisionSelectPanel` 既有实现。

### 4.5 生命周期与资源释放（沿用 v4）

`EnhanceMergeTabContent`：`initContent()` 缓存面板 + `disposeContent()` 释放；`EnhanceMergePanel` 实现
`Disposable`，子资源注册到 `this`；`isDisposed` 用 `@Volatile` 标志（`Disposer.isDisposed` 已废弃，javap 实证）；
dispose 路径不因 `project.isDisposed` 提前返回。

### 4.6 其它 WC Info 动作（决策项 D2：**Cleanup 复刻**，其余不复刻 —— 用户拍板 2026-10-22）

| 动作 | 原版实现 | 复刻成本 | 结论 |
|---|---|---|---|
| Cleanup | `CleanupWorker(SvnVcs, List<VirtualFile>)` + `execute()`（**public**，非 AnAction） | 低——直接复用，无需 DataContext | **✅ 复刻**（用户拍板 2026-10-22；仅 1.7+ 工作副本显示链接） |
| Configure Branches | `SvnBranchConfigurationManager`（**internal**） | 违反公开 API 约束 | **不复刻** |
| Properties | `ShowPropertiesAction`（AnAction） | 需数据上下文 | **不复刻** |

> 只做「工作副本列表 + Merge From…（走 Enhance Merge）」，其余动作由原版 WC Info 面板承担。

### 4.7 与原版 QuickMerge 的关系（再次明确）

点「Merge From…」**不调用**原版 `QuickMerge.execute/merge/mergeAll` 等流程方法——分支选择走
`SelectBranchPopup`，修订选择走本插件 `RevisionSelectDialog`（复用 `RevisionSelectPanel`），合并走
`MergeExecutor`（`LocalChangesPromptTask` 宿主 + `SvnIntegrateChangesTask`）。

---

## 5. 改动清单（相对当前分支上的 v4 实现）

### 5.1 修改

| 文件 | 说明 |
|---|---|
| `tab/EnhanceMergePanel.kt` | 列表态由「根选择器下拉框 + Select Branch… 按钮 + 标签页内修订选择态」改为**纯工作副本列表 + 每行 Merge From… 链接**；删除状态机 / `RevisionSelectPanel` 宿主 / `Merge Selected` / 双态切换 |
| `ui/RevisionSelectDialog.kt` | **恢复**（D1 ② 删除过）：`DialogWrapper` 薄包装，委托 `RevisionSelectPanel` |
| `ui/MergeVariantDialog.kt` | **恢复**（D1 ② 删除过；第七轮走查确认原版确有此步）：「Select Merge Variant」 |
| `flow/MergeFlow.kt` | **恢复** `openRevisionSelect(context)`（后台首屏 → 弹模态 `RevisionSelectDialog` → `MergeExecutor`） |
| `messages/*.properties` | 删 `tab.select.root` / `tab.select.branch` / `tab.change.branch` / `tab.empty.hint`；新增 `wc.merge.from` / `wc.no.working.copies`（保留 `tab.name.enhance.merge` / `tab.refresh.roots`） |
| `BundleKeysTest.kt` | 更新 key 集合（删 `tab.select.*`，新增 `wc.*`） |

### 5.2 保留（明确不动）

- `tab/EnhanceMergeTabContent.kt`（EP 注册 + predicate + displayNameSupplier）、`EnhanceMergeStartup`；
- `ui/RevisionSelectPanel`（**被 `RevisionSelectDialog` 复用**，逻辑不改）、`ui/RevisionSearchField`、
  `log/*`、`merge/MergeExecutor`、`settings/*`、`EnhanceMergeBundle`；
- `plugin.xml` 的 `<changesViewContent>` 与 `<postStartupActivity>`（D1 ② 已定，不再改）。

---

## 6. 文案（messages）变更

- **新增**：`wc.merge.from`（「Merge From…」，对齐原版标签）、`wc.no.working.copies`（空态提示）。
- **删除**：`tab.select.root`、`tab.select.branch`、`tab.change.branch`、`tab.empty.hint`
  （随「根选择器 + Select Branch… + 标签页内修订选择」形态一并移除）。
- **保留**：`tab.name.enhance.merge`、`tab.refresh.roots`、`button.merge.selected`、`action.*`、
  `search.*`、`status.search.count`、`label.already.merged`、`progress.*`、`error.*`、`message.no.revisions`。

---

## 7. 调研留痕（备选方案与未采用原因）

| 候选 | 结论 | 依据 |
|---|---|---|
| A. 复用原版 `CopiesPanel`（public） | **未采用**：复用后「Merge From…」仍指向原版 QuickMerge，无法在不反射的前提下改指向我们的流程 | §3 实证 |
| B. 反射改写原版 `WorkingCopyInfoPanel` | **未采用**：internal 类 + 反射注入，正是 D1 ② 已消除的违规手段 | D1 ② 决策 |
| C. 替换/隐藏原版 WC Info 标签 | **未采用**：无法禁用其它插件的 EP；且用户依赖其 Cleanup/Configure Branches 等动作 | §1.3 |
| D. 复刻 Configure Branches / Properties | **未采用（用户拍板）**：internal API 或需 AnAction 宿主，收益低（原版面板已提供）；**Cleanup 例外——已复刻**（公开 `CleanupWorker`） | §4.6 D2 |
| E. 修订选择常驻标签页内 | **未采用（用户拍板）**：改为弹模态对话框，对齐原版 Merge From 体验 | 用户拍板 2026-10-22 |

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| `getAllWcInfos()` 在 EDT 触发 svn 探测 | 后台枚举 + invokeLater 回填（§4.2） |
| 工作副本列表信息不全（`WCInfo` 无 `getRevision()`） | 只展示 path + URL（+ 可选 format），不承诺展示修订号/状态 |
| 恢复 `RevisionSelectDialog` / `openRevisionSelect` 后回归 | 复用既有 `RevisionSelectPanel`（逻辑零改动）+ 既有 40 单测 + verifyOnIde 兜底 |
| 入口标签「Merge From…」与原版重名可能让用户混淆 | 文案注释明确「本插件入口，走 Enhance Merge」；`wc.merge.from` 为独立 key，不触碰原版 bundle |

---

## 9. 验证计划

1. `gradlew clean test`：既有 40 用例 0 失败；`BundleKeysTest` 更新 key 集合；
2. `gradlew buildPlugin`：`javap -verbose … MergeFlow | grep "major version"` = **55**；
3. `gradlew verifyOnIde "-PideaVersion=2022.1"`：PASS（0 issues）；
4. `gradlew runPluginVerifier -PverifierLocalIde=…2022.1,…2026.2.3`：两端 Compatible；
5. 人工沙箱走查（见 `checklist.md`）：工作副本列表、每行「Merge From…」、选分支 → 模态修订选择对话框、
   勾选/搜索/合并状态/右栏变更树/Merge Selected 全链路。

---

## 10. 文档同步清单（实施完成后）

- [ ] `README.md`：入口/流程/文件树同步（列表 + Merge From… + 模态对话框）；
- [ ] `CHANGELOG.md`：新增「第七轮」条目；
- [ ] 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc`：新增第七轮记录；
- [ ] 本文档标注「已实施」并补验证结果；`spec/tasks/checklist` 全勾选。

### 7.x 原版「版本选择清单」的已知缺陷（第九轮定位，**不照抄**）

原版合并对话框的清单会混入「**合并源分支的修订副本**」：取数走
`MergeCalculatorTask.loadChangeLists` → `getCommittedChangesWithMergedRevisons`
（内部 `includeMergedRevisons = true`），经 `SvnMergeSourceTracker` + `MergeSourceHierarchyBuilder`
把合并源分支的修订一并产出（=「同一需求在别的分支上的副本」，路径相对源分支、提交说明重复）。
后果：① 这些副本对目标路径做 mergeinfo 检查一律 `NOT_EXISTS` → 状态 `ALIEN`，
而 svn4idea 的 `ALIEN` **图标为 null** → 列表出现「行首没有状态图标」的条目；
② 它们在目标分支上并不存在 → **合并不进去**（原版照样让勾选）→ 用户观感「版本缺失」。

**本插件的做法**：清单只取**源分支自身**的修订（`getCommittedChanges`，`includeMergedRevisons=false`，
且不走缓存），并保证每行都有状态图标。回归由 `LogPageLoaderTest` 断言「绝不调用含合并修订的接口」看守。
