# 方案 v2：SVN Enhance Merge（复刻原版 SVN 合并流程 + TortoiseSVN 式日志筛选）

> **本文档取代 v1**。v1（独立脚手架版）基于"原版分支枚举用 internal API 不可复用、日志全量 HEAD→r1 拉取、自绘 MergeClient 逐段合并"三条**错误结论**设计，导致实现与目标交互不符。v2 依据 `svn4idea/plugins/svn4idea` 源码逐类核实后重写。

---

## 1. 目标交互（以用户给定的 5 张图为准）

```
图1 「Version Controls」工具窗 → Subversion Working Copies Information 标签页
     Merge From…  下方  ← 新增「Enhance Merge…」按钮
        │
图2 点击 Enhance Merge… → Select Branch 弹窗（与原版【完全一致】）
     trunk (trunk) ／ branches… ／ Configure Branches…
        │
图3 选中分支 → Select Merge Variant（标题同原版）
     差异：只有 [Revision Select]（含描述）+ [Cancel]
        │
图4 Merge from <branch>（布局同原版：左修订列表 + 右变更路径）
     差异：① 顶部增加 TortoiseSVN 式条件过滤栏（图5）
           ② 日志实时直连仓库，进入页面只加载 30 条（可配置）
        │
      [Merge Selected] → 执行合并（复用原版执行链）
```

> **入口唯一**：只保留图1 面板链接。`Subversion` 主菜单组与 `Vcs.Operations.Popup` **不再注册**
> （用户决策 2026-10-18）。`EnhanceMergeAction` 保留为内部可调用类（面板链接用合成事件调用），
> 但不在 `plugin.xml` 的 `<actions>` 中注册。

---

## 2. 现状差距诊断（为什么"对不上"）

### 2.1 硬证据：本插件从未被真正运行验证过

`build/idea-sandbox/system/log/idea.log` 共 262 行，仅有两次启动，`args` 均为
`[traverseUI, .../build/searchableOptions, true]` —— 这是 `buildSearchableOptions` 任务，
**不是 `runIde`**，日志里没有任何 `Project opened`。

结论：图1 的注入链路、"30 条分页"、整个 UI 流程**一次都没有在带 SVN 项目的 IDE 里跑过**。
`build/classes` 里 P0 缺陷（见 §2.3）没被发现，根因即此。

### 2.2 逐图差距

| 图 | 期望 | 当前实现 | 差距 |
|---|---|---|---|
| 1 | Merge From 下多一行「Enhance Merge…」 | `WcInfoPanelLinkInjector` 用 HTML `insertAfterEnd` 注入 | **界面上不出现**（见 §2.3） |
| 2 | 原版 Select Branch 弹窗 | 自绘 `BranchSelectionDialog`（单行 URL 输入框） | ❌ 完全不同 |
| 3 | 只有 Revision Select + Cancel | `MergeMethodSelectionDialog`（只有描述文字、无按钮选择） | ❌ 没有"按钮即执行"的交互 |
| 4 | 原版列表 + 右侧路径 + 顶部过滤栏 | 自绘 5 列 `JBTable`（勾选/修订/作者/日期/说明），无路径面板、无过滤栏、**初始空表** | ❌ 三重不符 |
| 4-数据 | 实时、首屏 30 条 | 全量 `doLog(HEAD → r1, limit=0)` 一次拉完 | ❌ 大仓库首屏极慢 |

### 2.3 图1 注入失败的根因分析

1. **最可疑点：用了错误的插入 API**
   ```kotlin
   doc.insertAfterEnd(lastTr, "<tr><td colspan=3>…</td></tr>")
   ```
   JDK `HTMLDocument` 官方**用于向表格插行**的 API 是 `insertBeforeEnd(tableElement, htmlText)`。
   `insertAfterEnd(lastTr, …)` 会把插入点解析到最后一个 `<tr>` 之后（易被判到 `</table>` 之外），
   异常又被 `catch (ex: Exception) → LOG.warn` 吞掉，所以**界面无变化、也无明显报错**。
2. 匹配条件本身没问题：已用 `javap` 核实
   `com.intellij.util.ui.HtmlPanel extends javax.swing.JEditorPane`，
   而 `WorkingCopyInfoPanel : HtmlPanel()`，所以 `component is JEditorPane` + 类名匹配可命中。
3. `CopiesPanel.updateList()` 每次刷新都 `new WorkingCopyInfoPanel()` 重建面板，自愈依赖
   `DocumentListener` + 1s 定时器，链路长、可观测性为零（成功路径无任何日志）。

### 2.4 其它必须在 v2 修掉的缺陷

- `RevisionSelectDialog`：表格模型 `items` 默认 `emptyList()`，`createCenterPanel` 从未赋值 → **首屏 0 行**。
- 用户可见字符串全部硬编码英文，违反项目规则（须走 `messages/*.properties`）。
- `RevisionItem.merged` 死字段；无 `computeSpans` 单测。
- ~~`svn4idea/` 参考克隆未清理~~ → **用户决策：全程保留，永不删除**（2026-10-18）。
  它是本项目唯一可离线核对 svn4idea 源码的依据，本次全部调研结论均来源于此；
  它不是 fork、不参与构建（不在任何 sourceSet 中），仅作参考资料保留。
  v1 计划书 T9.2「删除 svn4idea/」一条作废。

---

## 3. 原版源码调研结论（`svn4idea/plugins/svn4idea`）

### 3.1 原版流程链（CopiesPanel → QuickMerge）

```
CopiesPanel.updateList()                     每刷新重建 WorkingCopyInfoPanel 并 addHyperlinkListener
  └ performAction(MERGE_FROM)  → mergeFrom(wcInfo, infoPanel)
       ├ SelectBranchPopup.showForBranchRoot(project, rootVf, callback, title, infoPanel)   ← 图2
       ├ MergeContext(vcs, 目标分支下对应 WC URL, wcInfo, branchTail, root)
       └ new QuickMerge(ctx, new QuickMergeInteractionImpl(ctx)).execute()
            ├ selectMergeVariant() → QuickMergeWayOptionsPanel + DialogBuilder          ← 图3
            ├ MergeCalculatorTask → loadChangeLists(...) 分页拉日志
            ├ selectMergeItems() → ToBeMergedDialog                                     ← 图4
            └ merge(selected) → ChangeListsMergerFactory + SvnIntegrateChangesTask
```

