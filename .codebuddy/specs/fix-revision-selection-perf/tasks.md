# Tasks — 修订选择面板的勾选正确性与性能整改（第八轮）

> **实施状态：✅ 已实施（2026-10-22）**。验证结果见文末「实施记录」。
> 需求编号见同目录 `spec.md`（R1~R8）；方案见 `.codebuddy/plan/RevisionSelect-Correctness-Perf-plan.md`。
>
> **执行纪律**：每个任务完成跑 `gradlew test`；最终统一 §T7 全量验证。

---

## T1 抽纯逻辑（R1 / R2）

- [x] T1.1 新增 `ui/RevisionSelection.kt`：`resolve`（数据源=全集）+ `selectAll`（过滤感知）
- [x] T1.2 面板改用该纯逻辑；删除面板内的内联过滤实现

## T2 右栏后台化（R3）

- [x] T2.1 `alreadyMerged` 改 `@Volatile` + 不可变集合整体替换
- [x] T2.2 `onListSelectionChanged` 拆成「EDT 轻量」+「Alarm(POOLED_THREAD) 200ms 防抖后台重活」
- [x] T2.3 新增 `refreshRightPane(chosen)`：后台算已合并路径 + `collectChanges`，回 EDT 应用

## T3 全集累积（R4 / R6.2 / R6.3）

- [x] T3.1 `loadedAll` 改不可变列表 + `@Volatile` 原子替换（消除 COW 逐条 add 的 O(n²)）
- [x] T3.2 去重只比对「此前已加载的集合」，**保留批内重复项**（合并修订按路径展开）
- [x] T3.3 `loadingMore` 在 `onFinished` 无条件复位
- [x] T3.4 合并状态失败落 `ListMergeStatus.COMMON`；渲染器区分「无记录=计算中」与「COMMON=无图标」
- [x] T3.5 dispose 时清空 `mergeStatusMap`

## T4 释放与取消（R5）

- [x] T4.1 `dispose()` 中断过滤 / 合并状态任务 + 取消右栏 Alarm
- [x] T4.2 `LogPageLoader.loadNextPageRaw` 增加可选 `ProgressIndicator`，每批之间 `checkCanceled()`
- [x] T4.3 `MergeFlow.openRevisionSelect` 首屏加载透传 indicator

## T5 杂项（R6）

- [x] T5.1 删除 `reloadToken` 及其四处比较
- [x] T5.2 `filterToken` / `loadingMore` 加 `@Volatile`
- [x] T5.3 「+N」达上限时置灰 + 说明性 tooltip（新增文案 `status.load.limit.reached`）

## T6 测试（R7）

- [x] T6.1 新增 `RevisionSelectionTest`（7 用例，含 B1 回归看守）
- [x] T6.2 `LogPageLoaderTest` 新增「取消真正中止分页」（1 用例）
- [x] T6.3 `BundleKeysTest` 守护新 key
- [x] T6.4 既有 41 用例不回归

## T7 全量验证（R8）

- [x] T7.1 `gradlew clean test buildPlugin`：49 用例 0 失败、字节码 major 55
- [x] T7.2 `gradlew verifyOnIde "-PideaVersion=2022.1"`：PASS（0 issues）
- [x] T7.3 `gradlew runPluginVerifier`（IC-221 + IC-262）：均 Compatible

## T8 文档同步

- [x] T8.1 `CHANGELOG.md` 新增「第八轮」条目
- [x] T8.2 `README.md` 说明勾选与过滤的关系
- [x] T8.3 新增 `plan/RevisionSelect-Correctness-Perf-plan.md` + 本 spec 三件套
- [x] T8.4 **回归修复**：第八轮曾把「批内同修订号」当重复删掉，导致有合并修订的分支上表格少行
  （真机验证到的「版本缺失」）。已恢复原版去重语义（只跨页去重），语义抽到 RevisionAccumulator 并加 4 个单测。

---

## 实施记录（2026-10-22）

- T1~T6 完成：勾选数据源改全集；右栏防抖 + 后台；`loadedAll` 原子替换；dispose 中断任务；
  loader 取消点；删除 `reloadToken`；`loadingMore` 无条件复位；失败态图标；上限置灰。
- T7 验证：`clean test buildPlugin` → **49 用例 0 失败**、字节码 major 55、zip ≈ 119KB；
  `verifyOnIde 2022.1` → **PASS（0 issues）**；`runPluginVerifier` IC-221 + IC-262 → **均 Compatible**。
- 遗留：`collectChanges` 移到后台线程（无官方线程安全承诺，登记为版本敏感点）；
  `mergeChecker` 跨 IDE 版本无兼容承诺，升级基线时需实测。

## T9 清单污染根因修复（第九轮）

- [x] T9.1 定位根因：原版 getCommittedChangesWithMergedRevisons（includeMergedRevisions=true）带入合并源分支副本
- [x] T9.2 LogPageLoader 改用 getCommittedChanges（不含合并修订、不走缓存、游标语义一致）
- [x] T9.3 渲染器恢复「无状态图标 → 回落刷新中图标」（每行都有状态图标）
- [x] T9.4 合并状态计算任务不再被后续「+N」取消
- [x] T9.5 LogPageLoaderTest 回归看守（断言不调用含合并修订的接口）
- [x] T9.6 文档同步：CHANGELOG / spec R9 / plan §7 / README
