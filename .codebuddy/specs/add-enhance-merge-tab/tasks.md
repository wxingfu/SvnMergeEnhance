# Tasks — 复刻 Working Copies Information 面板，Merge From… 走 Enhance Merge（第七轮）

> **实施状态：✅ 已实施（2026-10-22）**。验证结果见文末「实施记录」。
> 需求编号见同目录 `spec.md`（R1~R12）；方案见 `.codebuddy/plan/EnhanceMerge-ToolwindowTab-plan.md`。
>
> **环境基线**（不可偏离）：Gradle JDK 11、`GRADLE_USER_HOME=G:\GradleRepository`、
> `intellij { version = "2022.1"; type = "IC" }` + 内置 Subversion、不引入 SVNKit / 外部坐标、字节码 major 55。
>
> **执行纪律**：每任务完成跑 `gradlew test`；同文件多改**串行**；最终统一 §T8 全量验证。

---

## T1 基线与分支准备

- [ ] T1.1 记录基线：`gradlew clean test` 用例数（预期 40）、`buildPlugin` 产物大小
- [ ] T1.2 确认工作树干净、记录 HEAD

## T2 工作副本列表（R2）

- [ ] T2.1 `tab/EnhanceMergePanel.kt`：删除 v4 的「根选择器 `ComboBox` + `Select Branch…` 按钮 + `branchLabel` +
      `Change Branch` + 标签页内 `RevisionSelectPanel` 宿主 + `Merge Selected` + 状态机」整体结构
- [ ] T2.2 新增工作副本列表：后台枚举 `SvnVcs.getInstance(project).getAllWcInfos()` → invokeLater 回 EDT；
      每行渲染 `path` + `url`（`JBList` + 自定义 `ListCellRenderer`，或 `TableView` + `ListTableModel`）
- [ ] T2.3 `⟳ 刷新`（`tab.refresh.roots` 无障碍名）；空列表空态（`wc.no.working.copies`）
- [ ] T2.4 `gradlew test` 绿

## T3 「Merge From…」链接（R3）

- [ ] T3.1 每行渲染**加粗的「Merge From…」链接**（`wc.merge.from`，复用 `LinkLabel`）
- [ ] T3.2 点击 → `MergeFlow.selectBranch(project, wcInfo, link, onContext = { context -> MergeFlow.openRevisionSelect(context) })`（锚点 = 该链接）
- [ ] T3.3 确认不调用 `QuickMerge`；URL 换算/守卫复用 `BranchSelectedHandler`
- [ ] T3.4 `gradlew test` 绿

## T4 恢复模态修订选择（R4 / R5）

- [ ] T4.1 **恢复** `ui/RevisionSelectDialog.kt`：`DialogWrapper` 薄包装（`createCenterPanel = panel.component`、
      `selectedChangeLists()` 委托、`isOKActionEnabled = onSelectionChanged`、`getDimensionServiceKey` 保留）
- [ ] T4.2 **恢复** `flow/MergeFlow.openRevisionSelect(context)`：后台首屏加载（`LogPageLoader`）→
      EDT 弹 `RevisionSelectDialog.showAndGet()` → `selectedChangeLists()` → `MergeExecutor.mergeWithLocalChangesPrompt(...)`；
      `onSuccess`/`onThrowable` 带 `project.isDisposed` 守卫
- [ ] T4.3 **不恢复** `MergeVariantDialog`（唯一合并方式即逐版本勾选）
- [ ] T4.4 `gradlew test` 绿

## T5 文案（R9）

- [ ] T5.1 两套 `messages/*.properties`：删 `tab.select.root` / `tab.select.branch` / `tab.change.branch` / `tab.empty.hint`；
      新增 `wc.merge.from` / `wc.no.working.copies`
- [ ] T5.2 `BundleKeysTest` 更新（删 `tab.select.*` 等，新增 `wc.*`）
- [ ] T5.3 `gradlew test` 绿

## T6 测试收口（R11）

- [ ] T6.1 确认既有 40 用例不回归；`BundleKeysTest` 通过
- [ ] T6.2 若抽出「工作副本列表排序/去重」纯函数，补单测；否则无新增单测
- [ ] T6.3 记录最终用例数

