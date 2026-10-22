# Spec — 修订选择面板的勾选正确性与性能整改（第八轮）

> 需求编号 R1~R8，供 `tasks.md` / `checklist.md` 引用。
> 方案与调研留档见 `.codebuddy/plan/RevisionSelect-Correctness-Perf-plan.md`。
> **实施状态：✅ 已实施（2026-10-22）**。

## R1 勾选集合的数据源（**正确性，最高优先**）

- **R1.1** `selectedChangeLists()` 的数据源必须是**已加载全集**（`loadedAll`），
  **不是** `model.items`（当前过滤后的视图）。
- **R1.2** 过滤只是视图：过滤前后勾选集合与合并集合**不变**。
- **R1.3** 语义实现收敛到**无 UI 依赖的纯逻辑**（`RevisionSelection`），并有单测看守。

## R2 「全选」语义

- **R2.1** 无过滤条件 → 选中已加载全集（`QuantitySelection.setAll()`，稀疏「全选起点」）。
- **R2.2** 有过滤条件 → **只选当前可见行**（显式模式），避免误把被过滤掉的上万条纳入合并集合。
- **R2.3** 「全不选」清空全部勾选，语义不变。

## R3 右栏刷新（性能）

- **R3.1** 右栏（变更文件树 + `[Already merged]` 标记）刷新必须**离开 EDT**：
  合并状态计算与 `collectChanges` 都在后台线程做。
- **R3.2** 必须**防抖**（200ms）：连续勾选/取消只算最后一次。
- **R3.3** 后台算完后回 EDT 只做「换引用 + 重绘」，不在 EDT 上做重活。
- **R3.4** `alreadyMerged` 用 `@Volatile` + 不可变集合整体替换（后台写 / EDT 读无竞态）。

## R4 已加载全集的累积（性能）

- **R4.1** `loadedAll` 改为**不可变列表 + `@Volatile` 原子替换**，禁止逐条 `add` 触发的 O(n²) 拷贝。
- **R4.2** 去重：同一批次内重复修订号不得进集合两次。

## R5 释放与取消

- **R5.1** 面板 `dispose()` 必须中断在飞任务：分页加载、过滤、合并状态计算、右栏 Alarm。
- **R5.2** 进度条上的「取消」必须**真正中止**分页：分页循环每批之间检查取消点。
- **R5.3** 首屏加载同样可取消（indicator 透传到 loader）。

## R6 杂项清理

- **R6.1** 删除恒真的死守卫（`reloadToken`）及其比较。
- **R6.2** `loadingMore` 在 `onFinished` 中**无条件复位**（「+N」按钮不得永久消失）。
- **R6.3** 合并状态计算失败要落**明确状态**（不显示误导性的「刷新中」）；状态缓存在 dispose 时清理。
- **R6.4** 达到加载总量上限时「+N」动作置灰并给出**说明性提示**（不再静默失效）。
- **R6.5** `filterToken` 等跨线程令牌必须 `@Volatile`。

## R7 测试

- **R7.1** 新增 `RevisionSelectionTest`：数据源=全集（B1 回归看守）、全选模式排除项、「全选」两种语义。
- **R7.2** `LogPageLoaderTest` 新增「进度条取消真正中止分页」用例。
- **R7.3** `BundleKeysTest` 守护新增文案 key。
- **R7.4** 既有 41 个用例不得回归。

## R8 验证与合规

- **R8.1** `clean test buildPlugin` 通过；用例 0 失败；字节码 major 55。
- **R8.2** `verifyOnIde 2022.1` PASS（0 issues）。
- **R8.3** `runPluginVerifier` IC-221 与 IC-262 均 Compatible。
- **R8.4** 平台规范：防抖用平台 `Alarm`（不引入 `javax.swing.Timer`）；后台→EDT 均带 `ModalityState` 与过期条件；
  无 `catch (Throwable)`；无内部 API。

## 非目标（明确不做）

- 不改 UI 结构与产品形态（仍是「标签页 → Merge From… → 模态修订选择对话框」）；
- 不改 `QuantitySelection`（svn4idea 公开类）的语义；
- 不改 `RevisionSelectPanel` 的搜索框、分页、右栏视图结构；
- 不为 `mergeChecker` / `CommittedChangesTreeBrowser` 的跨版本行为做兜底（登记为版本敏感点即可）。
