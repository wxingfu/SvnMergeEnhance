# 贡献指南

感谢关注本项目。以下约定可减少沟通成本。

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | **11**（与 IDEA 2022.1 运行时 JBR 11 对齐；JDK 8 无法构建） |
| Gradle | 仓库自带 wrapper（`./gradlew`） |
| IDE 建议 | IntelliJ IDEA 2022.1+ |

本机相关配置（`org.gradle.java.home`、`intellij.ideaDependencyCachePath`）**不要写进仓库的
`gradle.properties`**，请放到用户级文件 `%USERPROFILE%\.gradle\gradle.properties`——
否则 CI 与其他人的构建会失败。

## 常用命令

```bash
./gradlew buildPlugin          # 产出 build/distributions/SvnMergeEnhance-<version>.zip
./gradlew test                 # 63 个纯逻辑单测，不连仓库
./gradlew verifyPlugin         # 官方包结构校验
./gradlew runPluginVerifier    # 官方 API 兼容性校验（会下载 IDE，较慢）
./gradlew verifyOnIde -PideaVersion=2022.1    # 启沙箱 + 扫插件异常日志
./gradlew runIde               # 沙箱人工实测
```

> 改了代码后若 `buildPlugin` 报 `Could not copy file ... jar`，多半是上一次沙箱 IDE 还在运行
> 锁住了文件：先跑 `verifyOnIde "-PverifyAutoClose=true"`，或结束 `*idea-sandbox*` 进程。

## 必须遵守的约束（详见 `.codebuddy/rules/svn-enhance-merge.mdc`）

1. **不 fork、不修改 svn4idea 源码**，只调用其公开 API；禁用 SVNKit 等外部 Maven 坐标。
2. 目标字节码保持 **11**；Kotlin stdlib **不打包**。
3. 用户可见字符串一律走 `messages/EnhanceMergeBundle*.properties`，不硬编码。
4. 耗时/IO 放后台任务；所有异步回调必须判 `isDisposed`；跨线程状态用
   `ConcurrentHashMap` / `@Volatile` + 互斥。
5. 合并执行必须复用原版 `SvnIntegrateChangesTask`；**不写** `SvnConfiguration`
   （会影响原版 Merge From 的行为）。
6. 升级 IDE 基线前，先跑 `runPluginVerifier` 与 `verifyOnIde`，并按规则文件里的
   「svn4idea API 兼容性基线」重新核对签名。

## 提交前自检

- [ ] `./gradlew test buildPlugin verifyPlugin` 全绿
- [ ] 改动若涉及界面，在沙箱里人工走过一遍主流程
- [ ] 新增/修改的字符串已同步中英两份 bundle
- [ ] `CHANGELOG.md` 已更新