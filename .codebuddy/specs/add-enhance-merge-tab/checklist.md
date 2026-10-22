# Checklist — 复刻 Working Copies Information 面板，Merge From… 走 Enhance Merge（第七轮验收）

> 用法：实施完成后逐项核对。A 节为构建/静态验收，B~E 节为沙箱人工/文档验收。
> 需求编号见同目录 `spec.md`。
>
> **状态（2026-10-22）**：A 节（构建/静态）已通过；B/C/D 节（沙箱人工）待真实 IDE 走查。

## A. 构建与静态验收

- [ ] A1 `gradlew clean test` 0 失败；用例数与 `tasks.md` T6.3 记录一致
- [ ] A2 `gradlew buildPlugin` BUILD SUCCESSFUL；zip 无 kotlin-stdlib / 第三方依赖
- [ ] A3 产物字节码 `javap -verbose -cp build/classes/kotlin/main com.weixf.svn.mergeenhance.flow.MergeFlow
      | grep "major version"` = **55**
- [ ] A4 `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyTimeout=600"` → **PASS（0 issues）**
- [ ] A5 `gradlew runPluginVerifier -PverifierLocalIde="G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3"` →
      IC-221 与 IC-262 均 **Compatible**
- [ ] A6 `plugin.xml` 仅一处 `<changesViewContent>`（无 `isInCommitToolWindow`）；`postStartupActivity` 仍为 `EnhanceMergeStartup`
- [ ] A7 代码规范自检（对应 tasks T7）：无 Swing Timer / Alarm 绑 Disposable / JBUI 缩放 / 无硬编码颜色 /
      无 catch(Throwable) / invokeLater 带 ModalityState / 图标按钮无障碍名 / 无 internal 引用 /
      dispose 无 `project.isDisposed` 提前返回
- [ ] A8 `BundleKeysTest` 通过（`wc.*` 两包齐全、`tab.select.*` 等已删、中英 key 集合一致）

## B. 工作副本列表（对照原版 WC Info 面板）

- [ ] B1 SVN 项目打开 VCS 工具窗 → 「Enhance Merge」标签页默认显示**工作副本列表**（path + URL），与原版 WC Info 面板的信息一致
- [ ] B2 多工作副本全部列出；`⟳ 刷新` 可重新枚举
- [ ] B3 非 SVN 项目 → 标签不出现（predicate 生效）；空列表 → 空态提示不崩溃
- [ ] B4 列表信息走后台枚举，打开标签页无 EDT 卡顿
- [ ] B5 标签页**无**「Select Branch… / 根选择器 / Merge Selected」等 v4 残留组件

## C. Merge From… → 模态修订选择 → 合并全链路

- [ ] C1 每个工作副本行有**加粗的「Merge From…」链接**（不是「Enhance Merge…」/「Select Branch…」）
- [ ] C2 点「Merge From…」→ 弹出原版分支选择（trunk / branches / Configure Branches…）
- [ ] C3 选分支后 → 弹出**模态 `RevisionSelectDialog`**（搜索框 / 表格 / 工具栏四动作 / +N / 合并状态 /
      右栏变更树 / `[Already merged]` 与之前一致；标题 `Merge from <branch>`）
- [ ] C4 对话框可缩放、尺寸记忆生效；无 `MergeVariantDialog` 中间步骤
- [ ] C5 `Merge Selected` → 关闭对话框 → 原版「合并前本地改动提示」→ 原版合并任务（进度/冲突/结果树）
- [ ] C6 取消/关闭对话框 → 无副作用、回到列表态；加载期间关闭项目无残留后台任务
- [ ] C7 列表态始终保留（工作副本列表不被对话框流程破坏）

## D. 合规与回归

- [ ] D1 全程无反射注入、无轮询、无 `<action>`；点「Merge From…」不触发原版 `QuickMerge`
- [ ] D2 Cleanup / Configure Branches / Properties **未复刻**（原版 WC Info 面板仍可正常使用这些动作）
- [ ] D3 中英文案正确（`wc.merge.from` / `wc.no.working.copies`）；装中文语言包后跟随

## E. 文档同步

- [ ] E1 `README.md` 同步（入口/流程/文件树）
- [ ] E2 `CHANGELOG.md` 新增「第七轮」条目
- [ ] E3 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc` 新增第七轮记录
- [ ] E4 `plan` 标注「已实施」+ 验证结果；`tasks.md` / 本文档全部勾选

---

**验收通过标准**：A 节全绿 + B/C/D 节无未勾选项 + E 节完成。
任何一项不满足 → 记录原因，修复后重跑对应节，不整体放行。
