# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- **复刻原版 Cleanup（2026-10-22）**：工作副本信息块内新增 **Cleanup** 链接（仅 svn 1.7+ 工作副本显示，
  对齐原版 `cleanupRow()`），动作复用原版**公开**的 `CleanupWorker(SvnVcs, List<VirtualFile>).execute()`，
  不自实现 svn 清理逻辑；`Configure Branches` 因依赖 internal API 仍不复刻。
- **第七轮走查修复（2026-10-22）**：
  - 工作副本信息块改为**独立信息块 + GridBagLayout 左上对齐**（此前用 BoxLayout(Y_AXIS)，
    子组件被拉伸到容器高度并在主轴居中，导致整块飘到面板中间）；
  - **分支弹窗锚点**由「Merge From…」链接改为**整个信息块**——原版 CopiesPanel.mergeFrom 把
    infoPanel 传给 SelectBranchPopup，其内部 showInCenterOf(myComponent) 使弹窗显示在信息块中心；
  - **恢复**「Select Merge Variant」合并方式选择步骤（原版 QuickMerge.execute() → selectMergeVariant），
    即分支选择后先弹该对话框再进入版本选择。
- **复刻 Working Copies Information 面板（第七轮，2026-10-22）**：标签页改为**工作副本列表**
  （`getAllWcInfos()` + 每行 path + URL），每行一个**加粗的「Merge From…」链接**（对齐原版 WC Info 面板）；
  点「Merge From…」→ 原版分支弹窗 → **模态 `RevisionSelectDialog`**（复用 `RevisionSelectPanel`）→
  `MergeExecutor`，后续步骤完全是 Enhance Merge（用户拍板：修订选择弹模态对话框、其余 WC Info 动作不复刻）。
- **常驻「Enhance Merge」标签页（第六轮，2026-10-22）**：经官方 `changesViewContentEP` 在 VCS 工具窗
  注册与「Subversion Working Copies Information」并列的标签页；纯公开 API、零反射、零轮询。
  修订选择逻辑抽成可复用面板 `RevisionSelectPanel`。
- **修订选择对话框「原版化 + 显式搜索框」（第五轮，2026-10-21）**：
  - 界面布局、行渲染（平台 `CommittedChangeListRenderer` + 行首复选框 + `ListMergeStatus` 图标）、
    工具栏（全选 / 全不选 / +100 / +500）、右栏变更树、`[Already merged]` 标记
    全部对齐原版 `ToBeMergedDialog`；
  - **唯一新增**：工具栏上方独立一行的关键词搜索框——匹配全字段（提交说明 + 全部变更路径 +
    作者 + 版本号 + 日期文本），多关键词空格分隔取且、大小写不敏感；`Esc`/`✕` 清空；
    同行右侧显示「匹配 M / 已加载 N」；输入 300ms 防抖、后台线程过滤（EDT 只 `setItems`）；
  - 过滤只作用于**已加载集合**（零服务器请求），命中不足由用户显式「+100 / +500」加载更多；
    游标跟踪**已加载全集的最早修订号**，过滤态下翻页不跳段（原版取 model 末行，过滤后会跳段）。
- 每修订的合并状态图标（已合并 / 外来 / 计算中）与右栏 `[Already merged]` 标记，
  复用原版 `MergeChecker` / `ListMergeStatus`，可一键关闭。
- 合并执行完全复用原版链路：`LocalChangesPromptTask`（本地改动提示）→
  `SvnIntegrateChangesTask`（进度 / 冲突解决 / 更新信息树）。
- 设置页：`Settings | Version Control | SVN Enhance Merge`（每页条数、合并状态开关）。
- 界面文案英文 + 简体中文，按 IDE 语言包状态自动切换。

### 变更
- **删除反射注入入口（D1 ②，2026-10-22）**：移除 `WcInfoLinkInjector`（反射 `WorkingCopyInfoPanel.getInfo()`
  + 2s 定时器扫组件树注入链接）及其整条模态链路（`EnhanceMergeAction` / `MergeVariantDialog` /
  `RevisionSelectDialog` / `MergeFlow.start` / `MergeFlow.openRevisionSelect`），入口唯一化为官方标签页；
  `plugin.xml` 摘除 `postStartupActivity`。本插件不再反射注入任何其它插件（满足上架规范）。
