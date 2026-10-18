# Checklist — Subversion Working Copies Information Enhance（v3 并列标签页）

> 验收清单。所有条目需由 `gradlew buildPlugin` / `gradlew test` / `gradlew runIde`（真实 SVN 项目）确认。
> 对应 spec：`.codebuddy/specs/add-wcinfo-enhance-tab/spec.md`；设计：`.codebuddy/plan/WcInfoEnhanceTab-plan.md`。

## E0 验证闭环（前置）

- [ ] `gradlew buildPlugin` 可编译产出 zip；`gradlew test` 全绿（53 既有 + 新增）
- [ ] `gradlew runIde` 沙箱启动，`idea.log` 出现 `Loaded custom plugins: SVN Enhance Merge`
      与 `EnhanceMerge i18n` 就绪行

## R1 并列标签页

- [ ] VCS 工具窗出现「Subversion Working Copies Information Enhance」标签页，位于原版之后
- [ ] 未启用 SVN 的项目中该标签页不出现（谓词生效）
- [ ] 原版「Subversion Working Copies Information」内容与行为完全不变

## R2 数据与刷新

- [ ] 首次打开标签页列表已（或正在）加载，按 path 升序，与原版一致
- [ ] roots 变更（检出/新增 root）后列表自动重建
- [ ] 工具栏刷新按钮：Enhance 页激活时可见可用，切走自动隐藏，刷新中禁用（防重入）
- [ ] 错误副本：url 行红字错误信息，操作行不显示
- [ ] `idea.log` 全程无 `Synchronous execution on EDT`（EDT 上无 svn 调用）

## R3 信息行

- [ ] 行序与原版逐行一致：path(bold) → url → format → depth → nested type(斜体) →
      isWcRoot(斜体) → Cleanup → Configure Branches → Enhance Merge…(bold)
- [ ] Change Format 仅在 `upgradeFormats.size > 1` 时出现；Fix Depth 仅非 INFINITY 深度出现
- [ ] Cleanup 仅 format ≥ 1.7 出现
- [ ] 文案全部来自 EnhanceMergeBundle，代码无硬编码可见字符串（含标点）

## R4 链接行为（与原版一致）

- [ ] Cleanup：先保存文档 → 后台执行，行为与原版一致
- [ ] Configure Branches：打开原版同款分支配置对话框
- [ ] Change Format：确认后后台转换，完成后列表自动刷新
- [ ] Fix Depth：确认对话框 → INFINITY 深度 checkout，视图随 roots 变更刷新
- [ ] root 失效：原版同款警告并中止，不发起 svn 调用
- [ ] Enhance 标签页**无**原版 Merge From 入口

## R5 Enhance Merge 入口

- [ ] 点击 Enhance Merge… → 原版同款 Select Branch 弹窗 → v2 全流程可用
- [ ] 多副本项目点击第 N 个副本 → 合并目标为该副本（零反射取 WCInfo）
- [ ] 修订选择 / 合并执行链行为与迁移前完全一致（零改动验证）

## R6 注入入口移除

- [ ] `wcinfo/WcInfoLinkInjector.kt` 已删除；`plugin.xml` 无 `<postStartupActivity>`
- [ ] 原版面板无注入链接、无 `svn.enhanceMerge.*` client property 残留
- [ ] 项目反复开关无异常、无泄漏日志

## R7 本地化

- [ ] 未装语言包 → 标签名与面板文案全英文；装中文语言包 → 全中文
- [ ] 新增 key 双语齐备，`BundleKeysTest` / `EnhanceMergeBundleTest` 扩展通过

## R8 生命周期与规范

- [ ] messageBus 订阅随面板 dispose 解绑；无 Timer / 轮询残留
- [ ] 所有 `invokeLater` 带 ModalityState 且守卫写在 lambda 内
- [ ] HiDPI（`JBUI`）、主题色（`UIUtil`）、`LOG.error` 使用符合 rules
- [ ] 反射残留为零：`runPluginVerifier` 两端 Compatible
      （IC-2022.1 + IC-2026.2.3，重点盯新增 svn4idea public 类引用）

## 官方校验与收尾

- [ ] `gradlew verifyPlugin` + `verifyPluginConfiguration` 零告警
- [ ] `gradlew runPluginVerifier -PverifierLocalIde=G:/GradleRepository/idea/ideaIC-2022.1,G:/GradleRepository/idea/ideaIC-2026.2.3`
      → 两端 Compatible，0 兼容性问题
- [ ] `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyAutoClose=true"` → PASS（issues 0）
- [ ] README / rules / 旧 spec R1 标注 / plan 实施结果小节 已同步
- [ ] 人工 `runIde` 全流程走通一次真实合并（Enhance 标签页 → 分支 → Revision Select →
      修订选择 → Merge Selected → 冲突/结果树）

## 待定

- [ ] 首启「入口迁移引导」通知：做 / 不做（默认不做，见 plan §7）
- [ ] Enhance Merge 行加粗样式确认（spec R3 Scenario 已定 bold，实现时视觉确认）
