# 方案 v3：并列标签页「Subversion Working Copies Information Enhance」

> 目标：Marketplace 上架。**取代 v2 的反射注入入口**（spec `add-revision-select-merge-ui` 的 R1）。
> 本文所有 svn4idea 事实均于 2026-10-18 从 `G:\gitDownload\svn4idea` 逐类核实（引用行号见文中）。
> Spec 三件套：`.codebuddy/specs/add-wcinfo-enhance-tab/`（spec.md / tasks.md / checklist.md）。

## 1. 背景与决策

### 1.1 现状入口的合规问题

v2 唯一入口 = `wcinfo/WcInfoLinkInjector.kt`：
按类名定位 internal 类 `org.jetbrains.idea.svn.dialogs.WorkingCopyInfoPanel` →
反射调 `getInfo()` 取 `WCInfo` → 向原版 `CopiesPanel` 的纵向容器插入 `LinkLabel` →
`ContainerListener` + `ToolWindowManagerListener` + **2s 兜底定时器**三重自愈。

三处反射/侵入的审核风险分级：

| 位置 | 性质 | Marketplace 风险 |
|---|---|---|
| `EnhanceMergeBundle` 反射桥接 `findLanguageBundle` | 调**公开 API** 的跨版本兼容手段（该方法 2026.x 搬家） | 低，业界常见 |
| 反射调 `WorkingCopyInfoPanel.getInfo()` | 调 **internal 类** | 红线 |
| 组件树扫描 + 向原版面板插入组件 | 修改**其他插件**的 UI | 红线 |

Plugin Verifier 只扫字节码**直接引用**，反射探测不到 → 自动校验全 PASS（能"过"的假象来源）；
但人工审核守则明确要求仅用公开 API、不得侵入其他插件内部实现——被抽检/举报即拒稿。

### 1.2 上架后的持续风险

自用时注入失效当天可发现；上架后用户覆盖 2022.1~2026.x，而 svn4idea 已 unbundle
转 Marketplace 独立演进。哪天它重写面板，注入**静默失效**（链接消失、无报错）→
差评 + `Broken` 标记 → 触发平台复审。入口命脉押在无契约的字节码巧合上不可接受。

### 1.3 决策（2026-10-18，用户确认）

走**路线 2**：用 svn4idea 原版同款的 `changesViewContent` 扩展点注册并列标签页
「Subversion Working Copies Information Enhance」，面板功能复刻原版、
把「Merge From…」换成「Enhance Merge…」，全程只调公开 API，并**删除注入器**。
（备选的「独立 ToolWindow + 裸调 svn CLI」路线放弃：失去原版冲突解决 UI、
变更列表语义、凭证/代理等集成，且集成深度是本插件的核心价值，不采。）

## 2. 原版源码调研结论（关键事实，已核实）

### 2.1 原版标签页的注册方式

原版「Subversion Working Copies Information」**不是** ToolWindow，而是 VCS 工具窗
（`ChangesViewContentManager`）里的一个标签页，通过平台公开 EP 注册
（`svn4idea/resources/META-INF/plugin.xml:206-209`）：

```xml
<changesViewContent tabName="Subversion Working Copies Information"
                    className="org.jetbrains.idea.svn.WorkingCopiesContent"
                    predicateClassName="org.jetbrains.idea.svn.WorkingCopiesContent$VisibilityPredicate"
                    displayNameSupplierClassName="org.jetbrains.idea.svn.WorkingCopiesContent$DisplayNameSupplier"/>
```

### 2.2 `WorkingCopiesContent`（public final class）

- `initContent()` → `new CopiesPanel(project)`；
- `VisibilityPredicate.test(project)` = `ProjectLevelVcsManager.checkVcsIsActive(SvnVcs.VCS_NAME)`（public）；
- `DisplayNameSupplier` 走 `SvnBundle.message("toolwindow.working.copies.info.title")`。

### 2.3 `CopiesPanel`（public class，extends SimpleToolWindowPanel）

数据与刷新节奏（复刻蓝本）：

- 构造：订阅 `SvnVcs.ROOTS_RELOADED`（**public** `Topic<Consumer>`，`SvnVcs.java:140`）；
- `refresh()` → `getVcs().invokeRefreshSvnRoots()`（public，`SvnVcs.java:208`），`isRefreshing` 防重入；
- `rootsReloaded(rootsChanged)`：true → `executeOnPooledThread(doRefresh)`；false → 仅清刷新态；
- `doRefresh()`（后台线程）：`getWcInfosWithErrors()`（public，`SvnVcs.java:579`）+
  `getSvnFileUrlMapping().getErrorRoots()`（public 接口 `SvnFileUrlMapping.java:31`）+
  `getFactory().createUpgradeClient().getSupportedFormats()`（public，`ClientFactory.java:183`）
  → `invokeLater(nonModal)` 回 EDT 更新列表；