- 项目/包名重命名：`SubversionEnhance` → `SvnMergeEnhance`，
  `com.svn.revisionselect` → `com.weixf.svn.mergeenhance`，
  插件名 `SVN Custom Merge` → `SVN Enhance Merge`，界面中文「自定义合并」→「增强合并」。
- **移除 TortoiseSVN 式过滤设施（第五轮，2026-10-21）**——按用户决策「界面保持原版，
  只增加一个显式搜索框」整体删除：
  - 删除 `ui/LogFilterPanel.kt`（By 字段勾选 / 搜索框 / From-To 日历 / 正则 / 大小写）、
    `log/LogFilterField.kt`、命中高亮（`highlightRanges` 等）、自绘 `RevisionCellRenderer`
    （回归平台 `CommittedChangeListRenderer`）、自定义行高、底部自定义状态栏、
    表格隐式 `TableViewSpeedSearch`（与显式搜索框双轨且与空格切换勾选存在按键消费竞争）、
    工具栏「Show All / Refresh」；
  - 删除**日期区间**全链路（开关 / 日历 / `LogFilter` 日期字段 / 设置项）与
    **LGoodDatePicker 依赖**（含 JitPack 仓库与 `THIRD-PARTY-LICENSES.txt`，
    安装包 434KB → 103KB，回到「零外部 Maven 坐标」）；
  - 删除「Bug-ID 正则」「默认日期范围」设置项与过滤栏的偏好持久化字段
    （`filterFields`/`useRegex`/`caseSensitive` 等；旧 `svnEnhanceMerge.xml` 由平台忽略未知字段，无需迁移）；
  - 测试 60 → 39 用例（删日期/正则/高亮/字段/token 语法用例，新增 AND/大小写/全字段用例）。
- **支持范围上限 2025.2 → 2026.2.3**（`until-build=252.*` → `262.*`）：已通过
  `runPluginVerifier` 对 IC-2022.1 与 IC-2026.2.3 两端的二进制 API 校验（两端均 Compatible，
  2026.2.3 侧自动解析并比对了对应的 Marketplace 版 `Subversion 262.10968.211`）。
  ⚠️ **IDEA 2026.x 起 JetBrains 已把 SVN 插件从发行版 unbundle**，在该版本上需先从
  Marketplace 安装官方 **Subversion** 插件，否则本插件无法加载（`<depends>Subversion</depends>`）。
- `runPluginVerifier` 固定使用 verifier-cli 1.410（原先默认 `latest` 每次联网解析，
  偶发 connection reset 会让任务莫名失败）；`-PverifierLocalIde` 支持逗号分隔多个本地 IDE。
- 安装包不再携带 Kotlin 标准库（官方要求，由 IDE 提供），体积 2.1MB → ~430KB。
- 接入官方 `runPluginVerifier` 做 API 兼容性校验；`verifyPlugin` /
  `verifyPluginConfiguration` 零告警。
- **修订选择过滤模型重构（第四轮）：搜索只搜「已加载」，删除版本区间**：
  - 搜索改为**纯客户端过滤**，只作用于**当前已加载集合**（首屏 + 用户手动「+100 / +500 / 全部显示」加载到的部分）；命中不足时**不再自动向服务器请求**，改为状态栏提示「已加载 N 条中匹配 M 条，可点「+100 / +500 / 全部显示」继续加载」，由用户显式决定是否加载更多（对齐 TortoiseSVN：只有 Next / Show all 才请求）。
  - **删除版本区间功能**（`ui/RevRangeDialog`、「版本区间…」工具栏动作、`LogFilter.revFrom`/`revTo`、`revrange.*` 文案）；游标恒从 HEAD 开始，「重新整理」= 清空并从 HEAD 重查第一页。
  - **过滤移到后台线程**（EDT 只 `setItems`）：此前在 EDT 上对全集做字符串拼接 + 正则 + `lowercase()`，两万条约冻结界面 0.4~1 秒，是"输入搜索词时 IDEA 卡死"的直接成因。
  - **加载任务互斥取消**（`ProgressIndicator.cancel()`）：此前旧任务既不取消也不停止发请求，且与新任务共享同一个 loader（互抢游标）——每敲一个字符叠一个并发扫描，IME 拼音一次词可叠 5~10 个。
  - 新增**已加载总量闸**（`MAX_LOADED_ALL` = 20000）与状态栏触顶提示；`LogFilter` 的分词结果缓存到实例（原先每判定一条记录都重新分词），并修掉「搜 `!abc` 时高亮按 `!abc`、实际匹配按 `abc`」的不一致。
  - 分离**过滤令牌**与**加载令牌**：改搜索词不再让在途的加载任务被丢弃（那会导致"数据没进集合、游标却已推进"，后续翻页整段跳过修订）。

