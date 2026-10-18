# SVN Enhance Merge — IDEA SVN 合并增强插件

[![CI](https://github.com/wxingfu/SvnMergeEnhance/actions/workflows/ci.yml/badge.svg)](https://github.com/wxingfu/SvnMergeEnhance/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/badge/Marketplace-SVN%20Enhance%20Merge-orange)](https://plugins.jetbrains.com/)

一个 **独立的 IntelliJ IDEA 增强插件**：在 IDEA 内置 Subversion 插件之上，提供 TortoiseSVN「Show Log / merge revisions」式的**逐版本勾选合并**——实时过滤的修订列表、每行的合并状态、右侧变更文件一览，确认后走**与原版「Merge From」完全一致**的合并链路。

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
- [许可证](#许可证)
- [贡献指南](#贡献指南)

---

## 功能特性

### 入口：Working Copies Information 面板链接

- 挂在 IDEA 内置 SVN 插件的 **Subversion Working Copies Information** 标签页，紧跟「Merge From…」下方，与它并列；
- 仅在项目启用 Subversion VCS 时可用；不注册任何菜单/右键项（避免与 IDE 自身菜单结构冲突）。

### 完整合并流程

```
Working Copies Information 面板 → Enhance Merge… 链接
  └─ ① SelectBranchPopup          复用原版分支选择弹窗（trunk / branches / Configure Branches…）
       └─ ② MergeVariantDialog     合并方式（Revision Select / Cancel）
            └─ ③ RevisionSelectDialog   修订列表（实时过滤 + 分页 + 合并状态）   ← 本插件核心
                 └─ ④ MergeExecutor     复用原版 SvnIntegrateChangesTask        ← 与原版一致
                      1) LocalChangesPromptTask   本地改动与合并路径相交时的原版提示
                      2) SvnIntegrateChangesTask  进度条 / 冲突解决 / 更新信息树
```

### 修订选择界面（核心增强）

| 能力 | 说明 |
|---|---|
| 实时过滤 | TortoiseSVN 语法：空格分词，`-词` 排除、`+词` 或匹配、`"短语"`、`!` 取反；可勾选 Message / Path / Author / Revision / Bug-ID / Date 字段，支持正则与大小写敏感 |
| 日期区间 | `From` / `To` 日历（点击即弹日历，**周一在首列、周日在末列**），服务端按日期裁剪 |
| 版本区间 | 搜索框输入 `10-20` / `10-` / `-20` 按修订号范围过滤 |
| 分页加载 | 首屏 + 「+100 / +500 / 全部显示」实时向仓库翻页，不用本地缓存 |
| 合并状态 | 每行显示 已合并 ✔ / 外来修订（无图标）/ 计算中 图标；右栏对**已合并**文件追加 `[Already merged]` |
| 变更文件 | 右侧 `CommittedChangesBrowser` 展示所选修订的变更路径，支持 `Ctrl+D` 查看差异 |
| 快速搜索 | 列表内直接输入文字，按提交说明定位行 |
| 勾选 | 复选框在行首最左，鼠标点击或 `Space` 键切换；全选 / 全不选；稀疏语义（全选后排除个别）跨翻页保留 |
| 实时汇总 | 底部状态栏显示版本数、修订区间、已选数、涉及路径数 |

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

1. **发起**：打开 `Version Control → Subversion Working Copies Information` 标签页，点击 **Merge From…** 下方的 **Enhance Merge…** 链接。

2. **① 选择源分支**：弹出的是**原版分支选择弹窗**（trunk / branches / Configure Branches… 俱全），选中 `branches/feature-x`。

3. **② 确认合并方式**：本插件仅提供 **Revision Select**（由你在下一步逐个勾选修订）→ 确认。

4. **③ 等待日志加载**：后台任务直连仓库拉取该分支修订（可取消，首屏条数见[设置项](#设置项)）。

5. **④ 选择修订**：
   - 勾选需要合并的版本（鼠标点行首复选框，或选中行后按 `Space`）；
   - 顶部过滤栏缩小范围：`By ▾` 选字段、搜索框输入关键词（支持 `-排除`、`+或`、`"短语"`、`!` 取反）、`From/To` 限定日期（点击日期框即弹出日历，周一在首列、周日在末列）；
   - 「+100 / +500 / 全部显示」继续向后翻页；`Refresh` 按当前条件重查；
   - 列表内直接输入文字可快速定位到匹配提交；选中行后 `Ctrl+D` 查看该修订差异；
   - 底部状态栏实时确认：「显示 N 个版本（rA–rB）— 已选 M 个，包含 K 个路径」。

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
| 修订日志 | `MergeCalculatorTask.loadChangeLists(ctx, cursor, size)`（游标分页）+ `SvnCommittedChangesProvider.getCommittedChanges(...)`（日期区间） |
| 列表渲染 | `TableView` + `ListTableModel` + `CommittedChangesBrowser` + `CommittedChangesTreeBrowser.collectChanges` |
| 勾选语义 | `QuantitySelection<Long>`（稀疏表示：全选后排除个别） |
| 合并状态 | `OneShotMergeInfoHelper(context)`（`MergeChecker`）+ `ListMergeStatus`（图标）/ `ChangeNodeDecorator`（`[Already merged]`） |
| 合并执行 | `SvnIntegrateChangesTask` + `ChangeListsMergerFactory(lists, false, false, true)` + `LocalChangesPromptTask` |

编译期通过 `intellij { plugins.set(listOf("Subversion")) }` 把捆绑的 Subversion 插件类暴露到 classpath；运行期由 `<depends>` 声明依赖。

### 关键实现点

1. **两种日志加载模式**（`LogPageLoader`，均带可取消的后台任务）

   | 模式 | 触发 | 数据来源 | 分页 |
   |---|---|---|---|
   | A 日期模式 | 填了 From/To | `SvnCommittedChangesProvider`（服务端按日期裁剪） | 内存分页 |
   | B 游标模式 | 日期均空 | `MergeCalculatorTask.loadChangeLists` | 服务端分页 + 客户端过滤不足时循环补页（≤10 批） |

   版本区间搜索词 `10-20` 会转成游标上界 `revTo + 1`（原版排除等于 `beforeRevision` 的修订），下界 `revFrom` 命中即早停。

2. **合并状态**（T8）后台线程批量调 `MergeChecker.checkList()`，映射 `MERGED→MERGED` / `NOT_EXISTS→ALIEN` / 其余→`REFRESHING`，每 10 条批量重绘一次；图标渲染在**复选框之后**（命中区 `[cell.x, cell.x+checkboxWidth]` 与视觉对齐）。右栏 `[Already merged]` 取 `getNotMergedPaths` 的补集。

3. **勾选与过滤解耦**：勾选状态按修订号存于 `QuantitySelection`，过滤/翻页只改展示列表，不丢勾选。

4. **合并执行**（`MergeExecutor`）与 `QuickMerge.merge(List)` 等价：`saveAllDocuments()` → `LocalChangesPromptTask`（QuickMerge 仅作宿主，不触发其合并流程）→ `SvnIntegrateChangesTask(...).queue()`。**不写 `SvnConfiguration`**，不影响原版「Merge From」行为。

5. **本地化**：自绘文案全部走 `messages/EnhanceMergeBundle`，按平台是否装载语言包切换英文基包 / `_zh_CN`；设置页标题通过 EP 的 `key` 属性引用同一 bundle。

### 与原插件的关系

- **复用**：上表全部公开 API（svn4idea 不属于平台 API，无兼容承诺；升级 IDE 前须按 `.codebuddy/rules` 的「svn4idea API 兼容性基线」逐项核对签名）；
- **不复用**：`SvnBranchConfigurationManager`（internal）、`QuickMerge` 的合并流程、`ToBeMergedDialog`（不可改造）；
- **不引入**：SVNKit 直接依赖（统一经由内置插件 API，认证与连接行为与原生一致）；
- **参考源码**：svn4idea 最新版源码位于仓库外的 `G:\gitDownload\svn4idea`（本机离线参考，**不参与构建、不提交**），用于核对公开 API 签名与跨版本兼容性。

---

## 项目结构

```
SubversionEnhance/
├── build.gradle.kts                     # 构建脚本（IPGP 1.17.3 / Kotlin 1.7.22 / JDK 11 / mockk 测试）
├── settings.gradle.kts                  # rootProject.name = SvnMergeEnhance
├── gradle.properties                    # org.gradle.java.home 指向本机 JDK 11
├── scripts/
│   ├── verify-plugin.ps1                # Windows 回归验证（切版本启沙箱 + 扫插件异常日志）
│   └── verify-plugin.sh                 # Unix 版（同一逻辑）
├── src/main/kotlin/com/svn/revisionselect/
│   ├── EnhanceMergeAction.kt             # 流程入口（普通类，非 AnAction；由注入链接直接调用）
│   ├── EnhanceMergeBundle.kt             # 语言包感知的文案 bundle（英文基包 / _zh_CN）
│   ├── flow/MergeFlow.kt                # 全流程编排：分支弹窗 → 方式确认 → 版本选择 → 合并
│   ├── log/LogPageLoader.kt             # 修订日志分页加载（日期模式 / 游标模式）
│   ├── log/LogFilter.kt                 # 过滤条件模型 + TortoiseSVN 搜索语法
│   ├── merge/MergeExecutor.kt           # 合并执行（LocalChangesPromptTask + SvnIntegrateChangesTask）
│   ├── settings/EnhanceMergeSettings.kt  # 设置状态（applicationService，持久化 svnEnhanceMerge.xml）
│   ├── settings/EnhanceMergeConfigurable.kt # 设置页 UI（Version Control 分组）
│   ├── ui/RevisionSelectDialog.kt       # 修订选择对话框（renderer / 分页 / 合并状态 / 快速搜索）
│   ├── ui/LogFilterPanel.kt             # 顶部过滤栏（By ▾ / 搜索框 / 起止日历）
│   ├── ui/MergeVariantDialog.kt         # 合并方式确认（Revision Select / Cancel）
│   └── wcinfo/WcInfoLinkInjector.kt     # 向 Working Copies Information 面板注入 Enhance Merge 链接
├── src/main/resources/
│   ├── META-INF/plugin.xml              # 插件描述（vendor / change-notes / depends / EP 注册）
│   ├── messages/EnhanceMergeBundle*.properties  # 界面文案（en / zh_CN）
│   ├── pluginIcon.svg / pluginIcon_dark.svg    # 插件图标 40×40（浅色 / 深色主题）
│   └── THIRD-PARTY-LICENSES.txt        # 第三方许可全文（LGoodDatePicker，MIT）
└── src/test/kotlin/com/svn/revisionselect/
    ├── LogFilterTest.kt                 # 过滤语义 25 用例
    ├── LogPageLoaderTest.kt             # 双模式分页 12 用例
    └── QuantitySelectionTest.kt         # 稀疏勾选 9 用例
```

---

## 开发与构建

### 1. 配置构建环境

- 修改 `gradle.properties` 中 `org.gradle.java.home` 为本机 JDK 11 路径（Windows 路径用 `/` 或转义 `\`）：

  ```properties
  org.gradle.java.home=D:/softwares/Java/jdk-11.0.32.1
  ```

- 首次构建会从 JetBrains 缓存镜像下载 IDEA 2022.1 SDK（约 600MB）与 JBR，耗时取决于网络。

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

安装后检查：`Version Control → Subversion Working Copies Information` 标签页中，每个工作副本的
「Merge From…」下方出现 **Enhance Merge…** 链接（非 SVN 项目不会出现该面板，因此也不存在入口）。

> 日志判据：沙箱启动后出现 `EnhanceMerge i18n: ...` 一行，即表示插件已加载并完成本地化判定。

---

## 设置项

`Settings/Preferences → Version Control → SVN Enhance Merge`：

| 项 | 默认值 | 说明 |
|---|---|---|
| 每页版本数 | `100` | 首屏与每次「加载更多」的条数（10–500），对齐 TortoiseSVN 的 `NumberOfLogs` |
| 计算已合并状态 | 开 | 为每行显示已合并/外来/计算中图标，并在变更文件列表标记 `[Already merged]`；关闭后不读取 `svn:mergeinfo` |
| Bug-ID 正则 | `[A-Z][A-Z0-9]+-\d+` | 勾选 Bug-IDs 过滤字段时从提交说明提取 Bug-ID；填错时自动回退默认值 |
| 默认日期范围（天） | `0` | >0 时过滤栏默认填「最近 N 天」并立即生效；0 表示不限制 |

此外，过滤栏的**勾选字段、日期区间开关、正则/大小写开关**会自动沿用上次使用
（同样存在 `svnEnhanceMerge.xml` 里；它们属于顺手记住的交互偏好，故不在设置页做 UI）。

设置持久化于应用级 `svnEnhanceMerge.xml`，重启 IDE 后仍生效。界面语言跟随 IDE
（未装语言包时为英文，装了中文语言包后自动切换中文）。

---

## 测试

### 纯逻辑单元测试（46 用例，不连仓库）

```bash
./gradlew.bat test
```

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `LogFilterTest` | 25 | 空条件、单/多词 AND、`-` 排除、`+` 或组、引号短语、`!` 取反、大小写敏感、正则模式（含非法正则）、Bug-ID 字段（默认/自定义正则）、日期区间含边界、版本区间各形态、含 `-` 文本不被误判为区间、高亮区间、字段选择 |
| `LogPageLoaderTest` | 12 | 日期模式一次拉取 + 内存分页 + 客户端过滤；游标模式推进 / 到底 / 整页保守判定 / `revTo+1` 边界 / `revFrom` 早停 / 补批 / `MAX_BATCHES` 上限 / `reopenIfExhausted` |
| `QuantitySelectionTest` | 9 | 全不选起点的增删清、全选起点的排除与恢复、`areAllSelected()` 的原版语义（起点为全选时恒为 `true`，本插件 `selectedChangeLists()` 依赖该行为走排除集） |

> 测试通过 `cursorSource` / `dateRangeSource` 注入点喂假数据，不连仓库；
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

在沙箱中走一遍：分支弹窗 → 版本选择（状态图标 / 复选框点击 / 快速搜索 / `Ctrl+D` diff）→ `Merge Selected`（本地改动提示 → 进度 → 冲突解决 → 更新信息树），完成一次真实合并。

---

## 已知限制与后续计划

| 项 | 现状 | 计划 |
|---|---|---|
| 分支枚举 | 复用原版 `SelectBranchPopup`（trunk / branches / Configure Branches…） | — |
| 大仓库日志量 | 分页实时加载（首屏条数可配，10–500） | 可加增量游标缓存 |
| 合并执行 | 完全走原版 `SvnIntegrateChangesTask`（含冲突解决与更新信息树） | — |
| Unix 回归脚本 | `scripts/verify-plugin.sh` 已写好但未在 Linux/macOS 实跑 | 首次使用前人工执行一次确认 |
| Kotlin stdlib 告警 | **已解决**：`gradle.properties` 设 `kotlin.stdlib.default.dependency=false`，stdlib 与 `annotations.jar` 不再打进 zip（由 IDEA 核心 `lib/3rd-party-rt.jar` 提供），安装包 2.10MB → 434KB | — |

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
   `OneShotMergeInfoHelper`、`MergeChecker`、`MergeCalculatorTask.loadChangeLists`、`SvnChangeList`、
   `SelectBranchPopup`、`SvnBranchConfigurationNew`、`QuantitySelection`、`SvnRepositoryLocation`）的签名，
   已与最新版 svn4idea 源码逐一比对，**与 2022.1 完全一致**。
   其中 `SvnBranchConfigurationNew` 已由 Java 改写为 Kotlin，`getRelativeUrl` 返回值改为可空——
   调用处已做 null 防护。
2. **IDEA 平台 API**：`com.intellij.*` 在 since/until 范围内由平台兼容性承诺覆盖。
   唯一确认变化的是 `com.intellij.ui.TableViewSpeedSearch`（新版平台增加了双参构造重载），
   本插件使用 2022.1 的单参构造，并已加降级防御——不可用时仅关闭快速搜索，不影响其他功能。
3. **Kotlin 标准库**：按官方要求不随插件分发（`kotlin.stdlib.default.dependency=false`），
   运行时由 IDEA 核心 `lib/3rd-party-rt.jar` 提供。已实测 2022.1 沙箱加载正常
   （`gradlew verifyOnIde -PideaVersion=2022.1` → PASS）。
4. **2026.x 的两处平台变化（已适配）**：
   - `DynamicBundle.findLanguageBundle()` 被移除（搬到 `com.intellij.l10n.LocalizationUtil`）。
     未适配前 Plugin Verifier 报 `NoSuchMethodError`，现改为「新平台优先」的反射桥接，
     两个版本都能正确判断是否装了语言包（`EnhanceMergeBundle`）。
   - **SVN 插件不再内置**：Plugin Verifier 实测 2026.2.3 对应的是 Marketplace 上的
     `Subversion 262.10968.211`。入口链接所反射的 `org.jetbrains.idea.svn.dialogs.WorkingCopyInfoPanel.getInfo()`
     在 2022.1 与 2026.2.3 的 svn4idea 中**签名完全一致**，注入机制无需改动。

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
  # 自动下载支持范围两端（IC-2022.1 / IC-2026.2.3）
  ./gradlew runPluginVerifier

  # 复用本机已解压的 SDK，不重复下载（逗号分隔多个目录）
  ./gradlew runPluginVerifier "-PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3"
  ```

---

## 第三方许可

本插件在安装包中**重新分发**以下第三方组件：

| 组件 | 版本 | 用途 | 许可 |
|---|---|---|---|
| [LGoodDatePicker](https://github.com/LGoodDatePicker/LGoodDatePicker) | 11.2.1 | 过滤栏的 From / To 日期选择控件 | MIT |

许可全文见安装包内的 `THIRD-PARTY-LICENSES.txt`（上游 jar 本身未内置 LICENSE，故由本插件补齐），
插件描述页（Marketplace）亦已注明。

以下组件**不分发**，仅在运行时使用 IDE 提供的：

| 组件 | 说明 |
|---|---|
| IntelliJ Platform SDK | 编译期依赖 |
| 内置 Subversion 插件（svn4idea） | 只调用其公开 API，不修改、不再分发 |
| Kotlin 标准库 | 由 IDE 核心 `lib/3rd-party-rt.jar` 提供（`kotlin.stdlib.default.dependency=false`） |

---

## 许可证

本项目采用 [MIT License](LICENSE)。版本号遵循语义化版本，变更见 [CHANGELOG.md](CHANGELOG.md)。

Copyright (c) 2026 wxingfu

> 注：插件在安装包中再分发 LGoodDatePicker（MIT），其许可全文见 `THIRD-PARTY-LICENSES.txt`
> （见[第三方许可](#第三方许可)）。

---

## 贡献指南

见 [CONTRIBUTING.md](CONTRIBUTING.md)：构建命令、本机配置注意事项，以及本项目必须遵守的约束
（不 fork svn4idea、不打包 Kotlin stdlib、异步回调必须判 dispose 等）。

---

## 发布流程

### 1. GitHub Release

```bash
./gradlew clean test buildPlugin verifyPlugin          # 构建与校验
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin main --tags
```

然后在 GitHub 上创建 Release，把 `build/distributions/SvnMergeEnhance-1.0.0.zip` 作为附件上传。

### 2. JetBrains Marketplace

需要 Marketplace 的 API token（在 [JetBrains Marketplace 后台](https://plugins.jetbrains.com/pluginManager/my-plugins) 生成）。
**不要把 token 写进仓库**，放进用户级文件：

```properties
# %USERPROFILE%/.gradle/gradle.properties
pluginRepositoryUrl=https://plugins.jetbrains.com
marketplaceUsername=<username>
marketplaceToken=<token>
```

然后发布：

```bash
./gradlew publishPlugin          # 上传到 Marketplace
./gradlew verifyPluginSignature  # 验证签名
```

发布前请确认：

- [x] 元数据齐全：`pluginIcon.svg` / `pluginIcon_dark.svg`（已在插件目录根部）、`<vendor>` 的 email 与 url、`<change-notes>`
- [x] 许可说明：`THIRD-PARTY-LICENSES.txt` 随包分发，描述页已注明 LGoodDatePicker(MIT)
- [x] 兼容性：`runPluginVerifier` 对 `IC-2022.1` 结论为 Compatible
- [ ] 人工走一遍真实合并（分支弹窗 → 版本选择 → 勾选 → 合并）
