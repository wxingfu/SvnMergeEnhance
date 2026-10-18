# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- 入口链接注入 SVN 的「Subversion Working Copies Information」面板（紧跟「Merge From…」下方），
  项目关闭时自动摘除，不留残留。
- 修订选择对话框：TortoiseSVN 式过滤（Message / Path / Author / Revision / Bug-ID / Date +
  正则 / 大小写 / 日期区间 / 版本区间）、分页加载（+100 / +500 / Show All / Refresh）、
  快速搜索、`Ctrl+D` 查看差异。
- 每修订的合并状态图标（已合并 / 外来 / 计算中）与右栏 `[Already merged]` 标记，
  复用原版 `MergeChecker` / `ListMergeStatus`，可一键关闭。
- 合并执行完全复用原版链路：`LocalChangesPromptTask`（本地改动提示）→
  `SvnIntegrateChangesTask`（进度 / 冲突解决 / 更新信息树）。
- 设置页：`Settings | Version Control | SVN Enhance Merge`（每页条数、合并状态开关、
  Bug-ID 正则、默认日期范围）。
- 界面文案英文 + 简体中文，按 IDE 语言包状态自动切换。

### 变更
- 项目/包名重命名：`SubversionEnhance` → `SvnMergeEnhance`，
  `com.svn.revisionselect` → `com.weixf.svn.mergeenhance`，
  插件名 `SVN Custom Merge` → `SVN Enhance Merge`，界面中文「自定义合并」→「增强合并」。
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

### 修复
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