- **修订选择过滤模型重构（第三轮）：搜索 / 日期 / 版本三个维度不再共用搜索框这一条通路——
  - 搜索框只做**文本匹配**（TortoiseSVN 语法不变：空格分词、`-` 排除、`+`/`"短语"`/`!`），不再把 `10-20` / `2026-10` 猜成版本区间（后者曾恒为空且无任何报错）；
  - 版本区间改为**独立对话框**（顶部「版本区间…」按钮，复刻 TSVN 的 Show range：上/下界各一个修订号输入框，**只有它**会改变向服务端 `svn log` 请求的版本范围）；
  - 日期区间改为**纯客户端过滤**（不再切换第二条服务端查询通道），默认开启且起止值恒等于已加载数据的提交日期、可选范围被锁在该区间内（从交互上杜绝"日历里有这一天、日志里却没有"）。
  - 加载器 `LogPageLoader` 简化为**单通道游标分页**（删除按日期的服务端查询模式），并引入"世代号"机制：过滤条件变化时旧的在飞加载任务立即作废、连一次请求都不发，杜绝旧条件数据串进新缓冲。

### 修复
- **修订选择搜索「含 # 搜不到 / 大库啥也搜不到 / 改搜索词就清空重拉且丢深历史**：根因有三——
  ① 日期区间被默认开启并预填成首屏窄窗口，模式 A（`svn log -r {date}:{date}`）只拉那一小段时间，更早的提交永不进入可搜索集；
  ② 每次改搜索词都会清空已加载列表、从 HEAD 重拉第一页（`onFilterChanged` 里 `model.setItems(emptyList())`），已下载的旧版本被丢弃；
  ③ 模式 B 的扫描深度受 `MAX_BATCHES=10`（约 1000 条）硬上限截断，HEAD 往前 1000 条之外的提交被静默丢弃。
  修复：① `EnhanceMergeSettings.dateRangeEnabled` 默认 `true → false`（对齐 TortoiseSVN，日期区间改为用户显式勾选才生效），默认走模式 B 从 HEAD 往回扫全量；
  ② `onFilterChanged` 不再清空重拉，改为对内存中已下载全集即时重过滤（零远端请求），仅当结果不足且未到底时才向后**扩充**加载；
  ③ 新增 `LogPageLoader.loadNextPageRaw`（返回未文本过滤的原始修订）支撑"累计全集 + 就地重过滤"，搜索 /「加载更多」/「全部显示」均在该全集上操作，深历史可被命中且不再静默丢弃（`SEARCH_MATCH_TARGET` / `MAX_SEARCH_ROUNDS` 控制扩充上限）。