### 3.2 可复用性判定（关键：v1 的三条结论全部推翻）

| 能力 | 原版实现 | 可见性 | v2 用法 |
|---|---|---|---|
| 图2 分支弹窗 | `SelectBranchPopup.showForBranchRoot(...)` + `BranchSelectedCallback` | **public final class / public interface** | **直接复用**（一行调用即得原版弹窗） |
| 分支配置读取 | `SvnBranchConfigurationManager` | `internal class` ❌ 不可用 | 由上面的回调参数 `SvnBranchConfigurationNew`（public）间接获得 |
| 目标分支对应 URL | `CopiesPanel.getCorrespondingUrlInOtherBranch` | private static | 用 public 的 `configuration.getRelativeUrl(url)` + `Url.appendPath(rel, false)` 复刻 |
| 图3 方式选择 | `QuickMergeWayOptionsPanel`（public）+ `DialogBuilder` | public | **自建同构面板**（仅 1 个选项 + Cancel），复用 `MultiLineLabelUI` 保持视觉一致 |
| 图4 修订列表 | `ToBeMergedDialog`（public，但内部 `myPanel` 私有、无法插入过滤栏） | public 不可改造 | **自建同构 Dialog**，复用 `TableView` + `ListTableModel` + `CommittedChangeListRenderer` + `CommittedChangesBrowser` + `QuantitySelection` |
| 分页取日志 | `MergeCalculatorTask.loadChangeLists(ctx, beforeRevision, size)` → `Pair<List<SvnChangeList>, Boolean>` | **public static** | **直接复用**：本就是实时 `doLog(--limit)`，无缓存 |
| 底层日志查询 | `SvnCommittedChangesProvider.getCommittedChangesWithMergedRevisions(settings, location, maxCount, consumer)` | public | 由上一行间接调用；`settings` 的日期/修订条件会转成 `svn log -r {date}:{date}`，即**服务端过滤** |
| 合并执行 | `SvnIntegrateChangesTask(vcs, WorkingCopyInfo, MergerFactory, currentBranchUrl, title, dryRun, branchName)` + `ChangeListsMergerFactory(lists, recordOnly, invertRange, groupSequential)` | **两者均 public** | **直接复用**：含连续段压缩、进度、冲突解决（ResolveWorker）、VFS 刷新、错误上报 |
| 已合并状态 | `OneShotMergeInfoHelper implements MergeChecker` | public | 可选开关（需额外 `svn:mergeinfo` 网络调用） |
| `SvnChangeList` | `implements CommittedChangeList`（revision/author/date/comment/affectedPaths） | public | 作为行模型，天然带变更路径供右侧浏览器使用 |

> **v1 的错误结论更正**
> ① "分支枚举依赖 internal 的 `SvnBranchConfigurationManager`，独立插件无法稳定复用" → 错。`SelectBranchPopup` 是 public，图2 可 100% 原样复刻。
> ② "取日志 = `doLog(HEAD, r1, limit=0)` 全量" → 与原版不符。原版用 `ChangeBrowserSettings` + `--limit` **分页实时查询**；全量拉取反而是性能倒退。
> ③ "逐段 `MergeClient.merge` + 自建 dry-run" → 可用但丢掉了原版的冲突解决、进度、VFS 刷新。应用 `SvnIntegrateChangesTask`。

### 3.3 原版未满足、需要 v2 自研的部分

1. **过滤栏**：原版 `ToBeMergedDialog` 只有工具栏（全选/全不选/加载 100/加载 500），没有任何条件过滤。需新增。
2. **首屏条数可配置**：原版固定 `BUNCH_SIZE = 100`（可用系统属性 `idea.svn.quick.merge.bunch.size` 覆盖，非 UI 配置项）。需做成插件设置项，默认 30。
3. **过滤 + 分页的联动词义**：原版没有过滤，所以只要 `--limit N` 即可；加了客户端过滤后，一页可能匹配不到几条，需要"循环补页"算法（见 §5.3）。

### 3.4 一个必须写进文档的原版缺陷

`SvnCommittedChangesProvider.getCommittedChangesImpl` 把 `maxCount` 直接传给 `svn log --limit`，
而**作者过滤发生在回调 handler 里**（`createLogHandler` 内 `author.equalsIgnoreCase(...)`）：
> 即"作者=bob + limit=30"可能返回 0 条，但仓库里明明有 bob 的提交。

v2 用"循环补页"天然规避该问题（见 §5.3）。

---

## 4. 新架构

### 4.1 类清单

```
src/main/kotlin/com/svn/revisionselect/
├── EnhanceMergeAction.kt              # 流程入口（**普通类，非 AnAction**；由面板链接直接调用 performFor）
├── wcinfo/
│   └── WcInfoLinkInjector.kt         # 图1：向 CopiesPanel 面板注入 Enhance Merge 链接
│                                     #（public WcInfoLinkInjector = StartupActivity；private LinkInstaller 持有 Timer/messageBus）
├── flow/
│   └── MergeFlow.kt                  # 编排：wcInfo → 图2 → 图3 → 图4 → 执行
├── ui/
│   ├── MergeVariantDialog.kt         # 图3：Revision Select + Cancel
│   ├── RevisionSelectDialog.kt       # 图4：过滤栏 + 分页列表 + 变更路径浏览器
│   └── LogFilterPanel.kt             # 图4 顶部过滤栏（TortoiseSVN 式）
├── log/
│   ├── LogFilter.kt                  # 过滤条件模型 + matches()（纯逻辑，可单测）
│   └── LogPageLoader.kt              # 分页/补页加载器（封装 MergeCalculatorTask.loadChangeLists）
├── merge/
│   └── MergeExecutor.kt              # 封装 LocalChangesPromptTask + SvnIntegrateChangesTask
├── settings/
│   ├── EnhanceMergeSettings.kt        # 持久化状态（见 §5.6）
│   └── EnhanceMergeConfigurable.kt    # Settings UI
└── resources/
    ├── META-INF/plugin.xml           # vendor / change-notes / depends / EP 注册
    ├── pluginIcon.svg + pluginIcon_dark.svg   # 40×40 图标（浅/深主题），须在插件目录根部
    ├── THIRD-PARTY-LICENSES.txt      # LGoodDatePicker(MIT) 许可全文
    └── messages/EnhanceMergeBundle.properties (+ _zh_CN)
```