- 列表按 `WCInfo.getPath()` 排序；`removeAll()` 重建面板。

各链接行为与所用 API（**全部 public**，可零反射复刻）：

| 链接 | 原版实现（CopiesPanel.java） | API |
|---|---|---|
| Cleanup | `new CleanupWorker(vcs, [root]).execute()`（内部先 `saveAllDocuments`） | `CleanupWorker` public |
| Configure Branches | `BranchConfigurationDialog.configureBranches(project, root)` | public static |
| Change Format | `ChangeFormatDialog` → `SvnFormatWorker`（后台）→ 完成后 `refresh()` | 均 public |
| Fix Depth | 确认对话框 → `SvnCheckoutProvider.checkout(project, file, url, HEAD, INFINITY, false, null, format)` | public |
| Merge From | `SelectBranchPopup` → `QuickMerge`（internal 流程，**我们换成 Enhance Merge**） | — |

升级格式集合：`CopiesPanel.getUpgradeFormats(info, supportedFormats)`（**public static**，直接复用）。

### 2.4 `WorkingCopyInfoPanel`（internal Kotlin）——不可引用，UI 自拼

`HtmlPanel` 子类，内容由 `UiBuilder` 用 `HtmlBuilder`/`HtmlChunk` 拼一张 `<table>`，
行序与条件（`WorkingCopyInfoPanel.kt:39-53`）：

1. path（加粗）；
2. url（`hasError` 时红色 error message）；
3. format（`upgradeFormats.size > 1` 时附 **Change Format** 链接）；
4. depth（非 `INFINITY` 且无错时附 **Fix Depth** 链接）；
5. nested type（`external` / `switched` 时斜体行）；
6. isWcRoot（斜体行）；
7. Cleanup（format ≥ 1.7 时）；
8. Configure Branches；
9. Merge From → **本插件换成 Enhance Merge**；`hasError` 时 7/8/9 整组不显示。

### 2.5 原版刷新按钮不可复用

`RefreshWorkingCopiesAction.kt:9-11` 硬编码
`ChangesViewContentManager.getActiveComponent(CopiesPanel::class.java)`——
Enhance 页激活时它找不到面板会**隐藏**。必须自建 toolbar group + 刷新 action
（`getActiveComponent` 本身是平台 public API，照抄找自己的面板类即可）。

### 2.6 错误通知不复刻

原版另有 "Svn Roots Detection Errors" 通知组 + `WorkingCopiesContent.show()` 跳转；
Enhance 页**面板内红字**显示错误即可，不引入通知组。

## 3. 新架构

### 3.1 类清单

| 类 | 职责 |
|---|---|
| `wcinfo/EnhanceWcInfoContent` | 实现 `ChangesViewContentProvider`（public 平台接口）；内含 `VisibilityPredicate`（照抄 2.2）与 `DisplayNameSupplier`（走 `EnhanceMergeBundle`，语言包感知）；`initContent()` → `EnhanceCopiesPanel` |
| `wcinfo/EnhanceCopiesPanel` | 复刻 `CopiesPanel` 的数据层与刷新节奏（订阅 `ROOTS_RELOADED`、`isRefreshing` 防重入、后台枚举、按 path 排序、`removeAll()` 重建）；`HyperlinkListener` 按链接 id 分发 |
| `wcinfo/WcInfoRowBuilder` | 纯函数：`(WCInfo, Collection<WorkingCopyFormat>) → HtmlChunk`，行序照 2.4，第 9 行为 Enhance Merge |
| `actions/RefreshEnhanceWcInfosAction` | 照抄 `RefreshWorkingCopiesAction`，`getActiveComponent(EnhanceCopiesPanel::class.java)` |
| （删除）`wcinfo/WcInfoLinkInjector.kt` | 整文件删除（含 private `LinkInstaller`、Timer、ContainerListener、反射） |

### 3.2 plugin.xml 变更

- **删**：`<postStartupActivity implementation="...WcInfoLinkInjector"/>`（现 plugin.xml:121）；
- **增**：
  ```xml
  <changesViewContent
      tabName="Subversion Working Copies Information Enhance"
      className="com.weixf.svn.mergeenhance.wcinfo.EnhanceWcInfoContent"
      predicateClassName="com.weixf.svn.mergeenhance.wcinfo.EnhanceWcInfoContent$VisibilityPredicate"
      displayNameSupplierClassName="com.weixf.svn.mergeenhance.wcinfo.EnhanceWcInfoContent$DisplayNameSupplier"/>
  <group id="SvnEnhanceMerge.WcInfoToolbar">
      <action id="SvnEnhanceMerge.RefreshWorkingCopies"
              class="com.weixf.svn.mergeenhance.actions.RefreshEnhanceWcInfosAction"
              icon="AllIcons.Actions.Refresh"/>
  </group>
  ```
  > 与规则「不注册任何 `<action>`」的关系：该决策针对**菜单/右键入口**
  > （不挂 `SubversionGroup` / `Vcs.Operations.Popup`）；此处是自有标签页工具栏，需同步规则表述。
