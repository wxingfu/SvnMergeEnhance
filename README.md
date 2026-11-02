# SVN Enhance Merge — IDEA SVN 合并增强插件

[![CI](https://github.com/wxingfu/SvnMergeEnhance/actions/workflows/ci.yml/badge.svg)](https://github.com/wxingfu/SvnMergeEnhance/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/badge/Marketplace-SVN%20Enhance%20Merge-orange)](https://plugins.jetbrains.com/)

一个 **独立的 IntelliJ IDEA 增强插件**：在 IDEA 内置 Subversion 插件之上，提供**与原版「Merge From → Quick Manual Select」界面一致**的逐版本勾选合并——并在工具栏上方增加**关键词搜索过滤框**，解决版本过多、时间跨度大时只能逐个滚动查找的问题；合并执行走**与原版「Merge From」完全一致**的链路。

> 本插件 **不 fork、不修改** IDEA 内置 Subversion 插件（svn4idea）源码，仅依赖其公开 API，可随 IDEA 正常构建与升级。

---

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [使用指南](#使用指南)
- [设置项](#设置项)
- [技术实现](#技术实现)
- [项目结构](#项目结构)
- [开发与构建](#开发与构建)
- [测试](#测试)
- [兼容性说明](#兼容性说明)
- [已知限制与后续计划](#已知限制与后续计划)
- [第三方许可](#第三方许可)
- [隐私](#隐私)
- [许可证](#许可证)
- [贡献指南](#贡献指南)

---

## 功能特性

### 入口：VCS 工具窗「Enhance Merge」标签页（复刻 Working Copies Information 面板）

- 经官方 `changesViewContentEP` 在 VCS 工具窗注册**常驻标签页**，与「Subversion Working Copies Information」并列；
- 标签页列出项目全部**工作副本**（每个工作副本一个信息块：本地路径 / URL / Format / Depth / Working copy root），
  块内提供 **Cleanup**（复用原版 `CleanupWorker`）与**加粗的「Merge From…」链接**（对齐原版 WC Info 面板）；
- 仅在项目启用 Subversion VCS 时可见；不注册任何菜单/右键项、**零反射**（纯公开 API，不注入任何其它插件、零轮询）。

### 完整合并流程

```
Enhance Merge 标签页（工作副本信息块列表）
  └─ 每块「Merge From…」链接
       └─ ① SelectBranchPopup          复用原版分支选择弹窗（trunk / branches / Configure Branches…）
            └─ ② Select Merge Variant    合并方式（Revision Select / Cancel），对齐原版步骤
                 └─ ③ RevisionSelectDialog   模态修订选择（原版布局 + 关键词搜索过滤 + 分页 + 合并状态）   ← 本插件核心
                      └─ ④ MergeExecutor     复用原版 SvnIntegrateChangesTask        ← 与原版一致
                           1) LocalChangesPromptTask   本地改动与合并路径相交时的原版提示
                           2) SvnIntegrateChangesTask  进度条 / 冲突解决 / 更新信息树
```

### 修订选择界面（核心增强）

界面布局、行渲染、工具栏（全选 / 全不选 / **Load +100 / Load +500**）、右栏变更树均与原版 `ToBeMergedDialog` 一致；**唯一新增**是工具栏上方独立一行的关键词搜索框。

> 「+N」两个按钮与原版同款：走 `CustomComponentAction` 在工具栏里渲染**真实 Swing 按钮**（带边框的立体按钮，文字 `Load +100` / `Load +500`），而非平台默认的扁平文字按钮；加载中按钮位置显示 `Loading...`（原版语义）。

| 能力 | 说明 |
|---|---|
| 关键词搜索过滤 | 工具栏上方显式搜索框；匹配范围 = **全字段**（提交说明 + 全部变更路径 + 作者 + 版本号 + 日期文本）；多关键词**空格分隔取且**，子串匹配、**大小写不敏感**；`Esc` / `✕` 清空恢复。同行显示「匹配 M / 已加载 N」 |
| 清单内容 | **只含「源分支自身」的修订**（不含合并源分支的副本——原版会把它们也列进来，但它们在目标上不存在、合并不进去，行首还会缺状态图标） |
| 过滤范围 | **只过滤已加载的修订**（纯客户端，零服务器请求）；命中不足时点「Load +100 / Load +500」加载更多，新加载数据同样按当前关键词过滤 |；**过滤不改变勾选与合并集合** |
| 分页加载 | 首屏 + 「Load +100 / Load +500」实时向仓库翻页（游标分页，跟踪已加载全集的最早修订号，过滤后不跳段） |
| 合并状态 | 每行显示 已合并 ✔ / 外来修订（无图标）/ 计算中 图标；右栏对**已合并**文件追加 `[Already merged]` |
| 变更文件 | 右侧 `CommittedChangesBrowser` 展示所选修订的变更路径，支持 `Ctrl+D` 查看差异 |
| 勾选 | 复选框在行首最左，鼠标点击或 `Space` 键切换；全选 / 全不选；稀疏语义（全选后排除个别）跨翻页保留 |；**勾选以修订号为准、不受过滤影响**（过滤只是视图，合并集合不变）；有过滤时「全选」只选当前可见行 |

### 合并执行：与原版逐字节一致

从点 `Merge Selected` 起，只跑原版 `LocalChangesPromptTask` + `SvnIntegrateChangesTask` 两个类：本地改动提示（搁置/查看/继续/取消）、进度条、冲突解决（`ResolveWorker`）、更新信息树全部由原版负责，**本插件不自创任何提示或对话框**。

---

## 环境要求

| 项 | 要求 |
|---|---|
| IDE | IntelliJ IDEA **2022.1 – 2026.2.3**（插件 `since-build=221` / `until-build=262.*`；2026.2.3 已回归验证，更高版本待验证后放开） |
| VCS 插件 | **Subversion** 插件。⚠️ **IDEA 2026.x 起 JetBrains 已把 SVN 从发行版 unbundle**：2022.1–2025.x 自带；2026.x 需先从 Marketplace 安装 JetBrains 官方 **Subversion** 插件，否则本插件因 `<depends>Subversion</depends>` 无法加载 |
| 构建 JDK | **JDK 11**（与 IDEA 2022.1 运行时 JBR 11 对齐；JDK 8 不可用） |
| 构建工具 | Gradle 8.x（推荐使用仓库自带 wrapper） |

本项目构建环境约定（可按需调整）：

- `gradle.properties` 中 `org.gradle.java.home` 指向本机 JDK 11 路径；
- Gradle 缓存目录由环境变量 `GRADLE_USER_HOME` 指定。

---

## 安装

### 方式一：Marketplace（推荐）

`Settings/Preferences` → `Plugins` → 搜索 **SVN Enhance Merge** → `Install`。

> 安装前若存在旧版本（插件 id 变更过：`com.svn.revisionselect` → `com.weixf.svn.mergeenhance`），
> 请先卸载旧插件，避免两个版本共存。

### 方式二：手动安装 zip

1. 从 [Releases](https://github.com/wxingfu/SvnMergeEnhance/releases) 下载
   `SvnMergeEnhance-<version>.zip`（或自行执行 `buildPlugin` 生成）；
2. IDEA → `Settings/Preferences` → `Plugins` → ⚙️ → `Install Plugin from Disk…`；
3. 选择该 zip 文件 → 确认 → 重启 IDEA。

### 方式三：从源码构建后安装

见 [开发与构建](#开发与构建)，执行 `buildPlugin` 后使用 `build/distributions/*.zip` 按上述步骤安装。

> ⚠️ **改了代码必须重新 `buildPlugin` 并重装 zip**：`plugin.xml`（含扩展点注册、设置页标题的
> bundle key）只在插件加载时读取一次，重启 IDE 不会让已安装的旧 jar 生效。

### 前置条件

- 目标项目已启用 IDEA 的 **Subversion** 集成（`Settings` → `Version Control` 中有 SVN 映射）；
- 本机 SVN 客户端可用（与使用内置 SVN 插件的要求一致）。

---

## 使用指南

以「把 `branches/feature-x` 上的若干修订合并到当前工作副本」为例：

1. **发起**：打开 `Version Control`（版本控制）工具窗，切到 **Enhance Merge（增强合并）** 标签页
   （与 **Subversion Working Copies Information** 并列）。标签页里列出本项目的工作副本，
   在目标工作副本的信息块上点击 **Merge From…**。

2. **① 选择源分支**：弹出的是**原版分支选择弹窗**（trunk / branches / Configure Branches… 俱全），选中 `branches/feature-x`。

3. **② 确认合并方式**：本插件仅提供 **Revision Select**（由你在下一步逐个勾选修订）→ 确认。

4. **③ 等待日志加载**：后台任务直连仓库拉取该分支修订（可取消，首屏条数见[设置项](#设置项)）。

5. **④ 选择修订**：
   - 勾选需要合并的版本（鼠标点行首复选框，或选中行后按 `Space`）；
   - 工具栏上方的**搜索框**输入关键词缩小范围：多关键词空格分隔取且，匹配提交说明 / 路径 / 作者 / 版本号 / 日期（大小写不敏感）；搜索只过滤**已加载**的修订，命中不足时点「Load +100 / Load +500」继续加载（不会自动请求服务器），新加载数据同样按当前关键词过滤；
   - 「Load +100 / Load +500」继续向后翻页；
   - 选中行后 `Ctrl+D` 查看该修订差异。

6. **⑤ 执行合并**：点击 `Merge Selected` →
   - 若工作副本有与待合并路径相交的**本地改动**，先弹原版提示（搁置 / 查看 / 继续 / 取消）；
   - 确认后状态栏出现原版合并进度；
   - 产生冲突则走 IDEA 原生冲突解决，结束后弹出原版「更新信息」树。

> 提示：合并结果只会出现在工作副本中，仍需通过 SVN `Commit` 提交到目标分支 —— 这与 TortoiseSVN / IDEA 原生合并的行为一致。

---

## 技术实现

### 总体思路

独立 Gradle IntelliJ 插件 + `<depends>Subversion</depends>`，**只调用内置 SVN 插件的公开 API**：

| 能力 | 使用的内置插件 API |
|---|---|
| 分支选择 | `SelectBranchPopup.showForBranchRoot(...)` + `SvnBranchConfigurationNew`（原版弹窗，零自绘） |
| 修订日志 | `SvnCommittedChangesProvider.getCommittedChanges(settings, location, size+N)`
  （游标分页，单通道；**不走缓存**、实时查仓库；**只含源分支自身的修订**，不含合并源分支的副本；
  搜索词为客户端过滤，不改变服务端查询范围；翻页时 N=2 并剔除重复的游标条目——`CHANGE_BEFORE` 区间含端点，
  不剔除会**静默丢一条更新的新修订**） |
| 列表渲染 | `TableView` + `ListTableModel` + `CommittedChangeListRenderer`（原版行渲染）+ `CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges` |
| 勾选语义 | `QuantitySelection<Long>`（稀疏表示：全选后排除个别） |
| 合并状态 | `OneShotMergeInfoHelper(context)`（`MergeChecker`）+ `ListMergeStatus`（图标）/ `ChangeNodeDecorator`（`[Already merged]`） |
| 合并执行 | `SvnIntegrateChangesTask` + `ChangeListsMergerFactory(lists, false, false, true)` + `LocalChangesPromptTask` |

编译期通过 `intellij { plugins.set(listOf("Subversion")) }` 把捆绑的 Subversion 插件类暴露到 classpath；运行期由 `<depends>` 声明依赖。

### 关键实现点

1. **日志加载与搜索过滤**（`LogPageLoader` + `RevisionSearchField` + `LogFilter`，均带可取消的后台任务）

   | 环节 | 行为 |
   |---|---|
   | 加载 | 游标分页（单通道）：首屏 + 「Load +100 / Load +500」，游标 = 已加载全集的最早修订号 |
   | 过滤 | 纯客户端：只作用于已加载集合，**零服务器请求**；命中不足不自动请求，由用户显式「Load +100 / Load +500」加载更多 |
   | 服务端入口 | 仅两个：**首屏**、**「Load +100 / Load +500」**（搜索词不改变服务端查询范围） |

2. **合并状态**（T8）后台线程批量调 `MergeChecker.checkList()`，映射 `MERGED→MERGED` / `NOT_EXISTS→ALIEN` / 其余→`REFRESHING`，每 10 条批量重绘一次；图标渲染在**复选框之后**（命中区 `[cell.x, cell.x+checkboxWidth]` 与视觉对齐）。右栏 `[Already merged]` 取 `getNotMergedPaths` 的补集。

3. **勾选与过滤解耦**：勾选状态按修订号存于 `QuantitySelection`，过滤/翻页只改展示列表，不丢勾选。

4. **合并执行**（`MergeExecutor`）与 `QuickMerge.merge(List)` 等价：`saveAllDocuments()` → `LocalChangesPromptTask`（QuickMerge 仅作宿主，不触发其合并流程）→ `SvnIntegrateChangesTask(...).queue()`。**不写 `SvnConfiguration`**，不影响原版「Merge From」行为。

5. **本地化（零反射）**：自绘文案全部走 `messages/EnhanceMergeBundle`，按 **IDE 当前界面语言**选文案——
   公开 API `DynamicBundle.getLocale()`（2022.1 ~ 2026.x 均为 `public static`，返回**语言包语言**而非系统语言）：
   装了中文语言包 → `_zh_CN`，否则 → 英文基包。两个坑位已在代码里处理：
   ①英文一律用 `Locale.ROOT` 请求（否则中文 Windows 上 JDK 会把 `_zh_CN` 命中 → 英文 IDE 里出现中文）；
   ②取到 bundle 后校验语言是否匹配（装了日文语言包而无 `_ja` 时不回退成中文）。
   设置页标题**刻意不写** EP 的 `key`/`displayName`（那会让平台按 `Locale.getDefault()`＝**操作系统语言**解析，
   在「中文系统 + 英文 IDE」上显示中文），改由 `Configurable.getDisplayName()` 提供，与对话框语言判定统一。

### 与原插件的关系

- **复用**：上表全部公开 API（svn4idea 不属于平台 API，无兼容承诺；升级 IDE 前须按 `.codebuddy/rules` 的「svn4idea API 兼容性基线」逐项核对签名）；
- **不复用**：`SvnBranchConfigurationManager`（internal）、`QuickMerge` 的合并流程、`ToBeMergedDialog`（不可改造）；
- **不引入**：SVNKit 直接依赖（统一经由内置插件 API，认证与连接行为与原生一致）；
- **参考源码**：svn4idea 最新版源码位于仓库外的 `G:\gitDownload\svn4idea`（本机离线参考，**不参与构建、不提交**），用于核对公开 API 签名与跨版本兼容性。

---

## 项目结构

```
SvnMergeEnhance/
├── build.gradle.kts                     # 构建脚本（IPGP 1.17.3 / Kotlin 1.7.22 / JDK 11 / mockk 测试）
├── settings.gradle.kts                  # rootProject.name = SvnMergeEnhance
├── gradle.properties                    # org.gradle.java.home 指向本机 JDK 11
├── LICENSE                              # Apache License 2.0 全文（随插件包分发）
├── scripts/
│   ├── verify-plugin.ps1                # Windows 回归验证（切版本启沙箱 + 扫插件异常日志）
│   └── verify-plugin.sh                 # Unix 版（同一逻辑）
├── src/main/kotlin/com/weixf/svn/mergeenhance/
│   ├── EnhanceMergeBundle.kt             # 文案 bundle（按 IDE 当前语言选英文基包 / _zh_CN，零反射）
│   ├── EnhanceMergeStartup.kt            # 启动期 i18n 日志（verify 就绪信号）
│   ├── flow/MergeFlow.kt                # 流程编排：分支弹窗（SelectBranchPopup）→ URL 换算 → 模态修订选择
│   ├── log/LogPageLoader.kt             # 修订日志分页加载（游标单通道 + 世代号防串 + 翻页边界剔除）
│   ├── log/LogFilter.kt                 # 过滤条件模型（关键词 + 全字段 + 空格 AND + 大小写不敏感）
│   ├── merge/MergeExecutor.kt           # 合并执行（LocalChangesPromptTask + SvnIntegrateChangesTask）
│   ├── settings/EnhanceMergeSettings.kt  # 设置状态（applicationService，持久化 svnEnhanceMerge.xml）
│   ├── settings/EnhanceMergeConfigurable.kt # 设置页 UI（Version Control 分组）
│   ├── tab/EnhanceMergeTabContent.kt    # 标签页 ChangesViewContentProvider + 可见性 + 标签名
│   ├── tab/EnhanceMergePanel.kt         # 标签页主体（工作副本列表 + Merge From… 链接）
│   ├── tab/WcInfoPanel.kt               # 工作副本信息块（复刻原版 WorkingCopyInfoPanel 的 HTML 渲染）
│   ├── ui/RevisionSelectPanel.kt        # 修订选择面板（原版布局 / CommittedChangeListRenderer / 分页 / 合并状态）
│   ├── ui/RevisionSelectDialog.kt       # 修订选择模态对话框（DialogWrapper 薄包装，委托 RevisionSelectPanel）
│   ├── ui/MergeVariantDialog.kt         # 合并方式选择（对齐原版 Select Merge Variant）
│   ├── ui/RevisionSearchField.kt        # 工具栏上方显式搜索框（防抖 / ✕ / Esc / 计数标签）
│   ├── ui/RevisionSelection.kt          # 勾选语义纯逻辑（以修订号为准 / 不受过滤影响）
│   └── ui/RevisionAccumulator.kt        # 已加载全集累积与去重语义（跨页去重 / 批内重复全保留）
├── src/main/resources/
│   ├── META-INF/plugin.xml              # 插件描述（vendor / category / license / support / change-notes / depends / EP 注册）
│   ├── messages/EnhanceMergeBundle*.properties  # 界面文案（en / zh_CN）
│   ├── pluginIcon.svg / pluginIcon_dark.svg    # 插件图标 40×40（浅色 / 深色主题）
│   └── THIRD-PARTY-LICENSES.txt          # 第三方许可声明（随插件包分发）
└── src/test/kotlin/com/weixf/svn/mergeenhance/
    ├── LogFilterTest.kt                 # 过滤语义 8 用例（空词 / 全字段 / AND / 大小写不敏感）
    ├── LogPageLoaderTest.kt             # 游标单通道分页 17 用例（世代号 / 补批 / MAX_BATCHES / 翻页边界 / 取消不推进游标）
    ├── QuantitySelectionTest.kt         # 稀疏勾选 9 用例
    ├── RevisionSelectionTest.kt         # 勾选集合语义 7 用例（以修订号为准 / 不受过滤影响）
    ├── RevisionAccumulatorTest.kt       # 已加载全集累积 4 用例（跨页去重 / 批内重复全保留）
    ├── EnhanceMergeBundleTest.kt        # 文案 bundle 3 用例（基包 / _zh_CN / 缺键回退）
    ├── BundleKeysTest.kt                # 文案 key 对齐 6 用例（中英一致 / 非空 / 各功能 key / 「+N」文案占位符）
    ├── PluginDescriptorTest.kt          # 插件描述文件 3 用例（Configurable 必须有 displayName/key / 无 2022.1 不认的元素 / since-build 与 depends）
    └── settings/EnhanceMergeSettingsTest.kt # 设置持久化 4 用例（pageSize 夹取 / 旧配置兼容 / 开关往返）
```

---

## 开发与构建

### 1. 配置构建环境

- 修改 `gradle.properties` 中 `org.gradle.java.home` 为本机 JDK 11 路径（Windows 路径用 `/` 或转义 `\`）：

  ```properties
  org.gradle.java.home=D:/softwares/Java/jdk-11.0.32.1
  ```

- 首次构建会从 JetBrains 缓存镜像下载 IDEA 2022.1 SDK（约 600MB）与 JBR，耗时取决于网络。

> **网络受限环境下构建失败先看这一条**：仓库内的 `gradle.properties` 已关闭 Gradle IntelliJ Plugin 的
> 「自更新检查」（`org.jetbrains.intellij.buildFeature.selfUpdateCheck=false`）。该检查仅为提示 IPGP
> 是否有新版本，但它会去 GitHub 拉重定向头（且关闭了自动跟随重定向），在网络受限 / GitHub 限流 /
> 代理拦截重定向时，`Location` 头为空 → `NullPointerException` →
> `Cannot resolve the latest Gradle IntelliJ Plugin version` → **构建在 `initializeIntelliJPlugin`
> 阶段直接失败**（与本项目代码无关）。确认该开关仍为 `false` 即可；
> 等效办法是加 `--offline`（依赖已缓存时可用）。

### 2. 构建插件

```bash
# Windows（PowerShell）
./gradlew.bat buildPlugin

# 产物位置
# build/distributions/SvnMergeEnhance-1.0.0.zip
```

> 若本机 `gradlew` 需要联网下载 wrapper 发行包，也可以直接使用 Gradle 缓存中已有的 gradle 可执行文件构建。

### 3. 沙箱调试

```bash
./gradlew.bat runIde
```

启动内置沙箱 IDEA 实例（含本插件 + 内置 Subversion 插件），可对真实 SVN 仓库做端到端验证。

### 4. 验证插件加载

安装后检查：`Version Control`（版本控制）工具窗里出现 **Enhance Merge（增强合并）** 标签页，
且与 **Subversion Working Copies Information** 并列；标签页内列出本项目的工作副本，
每个工作副本信息块上有 **Cleanup** 与 **Merge From…**（非 SVN 项目不会出现该标签页）。

> 日志判据：沙箱启动后出现 `EnhanceMerge i18n: ...` 一行，即表示插件已加载并完成本地化判定。

---

## 设置项

`Settings/Preferences → Version Control → SVN Enhance Merge`：

| 项 | 默认值 | 说明 |
|---|---|---|
| 每页版本数 | `100` | **首屏**加载的版本条数（10–500），对齐 TortoiseSVN 的 `NumberOfLogs`。「Load +100 / Load +500」按钮固定每次追加对应条数（与内置 Subversion 插件一致，不受本设置影响） |
| 计算已合并状态 | 开 | 为每行显示已合并/外来/计算中图标，并在变更文件列表标记 `[Already merged]`；关闭后不读取 `svn:mergeinfo` |

设置持久化于应用级 `svnEnhanceMerge.xml`，重启 IDE 后仍生效。界面语言跟随 IDE
（未装语言包时为英文，装了中文语言包后自动切换中文）。

---

## 测试

### 纯逻辑单元测试（66 用例，不连仓库）

```bash
./gradlew.bat test
```

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `LogFilterTest` | 8 | 空词不过滤、全字段命中（说明/作者/路径/版本号/日期）、多词 AND、大小写不敏感 |
| `LogPageLoaderTest` | 17 | 游标单通道：推进 / 到底 / 整页保守判定 / 补批 / `MAX_BATCHES` 上限 / `reopenIfExhausted`；**世代号**：stale 世代号不发包、reset 后旧在飞任务作废、**取数期间 reset 时半截数据作废**；**翻页边界**：剔除重复的游标条目且不丢更新的新修订；**取消**：取消后游标不推进（否则静默丢修订） |
| `QuantitySelectionTest` | 9 | 全不选起点的增删清、全选起点的排除与恢复、`areAllSelected()` 的原版语义（起点为全选时恒为 `true`，本插件 `selectedChangeLists()` 依赖该行为走排除集） |
| `RevisionSelectionTest` | 7 | 勾选集合语义：以修订号为准、不受过滤视图影响、稀疏增删 |
| `RevisionAccumulatorTest` | 4 | 已加载全集累积：跨页去重、**批内重复修订号全部保留**（SVN 合并修订按路径展开） |
| `EnhanceMergeBundleTest` | 8 | locale 判定（**零反射**）：英文/无法判定 → 英文基包（`Locale.ROOT`）、中文 → `_zh_CN`、未知语言 → 回退基包不串语种、语言不符即拒绝、locale 纯函数、诊断字段 |
| `BundleKeysTest` | 6 | 文案 key：中英包集合一致、取值非空、搜索框 key、标签页 key、合并方式弹窗 key、**「+N」按钮文案含 `{0}` 占位符** |
| `EnhanceMergeSettingsTest` | 4 | 设置持久化：pageSize 夹取、旧配置（含已删字段）兼容、开关往返 |
| `PluginDescriptorTest` | 3 | 插件描述文件：**`Configurable` 的 EP 必须有 `displayName` 或 `key`**（2024+ 平台缺省会抛 `PluginException`，打开设置对话框即报插件错误）、不含 IDEA 2022.1 不认识的元素（`license`/`support`/`privacy-policy` → 插件加载失败）、`since-build` 与 `<depends>` 齐全 |

> 测试通过 `cursorSource` 注入点喂假数据，不连仓库；
> `SvnChangeList` / `MergeContext` 用 mockk 只 stub 需要的 getter。

### 官方 API 兼容性校验（Plugin Verifier）

```bash
# 校验支持范围的两端（IC-2022.1 / IC-2025.2），会下载对应 IDE
./gradlew.bat runPluginVerifier

# 复用本机已解压的 ideaIC，不下载 IDE（更快）
./gradlew.bat runPluginVerifier -PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1
```

比对插件与目标 IDE 的**二进制 API**，报告缺失类、方法签名变更、internal API 依赖等问题。
svn4idea 不在平台兼容承诺内，改 `intellij.version` 之前应先跑一次。
失败等级为 `COMPATIBILITY_PROBLEMS`：deprecated 等软问题只报告不阻断（否则 svn4idea 自身的
deprecation 会把构建卡死）。

### 插件级回归验证

```bash
# 切到指定 IDE 版本启动沙箱，轮询插件就绪，扫描插件异常日志，输出 PASS/FAIL
./gradlew.bat verifyOnIde -PideaVersion=2025.2
```

脚本自动完成：杀旧沙箱 → 清日志 → 后台 `runIde` → 等 `EnhanceMerge i18n` 日志行（插件已加载）→ 过滤 IDEA 平台自身噪声后扫描 `SEVERE` / `com.svn` 堆栈 → PASS/FAIL（退出码 0/1/2/3，可接 CI）。加 `-PverifyAutoClose=true` 检查后自动关闭沙箱。

### 人工全流程验收

在沙箱中走一遍：分支弹窗 → 版本选择（搜索框过滤 / 状态图标 / 复选框点击 / `Ctrl+D` diff）→ `Merge Selected`（本地改动提示 → 进度 → 冲突解决 → 更新信息树），完成一次真实合并。

---

## 已知限制与后续计划

| 项 | 现状 | 计划 |
|---|---|---|
| 分支枚举 | 复用原版 `SelectBranchPopup`（trunk / branches / Configure Branches…） | — |
| 大仓库日志量 | 分页实时加载（首屏条数可配，10–500） | 可加增量游标缓存 |
| 合并执行 | 完全走原版 `SvnIntegrateChangesTask`（含冲突解决与更新信息树） | — |
| Unix 回归脚本 | `scripts/verify-plugin.sh` 已写好但未在 Linux/macOS 实跑 | 首次使用前人工执行一次确认 |
| Kotlin stdlib 告警 | **已解决**：`gradle.properties` 设 `kotlin.stdlib.default.dependency=false`，stdlib 与 `annotations.jar` 不再打进 zip（由 IDEA 核心 `lib/3rd-party-rt.jar` 提供），安装包 2.10MB → 434KB | — |
| IDEA 2026.x | JetBrains 已把 SVN 插件 unbundle，需用户自行从 Marketplace 安装官方 **Subversion** 插件 | 已在插件描述与本 README 标注；`until-build=262.*` 需随新版本滚动更新 |

---

## 兼容性说明

### 已声明的支持范围

| 项 | 值 | 说明 |
|---|---|---|
| `since-build` | `221` | IDEA 2022.1 |
| `until-build` | `262.*` | IDEA 2026.2.3；已通过 Plugin Verifier 对 IC-2022.1 / IC-2026.2.3 两端的二进制 API 校验（Compatible），2026.3+ 待验证后放开 |

### 兼容性依据（已逐项核对）

1. **内置 Subversion 插件（svn4idea）**：本插件直接调用的 14 个 API（`MergeContext`、`SvnIntegrateChangesTask`、
   `ChangeListsMergerFactory`、`LocalChangesPromptTask`、`QuickMerge`、`QuickMergeInteractionImpl`、
   `OneShotMergeInfoHelper`、`MergeChecker`、`SvnCommittedChangesProvider.getCommittedChanges`、
   `ChangeBrowserSettings`（游标字段）、`SvnRepositoryLocation`、`SvnChangeList`、
   `SelectBranchPopup`、`SvnBranchConfigurationNew`、`QuantitySelection`、`SvnRepositoryLocation`）的签名，
   已与最新版 svn4idea 源码逐一比对，**与 2022.1 完全一致**。
   其中 `SvnBranchConfigurationNew` 已由 Java 改写为 Kotlin，`getRelativeUrl` 返回值改为可空——
   调用处已做 null 防护。
2. **IDEA 平台 API**：`com.intellij.*` 在 since/until 范围内由平台兼容性承诺覆盖。
   第五轮起不再使用 `com.intellij.ui.TableViewSpeedSearch`（隐式 speed search 已移除，
   搜索改由工具栏上方显式搜索框承担）；行渲染直接用 `CommittedChangeListRenderer`（2022.1 起签名稳定）。
3. **Kotlin 标准库**：按官方要求不随插件分发（`kotlin.stdlib.default.dependency=false`），
   运行时由 IDEA 核心 `lib/3rd-party-rt.jar` 提供。已实测 2022.1 沙箱加载正常
   （`gradlew verifyOnIde -PideaVersion=2022.1` → PASS）。
4. **2026.x 的两处平台变化（已适配）**：
   - **本地化改为零反射**：此前靠**反射**探测"是否装了语言包"
     （`DynamicBundle.findLanguageBundle()` / `com.intellij.l10n.LocalizationUtil.findLanguageBundle()`）。
     实测（`javap`）发现 **2026.2.3 上这两条路径都不存在** —— `findLanguageBundle()` 已被移除，
     `LocalizationUtil` 里根本没有这个方法 → 旧实现**在 2026.x 上恒返回 null**，
     语言包感知其实已静默失效（恒走英文）。
     现改为**公开 API** `DynamicBundle.getLocale()`：它在 2022.1 与 2026.2.3 都是 `public static`
     且语义一致（返回**语言包语言**，不是操作系统语言）→ 本插件现在**零反射**，
     且 2026.x 上的语言跟随也一并修好。
   - **SVN 插件不再内置**：Plugin Verifier 实测 2026.2.3 对应的是 Marketplace 上的
     `Subversion 262.10968.211`。本插件声明 `<depends>Subversion</depends>`，
     因此在 2026.x 上需先安装官方 Subversion 插件（见「环境要求」）。
     所复用的 svn4idea 公开 API（`SelectBranchPopup` / `SvnCommittedChangesProvider` /
     `SvnIntegrateChangesTask` / `OneShotMergeInfoHelper` 等 14 项）在 2022.1 与 2026.2.3 中
     **签名完全一致**（已逐一 javap 比对，见上一节）。

### 已知风险与回归方法

- **bundled 插件 API 无兼容承诺**：svn4idea 不属于平台 API，JetBrains 可随时调整；
  上述比对只证明「当前源码一致」，不构成对未来的保证。
- **运行时行为差异**：2022.1 之后 IDEA 的 SVN 后端由 SVNKit 切换为命令行 `svn`。
  本插件只走公共 API（不直接引用 SVNKit），但认证、错误信息等行为可能有差异，需实测。
- **Java 目标版本**：当前按 JVM 11 编译；JetBrains 未来版本可能提高最低要求（如 17），升级基线时需同步。
- **⚠️ 无法对 2026.x 做「重新编译 + 沙箱」回归（工具链限制，非插件缺陷）**：
  - IDEA 2026.x 的平台 jar 是 **Kotlin 2.4 元数据**，而本项目按设计锁定 Kotlin 1.7.22 编译器
    （对齐 IDEA 2022.1 内置 Kotlin），对着 2026 SDK 编译会报 `Incompatible classes were found in dependencies`；
  - IPGP 1.x 无法启动 2026.x 沙箱：新版布局把 vmoptions 挪到 `bin/win/idea64.exe.vmoptions`，
    其中存在不含 `=` 的条目（`-Xmx2048m`、`-ea` 等），触发 IPGP `resolveIdeHomeVariable`
    的 `split("=")[1]` 越界（`IndexOutOfBoundsException: Index: 1, Size: 1`）。

  因此 2026.x 的兼容性依据是 **Plugin Verifier**（它会自动按 IDE 版本解析并下载对应的
  Marketplace 版 SVN 插件做二进制比对，结果 Compatible），而**入口链接注入与合并主流程
  需要在真实 2026.2.3 里人工过一遍**。若要恢复 2026.x 的编译期回归能力，需迁移到
  IPGP 2.x + Kotlin 2.x（构建脚本大改，`intellij { }` / `runPluginVerifier` 配置均需重写）。
- 一键回归验证（自动切 SDK 启动沙箱、轮询插件就绪、扫描插件异常日志并给出 PASS/FAIL）：

  ```bash
  # Gradle 任务（跨平台，推荐）
  ./gradlew verifyOnIde -PideaVersion=2026.2.3

  # 或直接调脚本
  powershell -File scripts\verify-plugin.ps1 -Version 2026.2.3   # Windows
  scripts/verify-plugin.sh --version 2026.2.3                    # Linux / macOS
  ```

  默认保持 IDE 运行以便人工走主流程；`-PverifyAutoClose=true` / `--close-when-done` 则在检查结束后关闭。
  首次验证某个版本会先下载该版本 IDE，耐心等待（可用 `-PverifyTimeout=900` 放宽）。
  实测通过后再提高 `until-build`。
- 二进制 API 校验（官方 Plugin Verifier，比对插件与目标 IDE 的类/方法签名）：

  ```bash
  # 自动下载支持范围两端（IC-2022.1 / IU-2026.2.3）。
  # 两个坑：① 2026.x 必须写 IU（IDEA 2026 起 Community/Ultimate 合并，
  #   code=IC 已 404）；② 两端一起下需约 8GB 磁盘，CI runner 会撑爆。
  # 需要只校验一端时用 -PverifierIdeVersions=IC-2022.1（CI 即采此法）
  ./gradlew runPluginVerifier

  # 复用本机已解压的 SDK，不重复下载（逗号分隔多个目录）
  ./gradlew runPluginVerifier "-PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3"
  ```

---

## 第三方许可

本插件**不分发任何第三方组件**（第五轮起移除 LGoodDatePicker 依赖，日期区间功能已删除）。
完整的第三方声明随插件包一起分发：`THIRD-PARTY-LICENSES.txt`（位于插件包根目录）。

以下组件**不分发**，仅在运行时使用 IDE 提供的：

| 组件 | 提供方 | 说明 |
|---|---|---|
| IntelliJ Platform SDK | JetBrains s.r.o. | 编译期依赖 |
| Subversion 插件（svn4idea） | JetBrains s.r.o. | 只调用其公开 API，不修改、不分发、不注入 |
| Kotlin 标准库 | JetBrains s.r.o.（IDE 核心 `lib/3rd-party-rt.jar`） | 由 IDE 提供（`kotlin.stdlib.default.dependency=false`） |

> 商标声明：「IntelliJ IDEA」「JetBrains」「Subversion」「TortoiseSVN」为各自权利人的商标，
> 此处仅用于说明兼容性与所复用 API 的来源；本插件与 JetBrains s.r.o. 及 TortoiseSVN 项目无隶属或背书关系。

---

## 隐私

本插件**不收集、不传输、不存储任何个人数据**：不含遥测、统计或任何第三方网络请求，
仅通过 IDE 自带的 Subversion 集成与你的 SVN 仓库通信。

唯一持久化的内容是 `Settings | Version Control | SVN Enhance Merge` 里的两个选项
（每页版本数、是否计算合并状态），保存在 IDE 的应用级配置文件 `svnEnhanceMerge.xml` 中。

---

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。版本号遵循语义化版本，变更见 [CHANGELOG.md](CHANGELOG.md)。

许可全文随插件包一起分发（`LICENSE` 位于插件包根目录）。

Copyright 2026 wxingfu

> 许可证变更记录：1.0.0 首次公开发布前由 MIT 切换为 **Apache-2.0**
> （更明确的专利授权条款，与 JetBrains Marketplace 的分发要求一致）。
> 变更前的内部构建不受影响（未公开发布过 MIT 版本）。

---

## 贡献指南

见 [CONTRIBUTING.md](CONTRIBUTING.md)：构建命令、本机配置注意事项，以及本项目必须遵守的约束
（不 fork svn4idea、不打包 Kotlin stdlib、异步回调必须判 dispose 等）。

---

## 发布流程

### 0. 前置：Marketplace 账号与供应商计划

1. 注册 JetBrains Marketplace 账号（<https://plugins.jetbrains.com>）。
2. 确认当前的**供应商计划（付费）**门槛：Marketplace 对上架插件有付费计划要求，
   开源项目通常可走免费 / 赞助通道 —— **以官网定价页与当期政策为准**（本仓库不写死价格，避免过期写错）。
3. 在后台签署 Vendor / Supplier Agreement。
4. 创建插件条目（填名称、分类、描述）→ 得到数字 `pluginId`（后续上传更新用）。

### 1. 构建与校验

```bash
./gradlew clean test buildPlugin verifyPlugin          # 构建、测试与官方包结构校验
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin main --tags
```

产物：`build/distributions/SvnMergeEnhance-1.0.0.zip`
（GitHub Release 把该 zip 作为附件上传）。

### 2. 上架物料（Marketplace 后台 + `plugin.xml`）

| 物料 | 取值 / 位置 |
|---|---|
| 分类 Category | **VCS**（`plugin.xml` 已写 `<category>VCS</category>`，后台保持一致） |
| 许可证 License | **Apache-2.0** —— 在 Marketplace 后台选 Apache-2.0（SPDX 标识）；许可全文随包分发（包根目录 `LICENSE`）。⚠️ **不要**往 `plugin.xml` 里加 `<license>` 元素：IDEA 2022.1 不识别它（沙箱实测 `Unknown element: license`），会导致插件**加载失败** |
| 支持渠道 Support | GitHub Issues：<https://github.com/wxingfu/SvnMergeEnhance/issues>；邮箱 `wxingfu@alu.uestc.edu.cn`（后台填写；同样**不要**用 `plugin.xml` 的 `<support>` 元素，2022.1 也不识别） |
| 支持 / 源码链接 | <https://github.com/wxingfu/SvnMergeEnhance> |
| 主页 | <https://wxingfu.github.io/> |
| 隐私政策 / 数据收集 | 声明 **No data collected**（本插件不收集任何数据，见「[隐私](#隐私)」章节）；政策链接可指向仓库 README 的隐私章节 |
| 第三方许可 | 随包分发的 `THIRD-PARTY-LICENSES.txt`（零分发组件的正式声明，见「[第三方许可](#第三方许可)」） |
| 截图 / 演示图 | 建议 1–2 张：① 工具窗「增强合并」标签页；② 修订选择对话框（搜索框 + 状态图标 + 「Load +100」） |
| 标签 Tags | `svn`、`subversion`、`merge`、`vcs`、`tortoise-svn` |

### 3. 上传

需要 Marketplace 的 API token（在 [Marketplace 后台](https://plugins.jetbrains.com/pluginManager/my-plugins) → My Tokens 创建 `permanentToken`）。
**不要把 token 写进仓库**，放进用户级文件：

```properties
# %USERPROFILE%/.gradle/gradle.properties
pluginRepositoryUrl=https://plugins.jetbrains.com
marketplaceUsername=<username>
marketplaceToken=<token>
```

```bash
./gradlew publishPlugin          # 上传到 Marketplace
./gradlew verifyPluginSignature  # 验证签名
```

其它两种上传方式（等价，择一）：

```bash
# CLI 工具
upload-plugin --id com.weixf.svn.mergeenhance --channels Default --token $TOKEN \
  build/distributions/SvnMergeEnhance-1.0.0.zip

# HTTP API（xmlId 必须与 plugin.xml 的 <id> 完全一致）
curl -i -H "Authorization: Bearer $TOKEN" \
  -F "xmlId=com.weixf.svn.mergeenhance" \
  -F "file=@build/distributions/SvnMergeEnhance-1.0.0.zip" \
  -F "channel=" \
  https://plugins.jetbrains.com/api/updates/upload
```

### 4. 上传之后

自动包结构 / 配置校验 → Marketplace 审核（期间可加 `isHidden=true` 先内部验证）→
批准后进入 Stable 频道 → **用 JetBrains 账号实测安装一次**（升级路径、插件 id 变更提示）。

### 5. 发布前自检清单

- [x] 元数据齐全：`pluginIcon.svg` / `pluginIcon_dark.svg`（已在插件目录根部）、`<vendor>` 的 email 与 url、`<change-notes>`（已按当前入口重写）
- [x] 分类 / 许可 / 支持 / 隐私 / 第三方许可：见上方「上架物料」表
- [x] 许可说明：Apache-2.0，无第三方组件再分发（LGoodDatePicker 依赖已移除），`THIRD-PARTY-LICENSES.txt` 随包分发
- [x] 兼容性：`runPluginVerifier` 对 `IC-2022.1` 与 `IC-2026.2.3` 结论均为 Compatible
- [x] 人工走一遍真实合并（分支弹窗 → 合并方式 → 版本选择 → 勾选 → 合并）
- [ ] 补 Marketplace 截图（标签页 + 修订选择对话框）