### 4.2 plugin.xml 变更

```xml
<id>com.weixf.svn.mergeenhance</id>
<name>SVN Enhance Merge</name>
<vendor email="…" url="https://wxingfu.github.io/">wxingfu</vendor>   <!-- Marketplace 强制项 -->
<description><![CDATA[ … 功能列表 + 第三方库(LGoodDatePicker/MIT)声明 … ]]></description>
<change-notes><![CDATA[ … 1.0.0 更新要点 … ]]></change-notes>          <!-- Marketplace 需要 -->

<depends>com.intellij.modules.vcs</depends>
<depends>Subversion</depends>
<resource-bundle>messages.EnhanceMergeBundle</resource-bundle>
<idea-version since-build="221"/>                                          <!-- until 由 patchPluginXml 注入 252.* -->

<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="com.weixf.svn.mergeenhance.wcinfo.WcInfoLinkInjector"/>
    <applicationService serviceImplementation="com.weixf.svn.mergeenhance.settings.EnhanceMergeSettings"/>
    <applicationConfigurable instance="com.weixf.svn.mergeenhance.settings.EnhanceMergeConfigurable"
                         id="com.weixf.svn.mergeenhance.settings"
                         key="settings.display.name"
                         displayName="SVN Enhance Merge" parentId="vcs"/>
</extensions>

<!-- 不再注册任何 <action>：入口只有 Working Copies Information 面板里的 Enhance Merge 链接 -->
```

> 注意：`postStartupActivity` 放进 `<extensions>` 内（v1 直接挂在 `<idea-plugin>` 根下；虽合法但易被工具链忽略，统一放 `<extensions>`）。

> **包级文件（2026-10-18 补充）**：`pluginIcon.svg` / `pluginIcon_dark.svg` / `THIRD-PARTY-LICENSES.txt`
> 必须位于**插件目录根部**（`zip 内 <plugin>/pluginIcon.svg`），Marketplace 才会识别。
> IPGP 1.x 默认把 `src/main/resources` 全打进 `lib/*.jar`（图标会落到 jar 内 `META-INF/`），
> 必须显式配置 `tasks.named<BuildPluginTask>("buildPlugin") { from("src/main/resources") { include(...) } }`。
> 另：Kotlin stdlib **不打包**（`kotlin.stdlib.default.dependency=false`），否则官方
> `verifyPluginConfiguration` 报警告且包体多 1.8MB。

---

## 5. 分步设计

### 5.1 图1 —— Merge From 下方的 Enhance Merge

**方案 A（已废弃）：修正 HTML 插入 API —— 仅作失败记录，勿再采用**

```kotlin
// 1) 找到文档里最后一个 <table> 元素（WorkingCopyInfoPanel 的 body 只有一张表）
val table = lastElementNamed(doc, "table") ?: return
// 2) 已存在则跳过（按 href 属性检测，幂等）
if (hasOurLink(doc)) return
// 3) JDK 官方"往表格插行"的 API
doc.insertBeforeEnd(table, """<tr><td colspan="3"><a href="CUSTOM_MERGE">Enhance Merge…</a></td></tr>""")
```

配套加固（缺一不可）：
- **可观测性**：成功/失败各打一条 `LOG.info` / `LOG.warn`（v1 成功路径无日志，无法定位）。
- **失败兜底**：`insertBeforeEnd` 抛异常时，降级为**组件方案**（方案 B）。
- **自愈**：`CopiesPanel.updateList()` 每次刷新都重建面板与 Document，保留 `DocumentListener`；
  定时器从 1s 降为 2s（进一步降低 EDT 占用），并只把 `isVisible` 判断前置。

**方案 B（最终采用）：在容器里插入真实 `LinkLabel` 组件**

`CopiesPanel` 的面板容器是 `JBPanel(VerticalLayout(8))`，`WorkingCopyInfoPanel` 是它的直接子组件。
找到"直接子组件中存在 `WorkingCopyInfoPanel`"的容器，对每个面板执行：

```kotlin
container.add(LinkLabel.create("Enhance Merge…") { startFlow(wcInfoOf(panel)) }, index + 1)
container.revalidate(); container.repaint()
```

- 触发时机：给容器挂 `ContainerListener`（`componentAdded`）→ 刷新后自动补齐，比 DocumentListener 更可靠。
- 视觉：`JBUI.Borders.emptyLeft(8)` 对齐；
- 优劣：不依赖 HTML 解析，但会在 Merge From 下方多出 8px 间距，与表格行不同高。

> **实测结论（2026-10-18）：最终采用方案 B（组件插入），方案 A 已废弃。**
>
> 实测过程：方案 A（`insertBeforeEnd`）确实能把行插进去，但**每 2s 兜底定时器又插一次，
> 最终界面上出现 4 个「Enhance Merge…」**。用 JDK `HTMLDocument` 写的对照探针揭示了根因：
> 插入本身正常，但 **href 并不在元素自身属性上，而是挂在 `HTML.Tag.A` 的嵌套 AttributeSet、
> 且挂在 content 叶子元素而非 `<a>` 元素**——`getAttribute(HTML.Attribute.HREF)` 恒为 null，
> 只要属性检测失配就退化为无限重复插入。而这条检测逻辑又强依赖 svn4idea 的内部 HTML 结构，跨版本脆弱。
>
> 因此改用方案 B：在原版 `CopiesPanel` 的纵向容器里，直接在每个 `WorkingCopyInfoPanel` 之后插入真实
> `LinkLabel` 组件。**幂等性由"下一个兄弟是否为自己的组件"判定，不做任何属性/文本解析**；
> 自愈改由 `ContainerListener`（原版刷新 `removeAll()` 重建时触发）+ 工具窗弹出 + 2s 兜底定时器三重保障。
>
> 实测日志：`Injected Enhance Merge link into Subversion WC info panel: …` 与
> `Enhance Merge link ensured: panels=1, labels=1`；此后 40+ 秒（20+ 次定时器）无任何新增，确认幂等。

**WCInfo 解析**（反射，已验证可行）：`WorkingCopyInfoPanel` 是 `internal class`，
但 `var info: WCInfo?` 的 getter 在字节码中是 public，且不做名字改写。

```kotlin
val info = panel.javaClass.getMethod("getInfo").invoke(panel) as? WCInfo ?: return
val rootVf = info.rootInfo.virtualFile          // 优先
    ?: LocalFileSystem.getInstance().findFileByPath(info.path) ?: return
```