- **日期区间模式下搜索出现与搜索目标无关的内容（且 `+100/+500` 按钮消失、改日期区间值不重拉）**：根因在模式 A（`svn log -r {date}:{date}` 整段拉完缓冲）的两处缺陷——
  ① `LogPageLoader.loadFromDateRangeRaw` 在缓冲拉完时立即置 `allLoaded=true`，导致对话框误判"再无数据"：搜索扩充循环 `while(!loader.allLoaded …)` 一轮即停（只覆盖日期区间**前 100 条**原始数据）、`+100/+500` 按钮因 `isVisible = !loader.allLoaded` 被隐藏；
  ② `onFilterChanged` 只在"日期开关翻转"时清空重拉，**改 From/To 区间值不重拉**，旧区间缓冲被继续复用、新日期范围被静默忽略，界面停留在旧数据上，表现为"加了日期范围后搜出来的都是无关内容"。
  修复：① `allLoaded` 在模式 A 改为"缓冲分页到底"才算取完（`bufferPos >= buffer.size`），搜索扩充、`+N`、全部显示在该模式下恢复对整段区间的覆盖；
  ② `onFilterChanged` 新增 `dateRangeChanged` 判定（模式 A 内 From/To 变化也清空重拉，防抖后一次服务端查询）；
  ③ 搜索扩充循环改用「原始页为空」作统一到底信号，不再被模式 A 的 `allLoaded` 提前截断。
- **GitHub Actions `Plugin Verifier` job 构建失败（`Could not find com.github.lgooddatepicker:LGoodDatePicker:11.2.1`）**：根因为该库当年只发布在已关停的 **JCenter**（版本号 `11.2.1`），Maven Central 与 JitPack 的 `11.2.1` 均无此构件；本机靠阿里云 `public` 聚合镜像缓存的 JCenter 旧构件才解析得到，CI 关了阿里云镜像即 404。其 GitHub 发布标签实为 `v11.2.1-Standard`，故改用 **JitPack**（`https://jitpack.io`，合法 Maven 仓库，按标签按需构建）坐标 `com.github.lgooddatepicker:LGoodDatePicker:v11.2.1-Standard`，并在 `repositories` 中始终声明 jitpack.io（CI 也需要）。`runPluginVerifier` 会再下一份 IDE 撑爆磁盘这点仍存在，但 LGoodDatePicker 改为网络源后不再依赖 IDE 自带库解压完整性。`Build and Test` job 因只需一份 IDE 侥幸成功，掩盖了该问题。
- **点「Show All」后日期控件不更新**：对话框打开时起止日历取的是首屏范围，而加载走游标模式（过滤条件里没有日期）；
  点「全部显示」后列表覆盖整条历史、日历框却仍是那个窄区间，用户看到的信息自相矛盾，
  再点「重新整理」还会被它截回去。现在加载完成后自动把日历扩到已加载数据的实际范围（`LogFilterPanel.widenDateRangeTo`，只扩不收，不会拆掉手填的更宽区间）。
- **CI：Plugin Verifier 下载上限 IDE 失败**：IDEA 2026 起 Community 与 Ultimate 合并为统一发行版，
  JetBrains 下载接口已无独立的 `ideaIC-2026.x` 条目（`code=IC` 返回 404），
  verifier 的 `ideVersions` 上限改用 `IU-2026.2.3`（`IU`/`IIU` 均指向统一发行版）。
- **IDEA 2026.x 启动即崩**：`DynamicBundle.findLanguageBundle()` 在 2026.x 被移除
  （搬到 `com.intellij.l10n.LocalizationUtil`），旧调用会抛 `NoSuchMethodError`；
  改为「新平台优先」的反射桥接，两个版本都能正确判断是否装了语言包（新增 3 个单测钉住回退路径）。
- **日期格式化线程安全**：`LogFilter` 原先共用一个静态 `SimpleDateFormat`（非线程安全），
  而该方法同时被 EDT 渲染与后台分页线程调用，改为不可变的 `DateTimeFormatter`。
- **「全部显示」伪造进度条**：原先用「已加载数 % 100」假造进度百分比，
  改为不确定进度 + 已加载计数（总量未知时官方不允许编造百分比）。
- 设置页标题语言与插件其余界面不一致：`applicationConfigurable` 上的 `key`/`displayName`
  会让平台按 `Locale.getDefault()` 解析（中文系统 + 英文 IDE 会显示中文），
  改为由 `Configurable.getDisplayName()` 提供，与对话框语言判定统一。
