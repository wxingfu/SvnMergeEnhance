# Checklist — Enhance Merge v2

> 验收清单。所有条目需由 `gradlew buildPlugin` / `gradlew test` / `gradlew runIde`（真实 SVN 项目）确认。
>
> **本插件 v1 从未在真实 IDE 中运行过**（沙箱日志仅 `buildSearchableOptions`），
> 因此 E0「验证闭环」是本轮的最高优先级，未通过前其余条目不具备可信度。

## R7' 第四轮验收：搜索只搜「已加载」+ 删除版本区间（2026-10-20 · **已实施**）

> 用户决策：① 搜索不再自动加载「不在当前页」的 Log（不是不加载——只过滤已加载集合，
> 命中不足时提示用户手动加载更多）；② 版本区间功能不需要，整体删除。
> 方案与逐文件清单见 `.codebuddy/rules/svn-enhance-merge.mdc` 第四轮节 / `plan.md` `5.4.4 / `tasks.md` T6'。

- [x] 过滤栏**没有**「版本 从/到」；工具栏**没有**「版本区间…」动作；`RevRangeDialog` 文件已删除
- [x] 搜索框输入任意文本（含 `2026-10`、`10-20`）**不触发任何服务端请求**（svn 请求数仅首屏那一次）
- [x] 搜索只过滤**已加载集合**；点「+100 / +500 / 全部显示」加载更多后，搜索结果自动重算
- [x] 搜索命中不足时状态栏提示「已加载 N 条中匹配 M 条，可点 +100 继续加载」
- [x] 大仓库（≥5 万条）连续输入 10 个字符：界面**不冻结**（过滤在后台线程，EDT 不阻塞）
- [x] 连续输入时**旧的搜索/加载任务被取消**（不再出现多个并发扫描）
- [x] 日期区间仍为纯客户端过滤：默认开启、值 = 已加载数据范围、可选范围锁在数据内；改日期不重查服务器
- [x] `Refresh` 清空并从 HEAD 重查第一页
- [x] 已加载总量触顶时状态栏明确提示「已达上限」（不让用户误以为仓库里就这么点）
- [x] `gradlew test` / `buildPlugin`（字节码 major 55）/ `verifyOnIde -PideaVersion=2022.1` 全通过

## E0 验证闭环（前置）

- [ ] `gradlew runIde` 能启动沙箱并打开一个**真实 SVN 工作副本**项目（不是 `buildSearchableOptions`）
- [ ] `idea.log` 出现 `Loaded custom plugins: SVN Enhance Merge`
- [ ] 本插件注入/加载路径有可定位的 INFO 日志，异常有 WARN 日志

## 工程与依赖

- [ ] 独立 Gradle IntelliJ Plugin（Kotlin）可编译，`gradlew buildPlugin` 产出可安装 zip
- [ ] `build.gradle.kts` 显式 `intellij { plugins.set(listOf("Subversion")) }`，未引入 SVNKit/Maven 外部依赖
- [ ] `plugin.xml` 中不含任何 `<actions>` 注册（菜单/右键入口已按决策删除）
- [ ] 未写入 `SvnConfiguration`（不影响原版 Merge From 行为）
- [ ] 未修改 / 未 fork `plugins/svn4idea` 源码

## R1 面板入口

- [x] 「Working Copies Information」面板中，每个工作副本的 **Merge From… 下方**显示 **Enhance Merge…**
      （日志：`Injected Enhance Merge link into Subversion WC info panel: D:\WorkSpace\ideaProjects\drools_learn`）
- [x] **只有一个链接**，不重复（日志：`Enhance Merge link ensured: panels=1, labels=1`；
      40+ 秒内 20+ 次兜底定时器无新增）
- [ ] 刷新工作副本列表后链接自动重新注入，不消失、不重复
- [ ] 多工作副本项目中，点击第 N 个副本的链接 → 合并目标为该副本
- [ ] 注入失败仅记 WARN 日志，不影响原版面板其它链接（Cleanup / Configure Branches / Merge From）
- [ ] 视觉确认：链接位置、左缩进、与「Merge From…」的间距符合预期
- [ ] 点击链接后 `idea.log` **不出现** `Synchronous execution on EDT`（EDT 上不得触发 svn 调用）

## R2 分支选择（复用原版）

- [ ] 弹窗与原版一致：trunk（带 `(trunk)`）、branches…、分隔线、Configure Branches…
- [ ] 选 trunk 或分支后，源 URL = 该分支下与当前工作副本对应的 URL
- [ ] 相对路径无法解析时给出明确提示并中止（不抛异常）
- [ ] 源与目标同层级时提示「Cannot merge from self」并中止
- [ ] 已删除 v1 的自绘 URL 输入对话框

## R3 合并方式

- [x] 新建 `MergeVariantDialog`，标题 `Select Merge Variant`
- [x] 只有 `Revision Select`（含多行描述）+ `Cancel` 两个按钮，**无标准 OK/Cancel 行**
- [x] 默认焦点在 Revision Select；ESC = 取消
- [x] 删除 v1 的 `MergeMethodSelectionDialog`
- [ ] Revision Select → 进入修订选择；Cancel → 无副作用结束（需人工点击确认）

## R4 日志实时分页

- [ ] 首屏只加载设置项条数（默认 **100**，10–500 可配），不是全量
- [ ] 首屏在 1s 内返回（仓库 r ≤ 3000）
- [ ] 「加载更多」按同页大小追加（游标单通道），游标不重复不遗漏
- [ ] 已填日期时：日期区间**只做客户端过滤**，不切换查询通道、不重复访问仓库
- [ ] ~~日期模式结果 > 5000 条时给出「建议缩小日期范围」提示~~ **已作废**（日期模式已删）
- [ ] 日历弹窗视觉：周一在首列、周日在末列；配色与 IDEA 主题一致；无周数列
- [ ] 到底后「加载更多」禁用
- [ ] 后台任务可取消，加载中有进度
- [ ] 加载失败有错误说明 + 重试
- [ ] 未使用任何本地日志缓存

## R5 列表与右侧路径

- [ ] 列表布局与原版一致（无表头、无网格，单行：勾选框 + 修订号 + 图标 + 日期 + 作者 + 说明）
- [ ] 右侧变更路径浏览器随选中修订变化
- [ ] Splitter 比例 0.7 且可拖动
- [ ] 窗口尺寸可记忆

## R6 勾选语义

- [ ] 单行点击勾选框区域可切换
- [ ] Space 键对选中行切换
- [ ] 全选 / 全不选正确；「全选后排除个别」语义正确
- [ ] 加载更多 / 修改过滤后，已有勾选不丢失

## R7 过滤栏

- [ ] 顶部过滤栏含：路径、作者、Bug-ID、日期(从-到)、信息(包含/排除)
- [ ] ~~版本(从-到)~~ **已作废**（第四轮：用户决策不需要版本区间，`RevRangeDialog` 与相关字段一并删除）
- [ ] 日期条件**只做客户端过滤**，不参与服务端查询
- [ ] 修改条件后 300ms 防抖并**只对已加载集合重过滤**（不重置游标、不重查服务器）
- [ ] 组合条件为 AND；排除关键字为 OR
- [ ] 单次翻页内部循环补页 ≤10 批，不出现"有匹配却为空"
- [ ] 搜索命中不足时**不自动请求服务器**，状态栏提示可点「+100 / +500 / 全部显示」继续加载
- [ ] 过滤在后台线程执行，EDT 不因全量过滤而阻塞（大仓库输入不卡顿）
- [ ] 空结果有提示 + 「重置过滤」入口
- [ ] 过滤不丢勾选

## R8 汇总与回传

- [ ] 状态栏实时显示：显示条数、修订号区间、已选条数、已选影响路径数
- [ ] 已选 0 条时 `Merge Selected` 禁用
- [ ] 确认回传 `List<SvnChangeList>`；取消无副作用

## R9 合并执行

- [ ] 复用 `SvnIntegrateChangesTask` + `ChangeListsMergerFactory`
- [ ] 合并前 `saveAllDocuments()`
- [ ] 不连续修订按连续段逐段合并
- [ ] 冲突进入 IDEA 原生冲突解决流程
- [ ] 完成后 Local Changes 自动刷新
- [ ] 已删除 v1 的 dry-run → 确认 → `MergeClient` 自建链路

## R10 已合并状态图标（默认开启）

- [x] 默认开启状态计算（`OneShotMergeInfoHelper` + `ListMergeStatus`，复用原版 `refreshListStatus` 语义）
- [x] 显示 已合并 ✔ / 外来修订（无图标）/ 计算中占位；图标取自原版 `ListMergeStatus.icon`
- [x] 状态分批（每 10 条）后台刷新，不卡顿；新加载页自动补算
- [x] 状态图标不影响可勾选性（复选框位于行首最左，点击命中区对齐）
- [x] 右栏变更路径对已合并文件追加 `[Already merged]`
- [x] 快速搜索（`TableViewSpeedSearch`，按提交说明匹配行）
- [x] 版本列表上可触发 diff（`DiffAction` 快捷键注册）
- [ ] 验收：图标正确、分批刷新不卡顿；已合并项仍可勾选

## R11 设置项

- [x] `Version Control → SVN Enhance Merge` 可打开（`applicationConfigurable`，`parentId=vcs`）
- [x] 每页条数（默认 100，10–500）修改后生效（首屏 + 加载更多 + 全部显示 + 过滤重查均读该值）
- [x] Bug-ID 正则可编辑（无效正则回退默认值，不致过滤失效）
- [x] 默认日期范围（默认 0）>0 时过滤栏默认填「最近 N 天」并立即生效
- [x] 计算已合并状态可关闭（关闭后不显示图标、不查 mergeinfo、无 `[Already merged]`）
- [x] 设置持久化到 `svnEnhanceMerge.xml`；`gradlew verifyPlugin` 通过
- [ ] 人工验收：设置页可见、改动生效、重启 IDE 后仍生效
- [ ] 默认日期范围（天）可配置

## R12 本地化（语言包感知）

- [x] `messages/EnhanceMergeBundle.properties`（英文基包）+ `_zh_CN.properties` 存在，`<resource-bundle>` 已注册
- [x] 代码中无硬编码的可见字符串（T2 对话框 + T4–T8 列表/过滤/状态 + T9 设置页与链接文字均已入 bundle；
      仅 `EnhanceMergeBundle` 自身的 "bundle is missing" 兜底除外——bundle 损坏时无法本地化）
- [x] **未装语言包 ⇒ 英文**：中文 Windows + 无语言包 IDE，日志 `languagePack=false … bundleLocale=`（基包），
      弹窗标题为 `Select Branch`
- [x] **装中文语言包 ⇒ 自动中文**：日志 `languagePack=true, platformLocale=zh … bundleLocale=zh_CN`
- [x] 规避 JDK 陷阱：未装语言包时用 `Locale.ROOT` 取基包（请求 `Locale.ENGLISH` 会被默认 locale 回退成中文）
- [x] properties 按 UTF-8 解码
- [ ] 装了语言包但无对应译文（如日文）⇒ 回退英文基包（待新增语言时验证）

## 测试

- [x] `LogFilter` 单测 **24 用例**（空条件 / 单多词 AND / `-` 排除 / `+` 或组 / 引号短语 / `!` 取反 /
      大小写 / 正则含非法 / Bug-ID 默认与自定义正则 / 日期区间含边界 /
      含 `-` 文本不误判为区间 / 高亮 / 字段选择）
      —— 第三轮已删「版本区间各形态 / 区间解析工具」相关用例；**第四轮**再删「版本区间为硬边界」用例并新增 `matchesText` 用例
- [x] `QuantitySelection` 语义单测 **9 用例**（全选后排除、恢复、清空、再全选；
      `areAllSelected()` 原版语义固化）
- [x] `LogPageLoader` 游标单通道单测 **15 用例**（推进 / 到底 / 整页保守 / 补批 /
      `MAX_BATCHES` 上限 / 重开 / 世代号 stale 不发包）
      —— 第三轮已删「日期模式内存分页 / `revTo+1` 边界 / `revFrom` 早停」用例
- [x] `EnhanceMergeSettings` 单测 **8 用例**（默认日期范围 / `dateRangeEnabledOnOpen` 开关联动 /
      每页条数 clamp / 存储值越界归一化 / Bug-ID 正则非法回退 null / 过滤字段 id 往返）
      —— 2026-10-19 新增，用于钉住「默认日期范围设置静默失效」这个 bug
- [x] `gradlew test` 全绿（**63 用例 0 失败**；第四轮实施后数字会变，须同步本文件与 README）
- [x] `gradlew clean test buildPlugin verifyPlugin` 全通过（zip **434KB**，见下方「官方规范合规」）
- [x] `gradlew verifyOnIde -PideaVersion=2022.1` → PASS（0 插件异常）

## 收尾

- [x] `README.md` 按 v2 重写（流程图 / 功能特性表 / 类结构 / 设置项 / 测试 / 回归验证 / 兼容性）
- [x] `.codebuddy/rules/svn-enhance-merge.mdc` 入口描述同步（唯一入口 = 面板注入链接，无菜单入口）；
      补充验证命令与 svn4idea API 兼容性基线
- [x] ~~删除 `svn4idea/`~~ → **保留，全程不删**（用户决策 2026-10-18）；
      README「与原插件的关系」说明其为仓库外离线参考源码（`G:\gitDownload\svn4idea`），
      并新建 `.gitignore`（忽略 `build/`、`.gradle/`、`.idea/`、沙箱与临时产物）
- [ ] `gradlew runIde` 按 面板链接 → 分支弹窗 → 合并方式 → 修订选择 → 合并，全程走通一次真实合并
      （沙箱已启动待人工操作）

> 结论状态：v2 已实现完成；2026-10-18 完成官方规范合规整改（见下节），仅剩人工真实合并验收与「入口是否改走官方扩展点」的待决项。

## 官方插件规范合规（2026-10-18 整改后新增）

**分发与元数据（R13）**

- [x] 不打包 Kotlin 标准库：`kotlin.stdlib.default.dependency=false`；zip 内无 `kotlin-stdlib*.jar` / `annotations*.jar`
- [x] `gradlew verifyPluginConfiguration` **零告警**（此前报 stdlib 冲突）
- [x] `pluginIcon.svg` / `pluginIcon_dark.svg`（40×40，浅/深主题）位于**插件目录根部**
- [x] `plugin.xml` 含 `<vendor email url>`、`<change-notes>`、`<description>`（含第三方库声明）
- [x] `THIRD-PARTY-LICENSES.txt` 含 LGoodDatePicker(MIT) 许可全文；README 有「第三方许可」小节
- [x] `runPluginVerifier` 可执行；`-PverifierLocalIde=<已解压 ideaIC>` 可免下载复用
- [x] Plugin Verifier 实跑结论：**Compatible**（IC-221.5080.210）

**生命周期与并发（R14）**

- [x] 三处 `Task.Backgroundable` 的 `onSuccess`/`onThrowable`/`onFinished` 均有 dispose 守卫
- [x] 所有 `invokeLater` 的守卫写在 lambda 内（排队期间对话框可能已关）
- [x] `refreshMergeStatus` 在对话框已关闭时不再发起 mergeinfo 查询
- [x] `mergeCheckerPrepared` 为 `@Volatile` + 互斥锁保护，`prepare()` 只执行一次
- [x] dispose 时移除注入到 SVN 面板的 `LinkLabel`（Timer / messageBus 一并释放）
- [x] `registerCustomShortcutSet` 传对话框 `disposable`
- [x] 空格键 `e.consume()` 在 `keyPressed`

**体验与无障碍（R15）**

- [x] 过滤栏勾选字段 / 日期区间开关 / 正则 / 大小写开关持久化到 `svnEnhanceMerge.xml`
- [x] 纯图标清除按钮设置 `accessibleContext.accessibleName`

**代码整洁**

- [x] 删除死代码 `LogFilter.dateMatchTexts`、两份 properties 的 `filter.by.tooltip`
- [x] `EnhanceMergeAction` 去掉 `AnAction` 继承（未注册到任何菜单）
- [x] 文件名与 public 类名对齐（`WcInfoLinkInjector.kt`），private 类改名 `LinkInstaller`

**有意不做 / 待决**

- [ ] `getDimensionServiceKey` 按项目隔离 —— 带 `Project` 重载仅 2023.2+，与 `since-build=221` 冲突（代码注释已说明）
- [ ] 入口改为官方 `ToolWindowFactory` / 扩展点 —— 待调研结论 + 是否上架 Marketplace 的决策
- [ ] 人工真实合并验收（`gradlew runIde` 走通全流程）