### 5.2 图2 —— 原版 Select Branch 弹窗（直接复用）

```kotlin
SelectBranchPopup.showForBranchRoot(
    project, rootVf,
    SelectBranchPopup.BranchSelectedCallback { _, configuration, branchUrl, _ ->
        val relative = configuration.getRelativeUrl(info.url)      // 当前 WC 在分支下的相对路径
        val sourceUrl = relative?.let { branchUrl.appendPath(it, false) }
        if (sourceUrl == null) { showError("…") } 
        else MergeFlow.start(MergeContext(vcs, sourceUrl, info, branchUrl.tail, rootVf))
    },
    EnhanceMergeBundle.message("popup.title.select.branch"),
    anchorComponent,        // 我们的链接组件，用作弹窗锚点
)
```

要点：
- 保留原版的 `trunk (trunk)` / `branches…` / `Configure Branches…` 三项与分隔线（由 `SelectBranchPopup` 自带，无需自己实现）。
- `getRelativeUrl` 返回 null 表示当前 WC 不在该分支配置树内 → 提示并中止（原版 `CopiesPanel` 用 `notNullize` 会抛异常，我们做得更友好）。
- 增加"不能从自身合并"守卫：`SvnUtil.isAncestor(a,b) || isAncestor(b,a)` → 报错返回（原版 `QuickMerge.execute()` 里的 `areInSameHierarchy`）。

### 5.3 图3 —— 仅 Revision Select + Cancel

`DialogWrapper` + 自绘面板，视觉照抄 `QuickMergeWayOptionsPanel`（`MultiLineLabelUI` + 按钮 + 说明标签 + `JBUI.Borders.emptyBottom(10)`）：

```
┌ Select Merge Variant ────────────────────────────┐
│ [ Revision Select ]                              │
│ Shows all revisions from target branch, merged   │
│ and not merged. For manual selection.            │
│                                                  │
│ [ Cancel ]                                       │
└──────────────────────────────────────────────────┘
```

- 标题 `Select Merge Variant`（与原版一致）。
- 按钮文字按用户要求用 **Revision Select**（用户写的 "Reversion" 为笔误，代码与文案统一为 Revision）。
- 复用原版 `quick.merge.variants.quick.select.explanation` 的语义文案，但由我们自己的 bundle 提供（本地化）。

### 5.4 图4 —— 修订列表（核心）

布局（对照图4 + 图5）：

```
┌ Merge from <branch> ────────────────────────────────────────────────────┐
│ ┌ 过滤栏（新增，TortoiseSVN 式）──────────────────────────────────────┐ │
│ │ 路径[      ] 作者[    ] 版本[   ]-[   ] Bug-ID[    ] 日期[  ]-[  ] │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ ┌ 工具栏：[全选][全不选][加载更多] ────────┐ ┌ 变更路径（复用浏览器）─┐ │
│ │ ☑ r27 ！  weixf  2026-10-17  1111111    │ │ src/…                  │ │
│ │ ☑ r24 ↗  weixf  2026-03-19  【需求编号】 │ │ └ rules/isFlow.dtd     │ │
│ │ …                                       │ │                        │ │
│ └─────────────────────────────────────────┘ └────────────────────────┘ │
│ 正在显示 30 个版本，介于 r2450 与 r2421 之间 —— 选中 4 个，包含 6 个路径 │
│                                              [Merge Selected] [Cancel] │
└─────────────────────────────────────────────────────────────────────────┘
```

**复用件（与图4 一致的关键）**

| 元素 | 实现 |
|---|---|
| 列表 | `TableView<SvnChangeList>` + `ListTableModel(FAKE_COLUMN)` + `setTableHeader(null)` + `setShowGrid(false)` |
| 行渲染 | `CommittedChangeListRenderer.renderChangeList(table, changeList)`（修订号 / 日期 / 作者 / 注释一条线） |
| 复选框 | 自绘 cell renderer：`JCheckBox`(WEST) + renderer(CENTER)，点击用 `ClickListener` 判命中区域（与 `ToBeMergedDialog` 完全同法） |
| 多选语义 | `QuantitySelection<Long>(startFromSelectAll = false)`（支持"全选后排除个别"的稀疏表示） |
| 右侧路径 | `CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges(selected, false)` |
| 分割 | `Splitter(false, 0.7f)`，`setDividerWidth(2)` |
| 按钮 | `setOKButtonText("Merge Selected")`，空选时置灰 |
| 尺寸记忆 | `getDimensionServiceKey() = "com.weixf.svn.mergeenhance.RevisionSelectDialog"` |

**键盘/交互**：`Space` 切换勾选、`Select All/Unselect All` 工具栏动作、双击无特殊行为（与原版一致）。

#### 过滤栏字段 → 生效层

| 控件 | 语义 | 生效层 | 说明 |
|---|---|---|---|
| 路径 | 前缀/包含匹配 `SvnChangeList.affectedPaths` | 客户端 | 原版无此能力 |
| 作者 | `ChangeBrowserSettings.USER` | 客户端（IDEA handler 内） | 循环补页规避"过滤后不足一页" |
| 版本 从/到 | 修订号闭区间 | **服务端上界**（起始游标）+ **客户端下界** | 见 §5.4.1 双模式 |
| Bug-ID | 正则（默认 `[A-Z][A-Z0-9]+-\d+`）从 comment 提取后比较 | 客户端 | 设置项可改正则 |
| 日期 从/到 | 提交日期闭区间（到日含当天全天） | **服务端**（`svn log -r {date}:{date}`） | 与图5 的"起始/结束"一致；**优先级高于版本区间** |
| 信息包含/排除 | 关键字（逗号分隔，AND/OR） | 客户端 | 沿用 v1 的 `RevisionFilter` 语义 |

**生效时机**：`DocumentListener` + 300ms `Alarm` 防抖 → 条件变化即**重置游标、从 HEAD 重查第一页**。
这就是"实时加载 SVN Log"的实现方式（不是把全量日志缓存在内存里过滤）。

#### 5.4.1 日期区间与版本区间并存时的筛选顺序（用户决策：先日期，后版本区间）

**决策**：两者同时填写时，**先用日期区间从服务端限定拉取范围，再在结果上按版本区间筛选**。
即日期区间**优先**（决定服务端查询），版本区间在其结果上二次过滤。

