// 仓库配置分两层，原因不同：
// - pluginManagement：解析**构建插件自身**（IPGP、Kotlin 插件），在 settings 阶段就要用；
// - dependencyResolutionManagement：解析**项目依赖**（ideaIC、LGoodDatePicker、mockk）。
// 两处都放阿里云镜像优先（国内加速），JetBrains 仓库不能少——ideaIC 只在那里发布。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}


dependencyResolutionManagement {
    // PREFER_PROJECT：必须允许项目仓库生效 —— IntelliJ Gradle 插件会向项目注册
    // intellij-repository 等仓库来解析 ideaIC / ideaLocal，PREFER_SETTINGS 会导致其被忽略而构建失败
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // IntelliJ Platform SDK（ideaIC 等）只发布在 JetBrains 仓库，Gradle 元数据缓存过期后需要它重新解析
        maven { url = uri("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases") }
        maven { url = uri("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/snapshots") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}


// 产物名（zip 会叫 SvnMergeEnhance-<version>.zip），与插件 id 保持一致便于识别
rootProject.name = "SvnMergeEnhance"
