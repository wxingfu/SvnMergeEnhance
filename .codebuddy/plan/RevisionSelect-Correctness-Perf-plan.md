# 方案 v1（第八轮）：修订选择面板的勾选正确性与性能整改

> **性质**：main 继承的既有问题整改轮（不改产品形态、不改入口、不动 UI 结构）。
> **实施状态：✅ 已实施（2026-10-22）**。验证：49 用例 0 失败 / 字节码 major 55 /
> verifyOnIde 2022.1 PASS（0 issues）/ runPluginVerifier IC-221 + IC-262 均 Compatible。
> 需求与验收见 `.codebuddy/specs/fix-revision-selection-perf/`。

---

## 1. 背景

第七轮走查后做了一次针对 `ui/RevisionSelectPanel.kt` 的全面 review，暴露出**继承自 main** 的
若干问题。按「合并工具的数据正确性 > 性能 > 释放 > 杂项」排序立项本轮。

**关键前提**：这些都不是第七轮引入的。第七轮只是把面板从模态对话框里原样抽出
（去注释的语义 diff 相似度 0.953，17 处差异全是预期的 `DialogWrapper`→`Disposable` 改造），
因此本轮修的是**旧账**，但旧账在「选中集很大」时会直接影响合并结果，值得单开一轮。

---

## 2. 问题清单与修法

| # | 级别 | 问题 | 修法 |
|---|---|---|---|
| B1 | **正确性** | `selectedChangeLists()` 以 `model.items`（**当前过滤后的视图**）为数据源 → 过滤前勾选的修订被**静默剔除**出合并集合（勾选框仍显示「已勾」） | 数据源改为**已加载全集**；语义抽到纯逻辑 `RevisionSelection` 并加单测 |
| M1/M2 | 性能 | 选中变化时在 **EDT** 上逐条算合并状态 + 全量 `collectChanges`（大选中集必卡） | 右栏刷新改为 `Alarm(POOLED_THREAD)` **防抖 200ms + 后台计算**，回 EDT 只换引用 + 重绘 |
| M3 | 性能 | `loadedAll` 用 `CopyOnWriteArrayList` 逐条 `add` → 每次 add 复制整个数组（`+500` 即 O(n²)，全在 EDT） | 改为**不可变列表 + `@Volatile` 原子替换**，一次构造新列表 |
| M4 | 释放 | 面板 dispose 不取消在飞后台任务；进度条「取消」实际不中止（loader 循环不感知 indicator） | dispose 中断过滤/合并状态任务与右栏 Alarm；`LogPageLoader.loadNextPageRaw` 增加**取消点**（每批之间 `checkCanceled`） |
| m1 | 杂项 | `reloadToken` 恒 0、守卫恒真（死代码，误导读者以为有作废机制） | **删除**该字段与四处比较（真实作废靠世代号 + `loadIndicator`） |
| m2 | 杂项 | `loadingMore` 可能永久卡 `true` → 「+N」按钮永久消失 | `onFinished` 里**无条件复位**（early-return 之前） |
| m3 | 杂项 | 原判断「`loadedAll` 可能写入重复修订号」**有误**：SVN 的「合并修订」按路径展开成同修订号的多条，**是不同条目，不能去重**（原版也保留批内重复） | **不批内去重**；语义抽到 `RevisionAccumulator` 并加单测 |
| m6 | 杂项 | 合并状态计算失败的行永远显示「刷新中」；`mergeStatusMap` 只增不清 | 失败落 `ListMergeStatus.COMMON`；渲染器区分「无记录(=计算中)」与「COMMON(=无图标)」；dispose 时清空缓存 |
| m7 | 杂项 | 达到 2 万条上限后「+N」静默失效（点了没反应） | 达上限时动作**置灰**并给出说明性 tooltip（新增文案 `status.load.limit.reached`） |

---

## 3. 语义约定（本轮确立，写入 `RevisionSelection` KDoc 与单测）

1. 勾选以**修订号**为准（稀疏集合，跨翻页 / 跨过滤保留）；
2. **过滤只是视图**：过滤不改变勾选，也不改变合并集合；
3. 「全选」有两种语义：
   - 无过滤条件 → 选中已加载全集（`QuantitySelection.setAll()`，之后可逐个排除）；
   - 有过滤条件 → **只选当前可见行**（显式模式）。

> 第 3 条的后半段是本轮**主动加的安全约束**：若「全选」在有过滤时仍按全集算，
> 用户以为只合并看到的几条、实际合并上万条——那是把 B1 修好之后引入的**新的**数据正确性问题。
> 无过滤时保持原语义（选中全集），与原版 `ToBeMergedDialog` 一致。