必须这样设计的原因（源码事实）：
`SvnCommittedChangesProvider.createRevision(date, change, default)` 对**同一边界**取 `date != null ? Revision.of(date) : change != null ? Revision.of(change) : default`
—— `DATE_BEFORE` 会**静默覆盖** `CHANGE_BEFORE`，`DATE_AFTER` 覆盖 `CHANGE_AFTER`。
若把两者都塞给 `ChangeBrowserSettings`，版本区间会被悄悄丢掉，且**分页游标（`CHANGE_BEFORE`）同样失效导致死循环翻页**。

因此显式分成两种模式：

| 模式 | 触发条件 | 服务端查询 | 版本区间 | 分页方式 |
|---|---|---|---|---|
| **A 日期模式** | 日期"从"或"到"任一已填 | `svn log -r {dateTo}:{dateFrom}`，`limit=0`（一次拉完该日期区间） | **客户端**过滤 | 内存分页（首屏 30，加载更多从已拉取结果取） |
| **B 游标模式** | 日期均空 | `MergeCalculatorTask.loadChangeLists(ctx, cursor, pageSize)` | **服务端上界**（起始游标）+ **客户端下界** | 服务端分页，循环补页 |

细节：
- 模式 A 与 TortoiseSVN 行为一致（拉完日期区间内全部日志后本地展示）；
  保护阈值：单次拉取 > 5000 条时在状态栏提示"结果过多，建议缩小日期范围"，但不阻断。
- 模式 B 的版本区间处理：
  - 「版本 到」→ 首屏起始游标 `beforeRevision = revTo + 1`
    （注意：`loadChangeLists` 内部会**排除**与 `beforeRevision` 相等的修订号，故必须 `+1`）；
  - 「版本 从」→ 客户端 `revision >= revFrom` 过滤，并在扫描中遇到 `revision < revFrom` 时置 `allLoaded = true`（早停），
    避免一路扫到 r1。
- 两种模式共用同一套客户端过滤器（路径 / 作者 / Bug-ID / 包含-排除 / 版本下界），顺序固定为：
  **版本区间 → 路径 → 作者 → Bug-ID → 包含 → 排除**。

```kotlin
class LogPageLoader(private val ctx: MergeContext) {
    private var cursor: Long = -1            // 模式 B：上一页最小修订号
    private var buffer: List<SvnChangeList> = emptyList()   // 模式 A：日期区间内的全量结果
    private var bufferPos = 0                               // 模式 A：内存分页位置
    var allLoaded = false; private set

    fun reset(filter: LogFilter) {
        cursor = filter.revTo?.plus(1) ?: -1   // 模式 B 起始上界；null → HEAD
        buffer = emptyList(); bufferPos = 0; allLoaded = false
    }

    /** 取下一页（至多 pageSize 条满足全部过滤条件的修订）。必须在后台线程调用。 */
    fun loadNextPage(filter: LogFilter, pageSize: Int): PageResult {
        return if (filter.hasDateRange()) loadFromDateBuffer(filter, pageSize)
               else loadByCursor(filter, pageSize)
    }

    private fun loadFromDateBuffer(filter: LogFilter, pageSize: Int): PageResult {
        if (buffer.isEmpty() && !allLoaded) {
            buffer = fetchByDate(filter)          // 一次 doLog(-r {dateTo}:{dateFrom}, limit=0)
            allLoaded = true                      // 日期区间内已全量，可一次判定到底
        }
        val matched = buffer.filter { filter.matchesClientSide(it) }
        val page = matched.drop(bufferPos).take(pageSize)
        bufferPos += pageSize
        return PageResult(page, bufferPos >= matched.size)
    }

    private fun loadByCursor(filter: LogFilter, pageSize: Int): PageResult {
        val matched = ArrayList<SvnChangeList>()
        var batches = 0
        while (matched.size < pageSize && !allLoaded && batches++ < MAX_BATCHES /*10*/) {
            val (raw, done) = MergeCalculatorTask.loadChangeLists(ctx, cursor, pageSize)  // 实时 doLog
            if (raw.isEmpty()) { allLoaded = true; break }
            cursor = raw.last().revision                     // 游标永远推进
            var stop = false
            for (item in raw) {
                if (filter.revFrom != null && item.revision < filter.revFrom) { stop = true; break }
                if (filter.matchesClientSide(item)) matched += item
            }
            if (stop || done) allLoaded = true
        }
        return PageResult(matched, allLoaded)
    }
}
```

- 首屏（模式 B、无过滤）：`pageSize = settings.pageSize`（默认 **100**，对齐 TortoiseSVN）→ 一条 `svn log --limit 101`，毫秒级返回。
- `MAX_BATCHES` 保证最坏情况 ≈ 300 条日志的扫描量，不会因为"该分支没有匹配项"卡死。
- 全部在 `Task.Backgroundable` 里跑（不阻塞 EDT），带 `ProgressIndicator` 可取消。

#### 5.4.2 2026-10-19 修正（R1+R2+R3：搜索精准度与深历史可达性）

实测暴露三类问题（含 `#` 的备注搜不到、大库啥也搜不到、改搜索词即清空重拉且丢深历史），根因与修复：

1. **R1 日期区间默认开启 + 预填首屏窄窗口**：`dateRangeEnabled` 原默认 `true`，对话框打开时
   `initDateRange` 把起止日历设成首屏日志的日期跨度，于是 `hasDateRange()` 恒真 → 走模式 A 只拉那一小段，
   更早提交永不入可搜索集。修复：默认值改为 `false`，默认走模式 B 从 HEAD 往回扫全量；日期区间改为用户显式勾选才生效。
2. **R2 改搜索词清空重拉**：原 `onFilterChanged` 每次 `model.setItems(emptyList())` 后从 HEAD 重拉第一页，
   已下载版本被丢弃、无法累计深度。修复：对话框维护一份**内存中已下载原始全集** `loadedAll`，
   过滤条件变化时**只对该全集即时重过滤**（零远端请求），仅当结果不足且 `loader.allLoaded == false` 时才向后扩充加载。
