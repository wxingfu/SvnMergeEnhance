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
- 安装包不再携带 Kotlin 标准库（官方要求，由 IDE 提供），体积 2.1MB → ~430KB。
- 接入官方 `runPluginVerifier` 做 API 兼容性校验；`verifyPlugin` /
  `verifyPluginConfiguration` 零告警。

### 修复
- 后台任务回调补齐 dispose 守卫：对话框关闭后不再写入已销毁的界面。
- `mergeChecker.prepare()` 加互斥锁与 `@Volatile`，保证只执行一次且对所有线程可见。
- 空格键切换勾选：`e.consume()` 移到 `keyPressed`（写在 `keyReleased` 不生效）。
- 快捷键注册改用对话框自身的 `Disposable`，避免泄漏。

## [1.0.0]

- 首个可安装版本（内部构建，尚未发布到 Marketplace）。