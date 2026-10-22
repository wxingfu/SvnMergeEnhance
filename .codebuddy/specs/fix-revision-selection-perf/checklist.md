# Checklist — 修订选择面板的勾选正确性与性能整改（第八轮验收）

> A 节构建/静态，B~D 节行为，E 节文档。需求编号见同目录 \spec.md\。
> **状态（2026-10-22）**：A 节已通过；B/C/D 节待真实 IDE 走查。

## A. 构建与静态验收

- [x] A1 \clean test buildPlugin\ BUILD SUCCESSFUL；**49 用例 0 失败**
- [x] A2 字节码 major 55；zip 约 119KB（无 stdlib / 第三方依赖）
- [x] A3 \erifyOnIde 2022.1\ → **PASS（0 issues）**
- [x] A4 unPluginVerifier\（IC-221 + IC-262）→ 均 **Compatible**
- [x] A5 无 \javax.swing.Timer\；无 \catch (Throwable)\；无内部 API
- [x] A6 后台→EDT 回调均带 \ModalityState\ + 过期条件
- [x] A7 eloadToken\ 已彻底移除（grep 无命中）

## B. 勾选正确性（B1 回归）

- [x] B1 单测：过滤视图只剩一条时，合并集合仍含过滤前勾选的修订
- [x] B2 单测：全选模式 = 全集 − 排除项
- [x] B3 单测：「全选」有过滤 → 只选可见行；无过滤 → 全集
- [ ] B4 人工：勾 r100/r200 → 搜索只匹配 r200 → 合并集合仍含 r100
- [ ] B5 人工：有过滤时点「全选」→ 只合并可见行

## C. 性能与释放

- [ ] C1 人工：全选 2000+ 条 → 界面不冻结（约 200ms 内出右栏）
- [ ] C2 人工：点「+500」→ 界面不冻结
- [ ] C3 人工：点「+N」后立刻点「取消」→ 真的停住
- [ ] C4 人工：关闭对话框 → 无残留后台任务
- [ ] C5 人工：达 2 万条上限 → 「+N」置灰且 tooltip 说明原因

## D. 杂项回归

- [ ] D1 合并状态图标正常；计算失败的行不再永远显示「刷新中」
- [ ] D2 「全不选」清空全部勾选；勾选框与合并集合一致
- [ ] D3 搜索框计数（匹配 M / 已加载 N）正常
- [ ] D4 中英文案正确（新 key \status.load.limit.reached\）

## E. 文档同步

- [x] E1 \CHANGELOG.md\ 新增「第八轮」条目
- [x] E2 \README.md\ 说明「勾选与过滤的关系」
- [x] E3 新增 \plan/RevisionSelect-Correctness-Perf-plan.md\ + 本 spec 三件套

---

**通过标准**：A 节全绿 + B/C/D 无未勾选项 + E 完成；不满足则记录原因并重跑对应节。
