# Tasks — Subversion Working Copies Information Enhance（v3 并列标签页）

> 顺序执行。包 `com.weixf.svn.mergeenhance`，根目录 `d:\WorkSpace\AIProjects\SvnMergeEnhance\`。
> 构建 JDK 11（`org.gradle.java.home=D:/softwares/Java/jdk-11.0.32.1`），`GRADLE_USER_HOME=G:\GradleRepository`，
> `intellij { version="2022.1"; type="IC"; plugins=listOf("Subversion") }`，不引入 SVNKit。
> PowerShell 调用 gradlew：`-PideaVersion=2022.1` 这类含点号参数必须加引号。
>
> **设计依据**：`.codebuddy/plan/WcInfoEnhanceTab-plan.md`（svn4idea 事实已逐类核实）。
> 本轮**删除** v2 注入入口（`WcInfoLinkInjector`），是破坏性变更：旧 spec
> `add-revision-select-merge-ui` 的 R1 由本 spec R1–R5 取代。

---

## T1 标签页注册与数据层（Spec R1 / R2）

- [ ] T1.1 新建 `wcinfo/EnhanceWcInfoContent.kt`：实现 `ChangesViewContentProvider`，
      `initContent()` → `EnhanceCopiesPanel(project)`；
      内含 `VisibilityPredicate`（`ProjectLevelVcsManager.checkVcsIsActive(SvnVcs.VCS_NAME)`）
      与 `DisplayNameSupplier`（`EnhanceMergeBundle.message("tab.wcinfo.enhance")`，
      语言包感知；**不**声明 `<resource-bundle>`，维持现状决策）
- [ ] T1.2 新建 `wcinfo/EnhanceCopiesPanel.kt`（`SimpleToolWindowPanel`，复刻 `CopiesPanel` 数据层）：
      构造时 `messageBus.connect(this).subscribe(SvnVcs.ROOTS_RELOADED, ...)` +
      `Disposer.register(project, this)`
- [ ] T1.3 刷新节奏对齐原版：`refresh()` → `SvnVcs.getInstance(project).invokeRefreshSvnRoots()`，
      `isRefreshing` 防重入；`rootsReloaded(rootsChanged)`：true → `executeOnPooledThread(::doRefresh)`，
      false → 仅 `invokeLater(nonModal)` 清刷新态
- [ ] T1.4 `doRefresh()`（pooled 线程）：`getWcInfosWithErrors()`（按 `getPath()` 排序）+
      `getSvnFileUrlMapping().getErrorRoots()` +
      `getFactory().createUpgradeClient().getSupportedFormats()`（异常仅 `LOG.info`，对齐原版）
      → EDT `invokeLater(nonModal)` 重建列表
- [ ] T1.5 EDT 回填守卫：所有 `invokeLater` 在 **lambda 内**判
      `isDisposed || project.isDisposed`（项目规范硬要求）
- [ ] T1.6 `plugin.xml`：注册
      `<changesViewContent tabName="Subversion Working Copies Information Enhance"
      className/predicateClassName/displayNameSupplierClassName="...EnhanceWcInfoContent$..."/>`
      （tabName 值用英文原文字面量；显示名由 DisplayNameSupplier 覆盖）
- [ ] T1.7 **验收**：runIde 中 SVN 项目出现并列标签页；无 SVN 项目不出现；
      原版标签页行为完全不变；`idea.log` 无 `Synchronous execution on EDT`

## T2 信息行渲染（Spec R3）

- [ ] T2.1 新建 `wcinfo/WcInfoRowBuilder.kt`：纯函数
      `(WCInfo, Collection<WorkingCopyFormat>) → HtmlChunk`，
      用平台 `HtmlBuilder`/`HtmlChunk` 拼 `<table>`（`tr valign=top` / `td colspan` 同构原版）
- [ ] T2.2 行序对齐原版 `WorkingCopyInfoPanel`：path（bold）→ url → format → depth →
      nested type（external/switched 斜体）→ isWcRoot（斜体）→ Cleanup（format ≥ 1.7）→
      Configure Branches → **Enhance Merge…（bold，替代原版 Merge From）**
- [ ] T2.3 条件行：format 行在 `upgradeFormats.size > 1` 时附 Change Format 链接；
      depth 行在非 `INFINITY` 且无错时附 Fix Depth 链接；
      `hasError` 时 url 行红字（`UIUtil.getErrorForeground()` 对应色，禁硬编码），
      且 Cleanup / Configure Branches / Enhance Merge 整组不显示
- [ ] T2.4 升级格式集合：复用 public static `CopiesPanel.getUpgradeFormats(info, supportedFormats)`
- [ ] T2.5 `EnhanceCopiesPanel` 挂 `HyperlinkListener`，按链接 id
      （自建常量 `CLEANUP / CONFIGURE_BRANCHES / CHANGE_FORMAT / FIX_DEPTH / ENHANCE_MERGE`）分发
- [ ] T2.6 面板容器边距/行距走 `JBUI.Borders.empty(...)`；HiDPI 合规（项目规范）

## T3 链接行为（Spec R4 / R5）

- [ ] T3.1 公共前置 `checkRoot(root, actionName)`：`VirtualFile` 失效 →
      `showWarningDialog` 并中止（文案走 bundle，对齐原版语义）
- [ ] T3.2 Cleanup → `CleanupWorker(SvnVcs.getInstance(project), listOf(root)).execute()`
- [ ] T3.3 Configure Branches → `BranchConfigurationDialog.configureBranches(project, root)`
- [ ] T3.4 Change Format → `ChangeFormatDialog(project, File(info.path), false, !info.isIsWcRoot)`
      + `setSupported(upgradeFormats)` + `setData(info.format)`；确认且格式变化 →
      `StoreUtil.saveDocumentsAndProjectSettings(project)` + `SvnFormatWorker(...)`，`onSuccess` 后 `refresh()`
- [ ] T3.5 Fix Depth → `Messages.showOkCancelDialog`（warning 图标，文案走 bundle）→ OK 后
      `SvnCheckoutProvider.checkout(project, File(info.path), info.url, Revision.HEAD,
      Depth.INFINITY, false, null, info.format)`（刷新由 roots 变更事件触发，不手动刷）
- [ ] T3.6 Enhance Merge → `EnhanceMergeAction().performFor(project, wcInfo, anchor=本面板)`
      （wcInfo 来自本面板枚举结果，零反射；`MergeFlow` 零改动）
- [ ] T3.7 **验收**：四类链接行为与原版逐项对照（含 root 失效分支）；
      点击 Enhance Merge 走通 v2 全流程；多副本项目点击第 N 个 → 目标正确

## T4 工具栏刷新（Spec R2）

- [ ] T4.1 新建 `actions/RefreshEnhanceWcInfosAction.kt`（`DumbAwareAction`）：
      照抄原版 `RefreshWorkingCopiesAction` 结构，
      `ChangesViewContentManager.getInstance(project).getActiveComponent(EnhanceCopiesPanel::class.java)`
      定位本插件面板；update：无面板 → 隐藏，`isRefreshing` → 禁用
- [ ] T4.2 `plugin.xml`：注册 `<group id="SvnEnhanceMerge.WcInfoToolbar">` +
      `<action id="SvnEnhanceMerge.RefreshWorkingCopies" icon="AllIcons.Actions.Refresh"/>`
      （在 `EnhanceCopiesPanel` 构造时 `createActionToolbar(place, group, false)` + `setToolbar`）
- [ ] T4.3 `EnhanceCopiesPanel.uiDataSnapshot` 提供 `HELP_ID`（可省）与面板定位所需的必要 data
- [ ] T4.4 **验收**：Enhance 页激活时刷新按钮可见可用；切到其他页自动隐藏；
      刷新中禁用；与原版标签页互不干扰

## T5 注入入口移除（Spec R6）

- [ ] T5.1 **删除** `wcinfo/WcInfoLinkInjector.kt`（含 private `LinkInstaller`、
      `Alarm` 兜底轮询、`ContainerListener`、`removeInjectedLinks`、反射 `resolveWcInfo`）
- [ ] T5.2 `plugin.xml` **删除** `<postStartupActivity implementation="...WcInfoLinkInjector"/>`
- [ ] T5.3 清理仅被注入器引用的 bundle key（`link.text.enhance.merge` 若被新面板复用则保留并改名引用）
- [ ] T5.4 **验收**：原版标签页每个副本仅有原版行（Cleanup / Configure Branches / Merge From），
      无注入链接、无 `svn.enhanceMerge.*` client property 残留；
      项目开关/重开多次无异常

## T6 本地化（Spec R7）

- [ ] T6.1 新增 key（英文 + `_zh_CN` 两份同步）：`tab.wcinfo.enhance`（标签显示名）、
      行标签 `wcinfo.label.url / .format / .depth`、`wcinfo.link.cleanup / .configure.branches /
      .change.format / .fix.depth / .enhance.merge`、`wcinfo.row.working.copy.root`、
      nested type 显示名（`external` / `switched`）、`dialog.title/...`（Fix Depth 确认、
      checkRoot 警告、Change Format 相关）
- [ ] T6.2 所有文案（含冒号、省略号、方括号）一律进 bundle，不在代码拼接
- [ ] T6.3 `BundleKeysTest` / `EnhanceMergeBundleTest` 扩展覆盖新 key（双语双向对齐）
- [ ] T6.4 **验收**：未装语言包 → 全英文；装中文语言包 → 全中文（含标签页名）

## T7 测试、验证与收尾（Spec R8）

- [ ] T7.1 `gradlew test` 全绿（53 既有 + 新增 bundle key 用例）
- [ ] T7.2 `gradlew verifyPlugin` + `verifyPluginConfiguration` 零告警；
      `gradlew buildPlugin` 产出 zip
- [ ] T7.3 `gradlew runPluginVerifier -PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3`
      两端 Compatible（重点盯 `changesViewContent` EP 与新引用的 svn4idea public 类：
      `CleanupWorker` / `BranchConfigurationDialog` / `ChangeFormatDialog` / `SvnFormatWorker` /
      `SvnCheckoutProvider` / `CopiesPanel.getUpgradeFormats` / `ChangesViewContentManager`）
- [ ] T7.4 `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyAutoClose=true"`
      PASS（超时先看 `idea.log` 的 `EnhanceMerge i18n` 就绪行，再放宽 `-PverifyTimeout`）