---

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `ui/RevisionSelection.kt`（新增） | 勾选语义纯逻辑：`resolve`（数据源=全集）/ `selectAll`（过滤感知），无 UI 依赖可单测 |
| `ui/RevisionSelectPanel.kt` | 勾选数据源改全集；右栏防抖+后台；`loadedAll` 不可变原子替换；dispose 中断任务；删除 `reloadToken`；`loadingMore` 无条件复位；状态图标失败态；上限置灰 |
| `log/LogPageLoader.kt` | `loadNextPageRaw` / `loadByCursorRaw` 增加可选 `ProgressIndicator`，每批之间 `checkCanceled()` |
| `flow/MergeFlow.kt` | 首屏加载把 indicator 透传给 loader（首屏也可取消） |
| `messages/*.properties` | 新增 `status.load.limit.reached`（中英） |
| `RevisionSelectionTest.kt`（新增） | 7 个用例：数据源=全集（**B1 回归看守**）/ 全选模式排除项 / 「全选」两种语义 |
| `LogPageLoaderTest.kt` | 新增 1 个用例：进度条取消真正中止分页 |
| `BundleKeysTest.kt` | 新增 key 守护 |

---

## 5. 验证

| 项 | 结果 |
|---|---|
| `clean test buildPlugin` | BUILD SUCCESSFUL，**49 用例 0 失败**（41 + 8），字节码 major 55，zip ≈ 119KB |
| `verifyOnIde 2022.1` | **PASS（0 issues）** |
| `runPluginVerifier` | IC-221 与 IC-262 均 **Compatible**（仍只有既有 `updateActionsImmediately` 1 处 deprecated） |

## 6. 遗留

- `CommittedChangesTreeBrowser.collectChanges` 现跑在后台线程（原在 EDT）。它是纯数据组装
  （构造 `Change`/`FilePath`，不碰 VFS），且历史上本插件就有「后台 + 防抖」的实现，但**没有官方
  线程安全承诺**——已在 `RevisionSelectPanel` 的 KDoc 中登记为「版本敏感点」。
- `mergeChecker`（svn4idea）本身无兼容承诺，跨 IDE 版本升级基线时需实测（本项目有
  `scripts/verify-plugin.ps1` 回归脚本兜底）。

---

## 7. 第九轮：修订清单被「合并源分支的副本」污染（原版自身缺陷，**不照抄**）

### 7.1 现象

版本选择列表里出现**行首没有状态图标**的条目（既非 +（未合并）也非 ?（未知/外来）），
用户判定为「版本缺失」。截图可见列表由**两段递减序列**交错组成：
一段带 ?（#29838、#29818 … #13175），一段**无图标**（#28638、#28611、#28544 … #13487）；
两段的提交说明里的【需求编号】互相重复（如 #7763 同时出现在两段）→ **同一需求在不同分支上的副本**。

### 7.2 根因（源码实证）

1. 取数：`MergeCalculatorTask.loadChangeLists` → `SvnCommittedChangesProvider.getCommittedChangesWithMergedRevisons`
   （内部 `includeMergedRevisions = true`）→ 经 `SvnMergeSourceTracker` + `MergeSourceHierarchyBuilder`
   把**合并源分支**的修订一并产出。
2. 状态：这些副本的路径相对**源分支**，相对目标路径做 mergeinfo 检查
   （`OneShotMergeInfoHelper.checkList`）一律 `NOT_EXISTS` → `ListMergeStatus.ALIEN`
   而 svn4idea 的 `ALIEN` **图标为 null**（`COMMON(SvnIcons.Common) / MERGED(Integrated) / NOT_MERGED(Notintegrated) / ALIEN(null) / REFRESHING(Unknown)`）
   → **行首无图标**。
3. 语义：它们在目标分支上并不存在 → **合并不进去**（原版照样让用户勾选）→ 选了却什么都没发生。

### 7.3 修法（不照抄原版）

- 加载器改用同一 provider 的 `getCommittedChanges`：内部 `includeMergedRevisions = false`，
  **只返回源分支自身的修订**；且它**直连实现不走缓存**（实时查仓库），游标（`CHANGE_BEFORE`）语义与原版一致。
- 渲染器：状态无图标时回落「刷新中」图标 → **每行都有状态图标**（第八轮曾把 ALIEN 渲染成无图标）。
- 合并状态计算任务不再被后续「+N」取消 → 先加载那批修订不会永远缺状态图标。
- 回归看守：`LogPageLoaderTest` 断言加载器不调用 `getCommittedChangesWithMergedRevisons`。

### 7.4 API 跨版本核实（221 / 262 均实证）

| API | 2022.1 | 2026.2.3 |
|---|---|---|
| `SvnCommittedChangesProvider.getCommittedChanges(ChangeBrowserSettings, RepositoryLocation, int)` | public ✓ | public ✓ |
| `ChangeBrowserSettings.CHANGE_BEFORE / USE_CHANGE_BEFORE_FILTER` | public 字段 ✓ | public 字段 ✓ |
| `SvnRepositoryLocation(Url)` | public ✓ | public ✓ |
| `SvnVcs.getCommittedChangesProvider()` | `CommittedChangesProvider<SvnChangeList, ChangeBrowserSettings>` ✓ | 同 ✓ |
