// SVN Enhance Merge —— 插件构建脚本
//
// 技术栈：Kotlin JVM + Gradle IntelliJ Plugin（IPGP）。
// 依赖 IDEA 2022.1 (IC) SDK + 内置 Subversion 插件，只调用其公开 API，不 fork 源码。
//
// 每个配置项的"为什么"见下方行内注释；构建/验证命令见 README「开发与构建」。

import org.jetbrains.intellij.tasks.BuildPluginTask
import org.jetbrains.intellij.tasks.PatchPluginXmlTask
import org.jetbrains.intellij.tasks.RunPluginVerifierTask


plugins {
    // Kotlin 1.7.22：IDEA 2022.1 内置 Kotlin 插件同版本，编译产物与其运行时最匹配
    kotlin("jvm") version "1.7.22"
    // IntelliJ Platform Gradle Plugin 1.x：负责下载 IDE SDK、编译插桩、打包插件 zip。
    // 锁 1.x 而不是 2.x：本仓库的配置（ideaDependencyCachePath / plugins.set）均为 1.x 语义，
    // 2.x 改动面大且收益不明显。
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.weixf.svn.mergeenhance"
version = "1.0.0"

repositories {
    // 顺序有意义：先 JetBrains（ideaIC 只在这里发布，国内镜像均无），
    // 再阿里云镜像（加速其它依赖），最后官方源兜底。
    // ideaIC 的 Gradle 元数据缓存过期后必须能从 JetBrains 仓库重新解析，故它排第一。
    maven { url = uri("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases") }
    maven { url = uri("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/snapshots") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    gradlePluginPortal()
    google()
    mavenCentral()
}

intellij {
    // 以 IDEA 2022.1 Community 作为编译 / 沙箱运行 / 打包基准。
    // 可用 -PideaVersion=2025.2 覆盖，用于在其他 IDE 版本上回归验证（无需改文件）——
    // 这是跨版本兼容的唯一入口：svn4idea 的 API 无兼容承诺，升级前必须先跑一遍。
    version.set(project.providers.gradleProperty("ideaVersion").getOrElse("2022.1"))
    // IC（Community）：功能覆盖本插件所需（VCS 框架 + Subversion 捆绑插件），体积比 IU 小
    type.set("IC")
    // IDE 解压目录：默认会解压到 Gradle 缓存里 zip 的旁边（位置随缓存目录漂移、不好清理）。
    // 路径通过 gradle.properties 的 intellij.ideaDependencyCachePath 指定，可留空保持默认。
    // 固定路径的另一个好处：`gradlew cleanIdea setupDependencies` 能精确重建，
    // 不会在缓存里留下多个不同版本的解压副本。
    project.providers.gradleProperty("intellij.ideaDependencyCachePath").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { ideaDependencyCachePath.set(it) }
    // 关键：显式把捆绑的 Subversion 插件类暴露到编译 classpath，
    // 否则 import org.jetbrains.idea.svn.* 会编译失败。
    // 不写 plugins.set 时 IPGP 只把 IDE 核心类放上 classpath，bundled plugin 的类不可见。
    plugins.set(listOf("Subversion"))
    // since/until 由下方 PatchPluginXmlTask 手动控制（不跟随 SDK 默认值）。
    // 若不置 false，IPGP 会用 SDK 版本覆盖 plugin.xml 的 idea-version，
    // 导致"用 2025.2 编译就声明支持 2025.2"——而我们只在 2022.1 实测过。
    updateSinceUntilBuild.set(false)
}

kotlin {
    // 与 IDEA 2022.1 运行时 (JBR 11) 对齐；需 JDK 11 及以上工具链。
    // 插件字节码 target 11 是"能装进 2022.1"的硬约束（IDE 用自己的 JBR 加载插件，
    // 目标版本高于运行时会直接 ClassFormatError）。
    jvmToolchain(11)
}


dependencies {
    // 日期选择控件：过滤栏的 From/To 日历。
    // 选它而非 JXDatePicker：LGoodDatePicker 的 DatePickerSettings 可逐项配颜色/边框，
    // 能完全融入 IDEA 主题（见 LogFilterPanel.createDatePicker）。
    implementation("com.github.lgooddatepicker:LGoodDatePicker:11.2.1")
    // 纯逻辑单测：kotlin-test（JUnit5）+ mockk。
    // mockk 的用途：SvnChangeList / MergeContext 的构造需要 SvnVcs（IDE 组件），
    // 单测里只 stub 过滤/分页真正读到的 getter，避免为测试去启动 IDE。
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.5")
}

tasks.test {
    // JUnit5（Jupiter）：kotlin-test 的 KotlinTest 风格断言在此平台上运行；
    // IPGP 会把 IDE SDK 加入 test classpath，因此测试可直接引用 svn4idea 类。
    useJUnitPlatform()
}

tasks.withType<PatchPluginXmlTask> {
    // 最低支持 IDEA 2022.1（build 221）
    sinceBuild.set("221")
    // 已验证上限：2025.2（build 252）。
    // svn4idea 侧 14 个关键 API 已与最新源码逐一比对签名一致（见 .codebuddy/rules 的兼容性基线），
    // 但 2026.x 未实测；升级前请用 `gradlew verifyOnIde -PideaVersion=<目标版本>` 回归后再提高此值。
    untilBuild.set("252.*")
}

// ------------------------------------------------------------ 插件包根目录文件
// JetBrains Marketplace 要求 `pluginIcon.svg` 位于**插件目录根部**（即 zip 内
// `SvnMergeEnhance/pluginIcon.svg`），而 IPGP 1.x 默认把 src/main/resources 全部打进
// lib/*.jar（图标会落到 jar 内的 META-INF/，Marketplace 识别不到）。
// `buildPlugin` 任务本身就是 Zip（IPGP 已 `into(pluginName)`），直接往它追加文件即可落到正确位置。
tasks.named<BuildPluginTask>("buildPlugin") {
    from("src/main/resources") {
        include("pluginIcon.svg", "pluginIcon_dark.svg", "THIRD-PARTY-LICENSES.txt")
    }
}

// ------------------------------------------------------------ 官方 API 兼容性校验
// JetBrains Plugin Verifier 会拿插件去比对目标 IDE 的二进制 API，报告
//「缺失类 / 方法签名变更 / internal API 依赖」等问题—— svn4idea 不在平台兼容承诺内，
// 升级 IDE 基线前跑一次比等到运行时炸掉划算。
//
// 用法：
//   gradlew runPluginVerifier                     # 校验最低与最高支持版本（会下载对应 IDE）
//   gradlew runPluginVerifier -PverifierLocalIde=<已解压的 ideaIC 目录>
//                                                  # 走 localPaths，复用本机已解压的 SDK，不下载 IDE
tasks.named<RunPluginVerifierTask>("runPluginVerifier") {
    val localIde = providers.gradleProperty("verifierLocalIde").orNull
    if (!localIde.isNullOrBlank()) {
        localPaths.set(listOf(file(localIde)))
    } else {
        // 只取支持范围的两端：最低（221）与最高（252.*），中间版本 API 连续，抽查两端即可
        ideVersions.set(listOf("IC-2022.1", "IC-2025.2"))
    }
    // 只对「兼容性硬问题」失败（缺类、签名不符）。deprecated / 内部 API 等软问题只报告不阻断，
    // 否则 bundled 插件 API 的 deprecation 会把构建卡死（svn4idea 本身就在演进）。
    failureLevel.set(listOf(RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS))
}

// ------------------------------------------------------------ 跨平台回归验证
// 等价于手动执行 scripts/verify-plugin.ps1（Windows）/ scripts/verify-plugin.sh（Unix）：
// 自动切 SDK 启动沙箱、轮询插件就绪、扫描插件异常日志并给出 PASS/FAIL。
//
// 用法：
//   gradlew verifyOnIde                          # 默认验证 2022.1
//   gradlew verifyOnIde -PideaVersion=2025.2     # 验证指定版本（首次会下载该 IDE）
//   gradlew verifyOnIde -PideaVersion=2026.2.3 -PverifyTimeout=900
//   gradlew verifyOnIde -PideaVersion=2025.2 -PverifyAutoClose=true   # 检查后关闭沙箱
val verifyOnIde by tasks.registering {
    group = "verification"
    description = "Boot the sandbox on a specific IDE version, wait for the plugin to load, then scan the log for plugin errors."

    doLast {
        val version = (project.findProperty("ideaVersion") as String?) ?: "2022.1"
        val autoClose = (project.findProperty("verifyAutoClose") as String?).toBoolean()
        val timeout = (project.findProperty("verifyTimeout") as String?) ?: "420"
        val projectPath = (project.findProperty("verifyProject") as String?) ?: ""
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        val command = if (isWindows) {
            buildList {
                add("powershell")
                add("-NoProfile")
                add("-ExecutionPolicy")
                add("Bypass")
                add("-File")
                add("scripts/verify-plugin.ps1")
                add("-Version")
                add(version)
                add("-TimeoutSec")
                add(timeout)
                if (autoClose) add("-CloseWhenDone")
            }
        } else {
            buildList {
                add("bash")
                add("scripts/verify-plugin.sh")
                add("--version")
                add(version)
                add("--timeout")
                add(timeout)
                if (projectPath.isNotBlank()) {
                    add("--project")
                    add(projectPath)
                }
                if (autoClose) add("--close-when-done")
            }
        }

        logger.lifecycle("[verifyOnIde] target IDE: $version (timeout=${timeout}s, autoClose=$autoClose)")
        val result = project.exec {
            commandLine(command)
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException("verifyOnIde failed with exit code ${result.exitValue}; see the output above.")
        }
    }
}