- [ ] T7.5 `gradlew runIde` 人工验收：plan §5 验证清单全过
      （标签页出现/谓词/顺序、信息行与原版逐行对照、四链接行为、Enhance Merge 全流程、
      原版面板无残留、EDT 纯净、项目关闭无泄漏日志）
- [ ] T7.6 README 重写「入口」章节（唯一入口 = Enhance 标签页；功能截图更新）
- [ ] T7.7 `.codebuddy/rules/svn-enhance-merge.mdc` 同步：入口与包章节重写、
      「不注册任何 action」表述澄清为「不注册菜单入口（自有标签页工具栏 action 除外）」、
      svn4idea API 兼容性基线补新引用类、删除注入器相关条目
- [ ] T7.8 旧 spec `add-revision-select-merge-ui/spec.md` 的 R1 加「已被
      `add-wcinfo-enhance-tab` 取代」标注（保留历史，不删除）
- [ ] T7.9 plan 文档补「实施结果」小节（差异记录 + 验证实跑结论）

## Task Dependencies

- T1 ← 无（全局前置）
- T2 ← T1；T3 ← T2；T4 ← T1（可与 T2/T3 并行）
- T5 ← T3（新入口可用后再拆旧入口，避免中间态无入口）
- T6 ← T2（key 随行定稿后补翻译）
- T7 ← 全部

## 备注

- 本轮**不改动**：`MergeFlow` / `ui/*` / `log/*` / `merge/*` / `settings/*`。
- 构建告警清单与超时陷阱见 rules（勿重复排查）；`verify-plugin.ps1` 锁文件问题先杀
  `*idea-sandbox*` java 进程再打包。
- 2026.x 仍无法沙箱实跑（工具链限制），兼容性依据 = Plugin Verifier 两端比对 + 人工实测记录。