- 本地改动提示失败由 `LOG.error` 降为 `LOG.warn`（可恢复的前置步骤失败不该按"插件内部错误"上报）。
- 后台任务回调补齐 dispose 守卫：对话框关闭后不再写入已销毁的界面。
- `mergeChecker.prepare()` 加互斥锁与 `@Volatile`，保证只执行一次且对所有线程可见。
- 空格键切换勾选：`e.consume()` 移到 `keyPressed`（写在 `keyReleased` 不生效）。
- 快捷键注册改用对话框自身的 `Disposable`，避免泄漏。
- **设置项「默认日期范围（天）」完全不生效**（2026-10-19 代码审查）：对话框只调用
  `initDateRange()` 填起止日历，没打开「日期区间」开关，而过滤栏**只在开关打开时**才把起止值
  交给过滤条件（`LogFilterPanel.buildFilter()`）→ 设了 N 天既不按日期重拉、界面也看不到日期面板，
  属"配置写了但静默失效"。修复：新增 `EnhanceMergeSettings.dateRangeEnabledOnOpen()`
  （配了默认范围就开启）与 `LogFilterPanel.applyDefaultDateRange()`（开关 + 填值 + 显隐；
  刻意**不**写入"上次使用"偏好，免得用户把设置改回 0 后当初被强制打开的开关残留），
  并新增 `EnhanceMergeSettingsTest`（8 用例）钉住这层判定。
  （注：该"配了默认范围才开启"的语义在 2026-10-20 过滤模型重构中被进一步简化——日期区间默认开启、
  值恒等于已加载数据范围，详见同节上方「修订选择过滤模型重构」。）
- **关闭项目时注入的「Enhance Merge…」链接从未被摘除**：`removeInjectedLinks()` 首行
  `if (project.isDisposed) return` 让清理代码永不执行——`ComponentManagerImpl.dispose()`
  **先把容器状态置为 `DISPOSED`，再 `Disposer.dispose()` 子节点**（已 javap 核对 2022.1 SDK），
  子 `Disposable` 被执行时 `isDisposed()` 恒为 true。改为不加守卫、try/catch 兜住
  `AlreadyDisposedException`，并在 EDT 上**同步**执行（IDE 退出阶段排进事件队列的请求可能永不被调度）。
- **搜索可能把十万级修订拉进内存**：`runExtendSearch` 只有轮数上限（200 轮），而单轮最多可拉
  `MAX_BATCHES × pageSize` 条，最坏约 10 万条 `SvnChangeList`（每条还带受影响路径）常驻已加载全集。
  新增累计原始条数上限 `MAX_SEARCH_TOTAL_RAW = 20_000` 作为第二道闸（取「全部显示」量级的 2 倍）。
- **合并状态计算的并发竞态**：`OneShotMergeInfoHelper` 内部的 `myPartiallyMerged` 是普通 `HashMap`，
  后台 `checkList()` 写、EDT `getNotMergedPaths()` 读，且首屏 / +N / 搜索扩充会并发起多个计算任务。
  现在 `prepare` / `checkList` / `getNotMergedPaths` 共用一把锁（`checkList` **逐行**加锁，
  避免 EDT 等整批算完而卡 UI），且 prepare 未就绪时 EDT 直接返回空、不进锁（否则要等一次 svn 调用）。
- **合并状态计算的异常捕获过窄**：`prepare()` / `checkList()` 原先只捕 `VcsException`，
  非受检异常逃出后台任务会被平台记成插件内部错误并上报；改为 `catch (Exception)` + `LOG.warn`。
- **勾选修订时状态栏在 EDT 上全量重算（大选集卡顿）**：每次勾选都对**全部**选中修订执行
  `collectChanges()`（为每个路径构造 `Change` + 跨修订合并），「全选 + 全部显示」后一次勾选就要
  遍历上万条。改为防抖重算（`Alarm`，200ms，绑对话框 disposable）：状态栏沿用上一次的数字立即刷新，
  连续勾选只在停手后算一次。
  ⚠️ 不能用「各修订路径数之和」代替：`CommittedChangesTreeBrowser.collectChanges` 末尾的
  `zipChanges()` 会把同一文件在多个修订里的改动**合并成一条**，两种算法不等价。
- **`LogPageLoader.cursor` / `allLoaded` 未加 `@Volatile`**：这两个字段由后台分页任务写、EDT 读
  （工具栏「+N」的显隐与 `loadMore()` 的提前返回），可能读到旧值导致按钮滞后一拍。已补 `@Volatile`。