3. **R3 扫描深度硬上限**：模式 B 受 `MAX_BATCHES=10` 截断。修复：搜索时通过
   `LogPageLoader.loadNextPageRaw`（返回未文本过滤的原始修订）持续向后扫描，直至凑够 `SEARCH_MATCH_TARGET`(100)
   条命中或 `loader` 到底或触达 `MAX_SEARCH_ROUNDS`(200) 安全上限；「加载更多 / 全部显示」同样在 `loadedAll` 全集上操作，
   深历史可被命中且不静默丢弃。

> 该修正仅在**单次对话框会话的内存**内运作，关掉对话框即释放，不落盘、不累积磁盘缓存（小笔记本零负担）。
> 跨会话秒开所需的持久化日志缓存作为可选后续，默认关闭、带限容。

### 5.5 合并执行（复用原版执行链）

```kotlin
object MergeExecutor {
    fun execute(ctx: MergeContext, selected: List<SvnChangeList>, dryRun: Boolean = false) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val factory = ChangeListsMergerFactory(selected, false, false, true) // recordOnly/invertRange/分组连续段
        SvnIntegrateChangesTask(
            ctx.vcs, WorkingCopyInfo(ctx.wcInfo.path, true), factory,
            ctx.sourceUrl, ctx.mergeTitle, dryRun, ctx.branchName,
        ).queue()
    }
}
```

相比 v1 的自绘 `dry-run → 确认 → MergeClient` 三段式，本方案获得：
- 连续段自动压缩（`Merger.MergeChunk`，与 `svn merge -c` 语义一致，含 `lowest-1` 处理）；
- 进度条 + 逐段 `checkCanceled`；
- **冲突自动进入 IDEA 的合并冲突解决器**（`ResolveWorker`）；
- 合并后 `RefreshVFsSynchronously` 刷新 VFS 与 Local Changes，无需手工 `markEverythingDirty`；
- 统一错误上报（`AbstractVcsHelper.showErrors`）。

可选增强（P2）：合并前接 `LocalChangesPromptTask`，实现"本地改动相交 → 搁置/查看/继续/取消"的原版体验。

### 5.6 设置项（`EnhanceMergeSettings`，applicationService）

| key | 默认 | 说明 |
|---|---|---|
| `pageSize` | `100` | 首屏条数与"加载更多"步长（10–500），对齐 TortoiseSVN 的 `NumberOfLogs` 默认 100 |
| `computeMergeStatus` | `true` | **默认开启**。计算并显示"已合并 / 未合并 / 外来修订"图标；需 `svn:mergeinfo` 查询（`OneShotMergeInfoHelper.prepare()`）。关闭时走纯 `svn log` 快速路径，无图标、不查 mergeinfo |
| `bugIdPattern` | `[A-Z][A-Z0-9]+-\d+` | Bug-ID 过滤用的正则 |
| `defaultDateRangeDays` | `0` | >0 时过滤栏默认填"最近 N 天" |
| `filterFields` | 空（=全选） | 过滤栏上次勾选的字段（存字段 id 字符串，不是枚举名）；**2026-10-18 新增** |
| `dateRangeEnabled` | `false` | 日期区间开关状态（**默认关闭**，对齐 TortoiseSVN，避免静默窄窗口）；**2026-10-18 新增，2026-10-19 由 `true` 改为 `false`** |
| `useRegex` | `false` | 正则开关；**2026-10-18 新增** |
| `caseSensitive` | `false` | 大小写敏感开关；**2026-10-18 新增** |

> 后4 项**不在设置页做 UI**（改动后自动写回即可）：它们属于"顺手记住"的交互偏好，
> 关闭对话框重开时沿用，避免用户每次重新勾。存字符串 id 而非枚举名，是为了让枚举改名
> 不会导致旧配置反序列化失败。

> 注：**不要**写 `SvnConfiguration`（那是原插件的配置，改了会影响原版 Merge From 行为）。
>
> **注册层级（2026-10-18 更正）**：实现为**应用级** `applicationService` + `applicationConfigurable`，
> 而非早期的 projectService 方案——IDEA 的 VCS 设置惯例是应用级（内置 Subversion 的
> `SvnConfigurable` 同处 Version Control 分组），且 `projectConfigurable` 的实例由平台**无参构造**、
> 拿不到 `Project` 句柄，与 projectService 无法自然衔接。
>
> **标题本地化（2026-10-18 实测修正）**：EP 用 `key="settings.display.name"` 引用插件 bundle
> （平台语义 `messageOrDefault(bundle, key, displayName)`，key 优先、displayName 兜底），
> 与原版 svn4idea 的 `key="configurable.name.svn.general"` 一致。**只写 `displayName` 会硬编码英文**，
> 装了中文语言包的 IDE 里设置页标题仍是英文（T9 验收时发现，已修）。

### 5.7 本地化

- 新建 `messages/EnhanceMergeBundle.properties`（**仅英文基包**），
  注册 `<resource-bundle>messages.EnhanceMergeBundle</resource-bundle>`。
- 全部自绘 UI 的文案走 bundle；复用原版对话框的文案由 IDEA 自带 bundle 提供。
- 顺带修正 §2.4 的硬编码问题。

**语言包感知策略（实测结论 2026-10-18）**

问题：直接继承 `DynamicBundle` 并同时提供 `_zh_CN` 资源，会在**英文 IDE** 里弹出中文
（实测：中文 Windows + 无语言包的 IDEA 2022.1，`Locale.getDefault()=zh_CN` ⇒ 命中 `_zh_CN`，
而 svn4idea 自带 bundle 无 `_zh_CN` 所以保持英文，形成不一致）。

**JDK 陷阱（离线探针实测，必须记住）**：
```
default locale = zh_CN
getBundle(base, Locale.ENGLISH, loader, control) -> 返回 _zh_CN   ← 强制英文失效
getBundle(base, Locale.ROOT,    loader, control) -> 返回基包     ← 只有 ROOT 稳定拿基包
```
即"请求英文"仍可能通过默认 locale 回退拿到中文资源。

**实现（`EnhanceMergeBundle`）**：
1. `DynamicBundle.findLanguageBundle() != null` ⇒ 平台确实装了语言包；
2. 装了 → 用 `DynamicBundle.getLocale()` 精确匹配（中文包 → `_zh_CN`，日文包 → `_ja`…）；
3. 没装 → 用 **`Locale.ROOT`** 拿基包（英文），与英文 IDE 界面一致；
4. 兜底：若解析出的 bundle 语言与目标语言不符（发生回退），也退回基包，避免"日文 IDE 显示中文"；
5. 自带 UTF-8 解码（IntelliJ 的 properties 是 UTF-8，`ResourceBundle` 默认按 ISO-8859-1 读）。

