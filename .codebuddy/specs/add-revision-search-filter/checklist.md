# Checklist — 版本选择对话框原版化 + 显式搜索过滤（第五轮验收）

> 用法：实施完成后逐项核对。A 节为构建/静态验收，B~G 节为沙箱人工/文档验收。
> 需求编号见同目录 `spec.md`。
>
> **状态（2026-10-21）**：A 节已全部通过（自动验证）；B~G 节待人工沙箱走查后勾选。

## A. 构建与静态验收（✅ 已通过）

- [x] A1 `gradlew clean test` 0 失败；用例数与 `tasks.md` T10.5 记录一致（**39**）
- [x] A2 `gradlew buildPlugin` BUILD SUCCESSFUL；zip 无 LGoodDatePicker / kotlin-stdlib 混入（**103KB**）
- [x] A3 产物字节码 `javap -verbose -cp build/classes/kotlin/main com.weixf.svn.mergeenhance.flow.MergeFlow
      | grep "major version"` = **55**
- [x] A4 `gradlew verifyOnIde "-PideaVersion=2022.1" "-PverifyTimeout=900"` → **PASS（0 ERROR，i18n 就绪行正常）**
- [x] A5 `gradlew runPluginVerifier -PverifierLocalIde="…ideaIC-2022.1,…ideaIC-2026.2.3"` →
      IC-221 与 IC-262 均 **Compatible**（262 侧 1 处 deprecated 不阻断）
- [ ] A6 全仓无 `THIRD-PARTY-LICENSES.txt`；`build.gradle.kts` 无 JitPack / LGoodDatePicker
- [ ] A7 代码规范自检（对应 tasks T11）：无 Swing Timer / Alarm 绑 Disposable / JBUI 缩放 /
      无硬编码颜色 / 无 catch(Throwable) / invokeLater 带 ModalityState / 图标按钮有无障碍名 /
      无 2022.1 不存在 API
- [ ] A8 `search_content` 全仓无 `TableViewSpeedSearch` / `LogFilterPanel` / `LogFilterField` 残留引用

## B. 界面回归原版（对照用户 2026-10-20 截图）

- [ ] B1 标题 = `Merge from <branch>`（来自 `MergeContext.mergeTitle`）
- [ ] B2 工具栏**只有** 4 个动作：Select all / Unselect all / +100 / +500（图标与原版一致）
- [ ] B3 无 Show All、无 Refresh、无日期控件、无 By 下拉、无正则/大小写开关
- [ ] B4 表格无表头、无网格线、平台默认行高
- [ ] B5 行渲染 = 复选框(最左) + 状态图标 + 修订号 + 提交说明 + 日期 + 文件摘要
      （与截图中 `61 conflict test` / `60 Merged from dev 1 [from revision 59]` 等行的信息构成一致）
- [ ] B6 已合并/外来/刷新中图标按 `ListMergeStatus` 显示；关「计算已合并状态」设置后不显示且不查 mergeinfo
- [ ] B7 右栏变更文件树正常；选中多修订时 `[Already merged]` 标记出现
- [ ] B8 Splitter 比例 0.7、分隔条 2px；对话框可缩放且尺寸记忆生效
- [ ] B9 底部仅 `Merge Selected` / `Cancel`；无自定义状态栏

## C. 搜索过滤功能

- [ ] C1 搜索框位于**工具栏上方独立一行**，宽度适度（约 450px 量级）不拉满，左对齐
- [ ] C2 placeholder 显示 `search.placeholder` 文案；✕ 按钮仅在有内容时出现
- [ ] C3 输入关键词（如 `conflict`）→ 列表即时过滤；多关键词空格分隔按 AND
- [ ] C4 大小写不敏感（输入 `CONFLICT` 同样命中）
- [ ] C5 可按**路径 / 作者 / 版本号 / 日期**命中（各测一次，如作者名、`src/main`、`57`、`2026/10`——
      注意日期匹配文本是 `yyyy-MM-dd HH:mm`，输入 `2026-10` 可命中）
- [ ] C6 计数标签显示 `匹配 M / 已加载 N`；无搜索词时隐藏
- [ ] C7 Esc / ✕ 清空 → 恢复完整列表，焦点留在搜索框
- [ ] C8 连续快速输入不卡界面（防抖 + 后台过滤）；无 EDT 冻结
- [ ] C9 **无任何高亮**（命中行与普通行文本样式一致）
- [ ] C10 焦点在表格上直接打字**不弹**任何搜索条（隐式 speed search 已删）
- [ ] C11 空格键仍能批量切换勾选（与 C10 同时成立，无按键竞争）
- [ ] C12 过滤态为**零请求**：过滤过程中无 svn log 网络往返（可用过滤时点 +100 的行为反证：
      只有点 +N 才发请求）

## D. 分页与勾选协同

- [ ] D1 过滤态下点 `+100`：新加载数据中**命中搜索词的才入列**，未命中的不入列；
      已加载全集持续累积，不跳段（验证方法：过滤 `A` 记下最早修订号 → +100 → 清除过滤，
      确认全集连续无空洞）
- [ ] D2 `+100`/`+500` 加载中：+500 隐藏、+100/+500 禁用；完成后恢复
- [ ] D3 加载到底后两个 +N 隐藏
- [ ] D4 勾选状态跨过滤/翻页保留（稀疏语义：全选后排除个别，再过滤仍成立）
- [ ] D5 `Merge Selected` 在无勾选时禁用；合并执行链路（本地改动提示 → 冲突解决 → VFS 刷新）不受影响

## E. 设置与持久化

- [ ] E1 设置页只剩「每页版本数」「计算已合并状态」
- [ ] E2 旧 `svnEnhanceMerge.xml`（含 `dateRangeEnabled`/`filterFields` 等已删字段）加载不报错
- [ ] E3 「计算已合并状态」关闭 → 无图标、无 mergeinfo 查询、首屏速度与纯 svn log 一致

## F. 回归防护

- [ ] F1 `LogFilterTest` 新语义用例全绿（AND/大小写/全字段/空词）
- [ ] F2 `BundleKeysTest` 双向对齐（代码引用的 key 与 properties 一一对应，无孤儿无缺失）
- [ ] F3 中文环境（装中文语言包）界面文案正确；英文环境走基包
- [ ] F4 大仓库（≥2 万修订能力上限内）连续输入 10 个字符 → 界面无冻结、svn 请求数为 0

## G. 文档同步

- [ ] G1 `README.md` 同步（能力表/架构/文件树/测试清单）
- [ ] G2 `CHANGELOG.md` 新增第五轮条目
- [ ] G3 规则文档 `.codebuddy/rules/svn-enhance-merge.mdc` 新增第五轮节并同步类清单与单测数
- [ ] G4 `plan` 文档标注「已实施」+ 验证结果；`tasks.md` / 本文档全部勾选

---

**验收通过标准**：A 节全绿 + B/C/D 节无未勾选项 + E/F 节无回归 + G 节完成。
任何一项不满足 → 记录原因，修复后重跑对应节，不整体放行。