- **首屏加载的回调缺 disposed 守卫**：`MergeFlow.openRevisionSelect` 的 `onSuccess` / `onThrowable`
  未判断项目是否已关闭，加载期间关掉项目会对已释放的 project 弹模态框 / 建对话框。已补守卫。
- **设置页 Bug-ID 正则输入尾部空格时永远显示"已修改"**：`isModified()` 比原始文本、`apply()` 写
  trim 后文本，二者永远不等。改为共用 `normalizedBugIdPattern()`（trim + 空串回退默认）。
- **修订选择对话框不可拉伸**：长提交说明与深路径只能靠滚动看；改为 `setResizable(true)`（尺寸仍记忆）。
- 清理：删除残留的空 `libs/` 目录（依赖已改为 JitPack 网络源）；`LogPageLoader.loadNextPage` 标
  `@VisibleForTesting`（生产一律走 `loadNextPageRaw`，两条路径的 `allLoaded` 语义刻意不同）。
- **搜索 `2026-10` 恒为空、改一个字符就隐式切换查询通道并清空重拉**：根因为搜索框曾把看似版本区间的输入（`2026-10` → `[10, 2026]`）直接改写向服务端请求的范围，而该范围与文本搜索不兼容，导致空结果且无任何报错；同时每次键入都会触发"模式切换 → 清空全集 → 从 HEAD 重拉"。修复：搜索框不再解析版本区间（见上「变更」），版本区间改由独立对话框承载；改搜索词只做内存重过滤，不再触碰服务端查询路径。
- **日期区间默认开启、值恒等于已加载数据的提交日期**：此前日期区间默认关闭（2026-10-19 修复改为"配了默认范围才开启"，仍是补丁式）。现对齐 TortoiseSVN——`LogFilter.LOGFILTER_ALL` 含日期位，对话框一打开就用已加载数据的 min/max 填起止、日历可选范围锁在数据区间内；用户手动改过日期后，加载更多不再覆盖其选择（否则新加载的更早条目会被日期过滤整批抹掉）。

### 规范（IntelliJ 官方插件开发规范整改，2026-10-18 全量审查）
- **禁用 `javax.swing.Timer`**：过滤栏输入防抖与入口注入的兜底轮询改用平台 `Alarm`
  （前者绑对话框 disposable；后者让安装器实现 `Disposable` 并注册到 project，
  关闭时未触发的请求自动作废、注入的链接自动摘除）。
- **HiDPI**：所有硬编码的行高/间距/分隔条宽度改走 `JBUI.scale` / `JBUI.Borders`。
- **主题色**：日期控件「非法日期」的硬编码 `JBColor.RED` 改为 `UIUtil.getErrorForeground()`；
  合并方式对话框的说明文字改用 `JBLabel` + `UIUtil.getContextHelpForeground()`。
- **异常窄化**：全量移除 `catch (Throwable)`，改为捕获具体异常
  （反射场景按 `ReflectiveOperationException` 与 `LinkageError` 分开处理）。
- `invokeLater` 显式传入 `ModalityState` 与「项目已关闭」过期条件。
- ESC 取消改走 Action 系统（`DumbAwareAction` + `CustomShortcutSet`）；
  说明文字拼 HTML 前做 XML 转义。
- 设置页 `FormBuilder` 用平台「一整行」间距而非魔法像素；spinner 步长/上限等魔法数提为常量。
- 用户可见文本不再手工拼接：状态栏的 `r{x} – r{y}` 与 `[已合并]` 的方括号都收进文案包。
- 清理死代码：`LogPageLoader.DEFAULT_PAGE_SIZE`（与 `EnhanceMergeSettings` 重复且无引用）。
- 新增 `BundleKeysTest`（4 个用例）：动态拼接的 `filter.field.*` key 与枚举双向对齐、
  中英文包 key 集合一致，杜绝「新增过滤维度忘加文案」导致界面抛异常。

## [1.0.0]

- 首个可安装版本（内部构建，尚未发布到 Marketplace）。