**实测验证**：同一台机器先后两次启动，日志分别为
`languagePack=false … bundleLocale=`（英文）与
`languagePack=true, platformLocale=zh … bundleLocale=zh_CN`（装语言包后自动切中文）。
即：未装语言包不冒中文，装了语言包自动跟随，无需改代码。

### 5.8 已合并状态图标（默认开启）

**数据来源**：`OneShotMergeInfoHelper(mergeContext)`（`public class … implements MergeChecker`）。

| 状态 | `MergeCheckResult` | `ListMergeStatus` | 图标含义 |
|---|---|---|---|
| 已合并 | `MERGED` | `ListMergeStatus.MERGED` | ✔ 灰勾 |
| 未合并 | `NOT_MERGED` | `ListMergeStatus.NOT_MERGED` | 无图标（可合并） |
| 外来修订 | `NOT_EXISTS` | `ListMergeStatus.ALIEN` | ↳ 蓝箭头（来自其它分支，不在当前 WC 历史里） |
| 计算中 | — | `ListMergeStatus.REFRESHING` | 占位，异步刷新后替换 |

**实现要点（原版 `ToBeMergedDialog.refreshListStatus` 的做法，照搬）**：

1. `MergeChecker.prepare()` 是**网络调用**（取 `svn:mergeinfo`），必须在后台任务里执行；
   关闭开关时**完全跳过**，列表立即可用。
2. 状态按条目计算：`status = toListMergeStatus(mergeChecker.checkList(changeList))`，
   存入 `Map<Long, ListMergeStatus>`（**并发访问 → `Collections.synchronizedMap`**）。
3. 每计算 10 条 `invokeLater { table.revalidate(); table.repaint() }` 一次 —— 避免逐条重绘造成卡顿。
4. 新加载的一页（`loadMore`）在后台补算状态；计算期间显示 `REFRESHING` 占位图标。
5. 状态**不影响可勾选性**（与 v1 spec R10"已合并默认不可勾选"不同）：
   原版 `ToBeMergedDialog` 也允许勾选已合并项（重复合并 SVN 会自行处理）。
   仅作视觉区分 + 提供"仅显示未合并"过滤项（可选）。
6. 开关关闭时：状态列渲染为 `NULL` 图标（`CommittedChangeListRenderer` 的图标提供者返回 null），
   且**不调用** `prepare()`，因此首屏时间不受影响。

---

## 6. 任务拆解

| # | 任务 | 关键产出 | 验收 |
|---|---|---|---|
| T0 | 骨架与诊断设施 | 重写 `WcInfoLinkInjector`（`insertBeforeEnd` + INFO 日志）；`gradlew runIde` 打开真实 SVN 项目 | `idea.log` 出现 `injected Enhance Merge link`；图1 出现 Enhance Merge |
| T1 | 图1 兜底 | 方案 B `LinkLabel` + `ContainerListener` | 若 T0 失败，图1 仍可用 |
| T2 | 图2 | `MergeFlow` 接 `SelectBranchPopup` + `MergeContext` 构造 + 自身合并守卫 | 弹窗与原版逐项一致；选中分支后进入图3 |
| T3 | 图3 | `MergeVariantDialog`（Revision Select + Cancel） | 只有 2 个按钮，描述文案正确 |
| T4 | 图4-列表 | `RevisionSelectDialog` 重写（TableView+renderer+QuantitySelection+CommittedChangesBrowser+Splitter） | 首屏有数据、勾选/全选/空格切换正常、右侧路径随选中变化 |
| T5 | 图4-分页 | `LogPageLoader`（模式 B 游标分页）+ 首屏 `settings.pageSize`（默认 100）+ "加载更多" + 循环补页 | 首屏耗时 < 1s；翻页游标正确、到底后按钮置灰 |
| T6 | 图4-过滤 | `LogFilterPanel` + `LogFilter` + 防抖重查 + **模式 A 日期模式** | 6 类条件单独/组合正确；改条件后从 HEAD 重查；**日期与版本并存时先日期后版本**；状态栏数字正确 |
| T7 | 合并执行 | `MergeExecutor`（SvnIntegrateChangesTask） | 选中 3 条不连续修订能正确合并；冲突能弹解决器；Local Changes 刷新 |
| T7 | 合并执行 | `MergeExecutor`（SvnIntegrateChangesTask）+ `LocalChangesPromptTask` 前置 | 选中 3 条不连续修订能正确合并；冲突能弹解决器；Local Changes 刷新；合并前本地改动提示与原版一致 |
| T8 | 图4-合并状态 | `MergeChecker` + `ListMergeStatus` 状态图标、`[Already merged]` 文件标记、快速搜索、diff 快捷键 | 图标语义与原版一致；已合并项仍可勾选；右栏已合并文件有标记 |
| T9 | 设置与本地化 | `EnhanceMergeSettings` + `EnhanceMergeConfigurable` + bundle（设置项四项） | 改 pageSize 生效；标题随语言包本地化；界面无硬编码英文 |
| T10 | 测试与收尾 | `LogFilterTest`（≥12 用例）、`LogPageLoader` 双模式测试、`QuantitySelection` 语义测试、README/checklist 同步 | `gradlew test` 全绿；文档与实现一致（`svn4idea/` 保留，不删） |

依赖：T0 → T1（兜底）→ T2 → T3 → T4 → T5 → T6 → T7；T8 ← T4；T9 可与 T5–T8 并行；T10 收尾。

---

## 7. 验证方案（针对"从未运行过"这一根因）

1. **`gradlew runIde` + 真实 SVN 项目**（不是 `buildSearchableOptions`）。判据：
   - `idea.log` 含 `Loaded custom plugins: SVN Enhance Merge`；
   - 含我们新增的 `WcInfoLinkInjector` INFO 日志；
   - 手动点开 Version Controls 工具窗 → Subversion Working Copies Information，逐一对照图1–图5。
2. **性能判据**：首屏 30 条应在 1s 内（仓库 r≤3000）；"加载更多"每次 < 1s。
3. **单测**：`LogFilter`（6 类条件 × 单独/组合/边界）、`QuantitySelection` 的"全选后排除"语义、
   `RevisionRangeFormatter`（保留）。