- **不**声明 `<resource-bundle>`（维持现状决策：`DisplayNameSupplier` 用 `EnhanceMergeBundle`）。

### 3.3 入口形态

```
VCS 工具窗
 ├─ Subversion
 ├─ Local Changes
 ├─ Repository
 ├─ Incoming
 ├─ Subversion Working Copies Information      ← 原版，保持原样（含原版 Merge From）
 └─ Subversion Working Copies Information Enhance ← 本插件（Merge From 位置 = Enhance Merge…）
```

第三方插件的标签排在内置之后（观察行为，非 API 契约，见 §6 风险）。

## 4. 分步设计要点

1. **可见性谓词**照抄原版：SVN 未激活 → 标签页不出现。
2. **刷新节奏**严格对齐 `CopiesPanel`（§2.3）：`isRefreshing` 防重入；枚举只在 pooled 线程
   （可能触发工作副本探测，禁止 EDT）；EDT 回填一律 `invokeLater(nonModal)` + lambda 内判
   `isDisposed || project.isDisposed`（项目规则硬要求）。
3. **面板生命周期**：`messageBus.connect(this)` + `Disposer.register(project, this)`；
   面板随标签页销毁时自动解绑（对照 v2 注入器的三重自愈机制全部不再需要）。
4. **HTML 行**用平台公开的 `HtmlBuilder`/`HtmlChunk`（与原版 `WorkingCopyInfoPanel` 同构），
   文案全部走 `EnhanceMergeBundle`（**不用** `SvnBundle` 的 key，那是内部 bundle）。
5. **Enhance Merge 点击**：`EnhanceMergeAction.performFor(project, wcInfo, anchor)`（现有公开入口，
   零改动），`anchor` = 链接所在面板；行内数据来自**自己的枚举结果**，不再反射。
6. **清理**：删除 `WcInfoLinkInjector.kt`；`KEY_OUR_LABEL`/`KEY_CONTAINER_HOOKED`/
   `KEY_LOGGED` 与 `removeInjectedLinks()` 一并消失；`plugin.xml` 删 `postStartupActivity`。

## 5. 验证方案

- `gradlew buildPlugin` / `test`（bundle key 双语对齐用例随新 key 扩充）；
- `gradlew verifyPlugin` + `verifyPluginConfiguration`（零告警基线保持）；
- `gradlew runPluginVerifier -PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3`
  （重点：`changesViewContent` EP 与新引用的 svn4idea public 类在两端二进制比对）；
- `gradlew verifyOnIde -PideaVersion=2022.1 -PverifyAutoClose=true`（沙箱扫异常日志；
  超时先看 `idea.log` 的 `EnhanceMerge i18n` 就绪行）；
- `gradlew runIde` 人工验收：Enhance 标签页出现/位置/谓词、信息行与原版逐行对照、
  各链接行为、点击 Enhance Merge 走通 v2 全流程、原版标签页不受影响、
  `idea.log` 无 `Synchronous execution on EDT`。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 标签页顺序非 API 契约（第三方排内置后是观察行为） | 验收时确认；顺序漂移不影响功能，不做依赖顺序的逻辑 |
| 原版面板日后加行/改交互，Enhance 页不自动跟随 | 接受（有意的取舍）；升级 IDE 基线时按规则重新核对公开 API 基线清单并对照原版源码 |
| `changesViewContent` EP 属性在 2022.1 的兼容性 | svn4idea 2022.1 已用同一 EP 同样属性（多年历史），且纳入 verifier 两端比对 + 2022.1 沙箱实跑 |
| 删除注入器后老用户找不到入口 | 可选：首启一次性 sticky 通知引导（需注册 notificationGroup）——**待用户决策，默认不做** |
| `getActiveComponent` 泛型签名跨版本变化 | 平台 API（非 svn4idea），纳入 verifier 比对面 |

## 7. 待定事项

- [ ] 首启引导通知（§6 第 4 行）：做 / 不做，待用户决策；
- [ ] Enhance 页信息行的「Enhance Merge」是否加粗（原版 Merge From 是 bold）——建议加粗保持醒目，实现时定。