## T7 自检清单（代码规范回归）

- [ ] T7.1 无 Swing Timer / Alarm 绑 Disposable；JBUI 缩放；主题色 UIUtil/JBColor
- [ ] T7.2 无 catch(Throwable)；invokeLater 带 ModalityState + 过期条件；图标按钮无障碍名
- [ ] T7.3 无 2022.1 不存在 API；无 internal（`WorkingCopyInfoPanel` / `SvnBranchConfigurationManager`）
- [ ] T7.4 dispose 无 `project.isDisposed` 提前返回

## T8 全量验证

- [ ] T8.1 `gradlew clean test`：0 失败，记录用例数
- [ ] T8.2 `gradlew buildPlugin`：字节码 `major version` = 55
- [ ] T8.3 `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyTimeout=600"`：PASS（0 issues）
- [ ] T8.4 `gradlew runPluginVerifier -PverifierLocalIde="G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3"`：两端 Compatible
- [ ] T8.5 人工沙箱走查：见 `checklist.md` B/C/D 节

## T9 文档同步

- [ ] T9.1 `README.md`：入口/流程/文件树同步（列表 + Merge From… + 模态对话框）
- [ ] T9.2 `CHANGELOG.md`：新增「第七轮」条目
- [ ] T9.3 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc`：新增第七轮记录
- [ ] T9.4 本文档与 `checklist.md` 全部勾选；`plan` 标注「已实施」并补验证结果

---

## 实施记录（2026-10-22，第七轮）

- T1 基线：40 用例 0 失败 / zip ≈ 123KB / 字节码 major 55。
- T2~T6 完成：`tab/EnhanceMergePanel.kt` 重写为**纯工作副本列表**（`getAllWcInfos()` + `WCInfo`，每行
  path + URL + 加粗「Merge From…」`LinkLabel`，删根选择器/状态机/`Merge Selected`）；**恢复**
  `ui/RevisionSelectDialog.kt`（薄包装）；`MergeFlow` 恢复 `openRevisionSelect`；文案删 `tab.select.*`/
  `tab.change.branch`/`tab.empty.hint`、新增 `wc.merge.from`/`wc.no.working.copies`；`BundleKeysTest` 同步。
- 未恢复 `MergeVariantDialog`（唯一合并方式即逐版本勾选，R4.4）。
- T8 验证：`clean test buildPlugin` → **40 用例 0 失败**、编译零告警、字节码 major 55、zip ≈ 123KB；
  `verifyOnIde 2022.1` → **PASS（0 issues）**；`runPluginVerifier` IC-221 + IC-262 → **均 Compatible**
  （仅既有 `ActionToolbar.updateActionsImmediately` 1 处 deprecated，非本次引入）。
- 遗留：真实 IDE 沙箱人工走查（checklist B/C/D 节）。

### 后续轮次（同一分支继续实施）

- **第八轮（勾选正确性与性能整改）**：勾选集合数据源由「过滤视图」改为「已加载全集」（修数据丢失）；
  右栏刷新改 200ms 防抖 + 后台线程；`loadedAll` 改不可变列表原子替换；dispose 中断在飞任务；
  进度条「取消」真正中止分页；删除 `reloadToken` 死守卫等 5 项杂项。
  ⚠️ 该轮**误把「批内同修订号」当重复删掉** → 有合并修订的分支上表格少行（真机验证到的「版本缺失」），
  已在下一轮修复。详见 `.codebuddy/specs/fix-revision-selection-perf/`。
- **第九轮（清单纯净度修复 · 不照抄原版缺陷）**：定位根因——原版 `getCommittedChangesWithMergedRevisons`
  （includeMergedRevisons=true）把**合并源分支的修订副本**也带进清单（合并不进去、状态图标为 null）；
  本插件改用 `getCommittedChanges`（只含源分支自身修订、不走缓存、游标语义一致）；
  并恢复「状态无图标 → 回落刷新中图标」、合并状态任务不再被「+N」取消。