4. **回归**：确认原版 `Merge From…` 与 `Configure Branches…` 行为完全不受影响
   （我们不写 `SvnConfiguration`、不替换任何原版类）。

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| `insertBeforeEnd` 仍不生效 | 图1 无入口 | T1 兜底组件方案；两个方案共用 `startFlow` |
| `WorkingCopyInfoPanel` 在后续 IDEA 版本改结构 | 图1 失效（仅降级，不影响其它） | 类名/方法名双重反射 + 失败仅打日志，不抛异常 |
| `MergeCalculatorTask.loadChangeLists` 含 `-g`（includeMergedRevisions） | 老仓库首屏偏慢 | 设为可切换"快速模式"（改用 `SvnCommittedChangesProvider.getCommittedChanges`，无 `-g`） |
| 客户端过滤导致一页匹配不足 | 列表稀疏 | §5.3 循环补页 + `MAX_BATCHES` 上限 |
| 复用 public 但语义稳定的原版类 | 大版本升级可能签名变化 | 集中在 `LogPageLoader` / `MergeExecutor` 两个薄封装里，升级只需改这两处 |
| 首次 `runIde` 可能暴露更多编译期/运行期问题 | 排期 | 把 T0 作为独立的"打通验证"里程碑，先跑通再加功能 |

---

## 9. 实施结果与合规整改记录（2026-10-18）

本节记录**实现与上文设计的差异**，以及一次面向 IntelliJ 官方插件规范的审查整改。

### 9.1 实现与设计的差异（以代码为准）

| 上文设计 | 实际实现 | 原因 |
|---|---|---|
| `EnhanceMergeAction` 为内部入口（合成事件调用） | **普通类，不继承 `AnAction`** | 它从未注册到任何菜单，继承后 `update()`/`actionPerformed()` 与 `CommonDataKeys`/`FileUtil` 等导入全是永不执行的死代码 |
| `mergeStatusMap` 用 `synchronizedMap` | `ConcurrentHashMap` | 后台线程写、EDT 读，无需全图加锁 |
| 过滤栏防抖用 `Alarm` | `javax.swing.Timer`(300ms) | 面板是 Swing 组件，Timer 更直接 |
| 行渲染用 `CommittedChangeListRenderer` | 自绘 `SimpleColoredComponent` | 只有自绘才能按命中片段分段着色（TortoiseSVN 式高亮） |
| 合并执行仅 `SvnIntegrateChangesTask` | 前面加 `LocalChangesPromptTask`（`QuickMerge` 仅作宿主） | 与原版 Merge From 的"合并前本地改动提示"保持一致 |
| 类 `WcInfoLinkInstaller` | 文件 `WcInfoLinkInjector.kt` + private `LinkInstaller` | 文件名与 public 类名对齐；两类职责分离（`StartupActivity` 由平台创建，Timer/messageBus 需明确释放） |

### 9.2 官方规范整改项（已完成）

| 类别 | 问题 | 处理 |
|---|---|---|
| 生命周期 | 后台任务回调无 dispose 守卫（对话框关闭后仍往已销毁 UI 写数据） | `loadMore`/`loadAll`/`onFilterChanged` 的三个回调 + 所有 `invokeLater` 加 `isDisposed \|\| project.isDisposed`（守卫写在 lambda 内） |
| 并发 | `mergeCheckerPrepared` 跨线程不可见，可能重复 `prepare()`（并发初始化同一缓存） | `@Volatile` + 互斥锁；`preparedHere` 保持"仅首次刷新右栏"语义 |
| 按键 | `e.consume()` 写在 `keyReleased` 不起作用 | 消费移到 `keyPressed`，切换留在 `keyReleased` |
| 打包 | Kotlin stdlib（4 jar）+ `annotations.jar` 被打包，官方 `verifyPluginConfiguration` 报警告 | `kotlin.stdlib.default.dependency=false`；zip 2.10MB → **434KB** |
| 上架元数据 | 缺 `pluginIcon` / `vendor` email+url / `change-notes` / 第三方许可声明 | 全部补齐（含浅/深主题 40×40 图标、`THIRD-PARTY-LICENSES.txt`） |
| 资源泄漏 | 快捷键注册传 `null` Disposable；关闭项目后残留注入的 `LinkLabel` | 传对话框 `disposable`；dispose 时 `removeInjectedLinks()` |
| 死代码 | `LogFilter.dateMatchTexts`、两份 properties 的 `filter.by.tooltip` 从未被调用 | 删除 |
| 可用性 | 过滤栏勾选/正则/大小写开关关闭对话框即丢 | 持久化到 `EnhanceMergeSettings`（存字段 id 字符串） |
| 无障碍 | 纯图标清除按钮无可读名称 | 加 `accessibleContext.accessibleName` |
| 工具链 | 未接官方 API 兼容性校验 | 配 `runPluginVerifier`（支持 `-PverifierLocalIde=<已解压 ideaIC>` 免下载） |

**有意未改**：`getDimensionServiceKey` 无法按项目隔离——带 `Project` 参数的重载是 IDEA 2023.2+ 才有，
2022.1 只有无参版本，用了会破坏 `since-build=221`。已在代码注释写明。

### 9.3 验证结果（2026-10-18 实跑）

| 检查 | 结果 |
|---|---|
| `gradlew test` | 46/46 通过（LogFilter 25 + LogPageLoader 12 + QuantitySelection 9） |
| `gradlew verifyPlugin` / `verifyPluginConfiguration` | 通过 / **零告警** |
| `gradlew buildPlugin` | 成功，zip 434KB，`pluginIcon.svg` 等位于插件目录根部 |
| `gradlew runPluginVerifier`（官方 Plugin Verifier 1.410） | **Compatible**（IC-221.5080.210） |
| `gradlew verifyOnIde -PideaVersion=2022.1` | **PASS**，issues 0 |

### 9.4 遗留待决（不影响当前使用）

入口靠**反射**调 `WorkingCopyInfoPanel.getInfo()` + 2s 定时扫组件树注入 UI，
不符合官方"仅用公开 API"要求，构成 Marketplace 审核风险（内部自用无影响）。
是否改走官方 `ToolWindowFactory` / 扩展点，待两项前提确定后再决策：
① 官方 `ToolWindowFactory` 与相关扩展点的调研结论；② 最终是否上架 JetBrains Marketplace。
**在此之前保持现状**（改动会变更入口形态，属产品决策）。
