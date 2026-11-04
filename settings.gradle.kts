pluginManagement {
    // 约定插件：trackr.android.application / trackr.kmp.library / trackr.kmp.compose / trackr.kmp.feature
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// JDK 21 toolchain 缺失时由 foojay resolver 自动下载（~/.gradle/jdks）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 仅对高德 SDK 生效：其 jar 体积大（30MB+），Maven Central 在国内拉取极慢，
        // 用阿里云镜像加速；其余依赖仍走官方仓库，未命中时自动回落。
        maven("https://maven.aliyun.com/repository/public") {
            content { includeGroup("com.amap.api") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Trackr"
include(":androidApp")
include(":core:designsystem")
include(":core:model")
include(":core:domain")
include(":core:testing")
include(":core:navigation")
include(":core:network")
include(":core:data")
include(":core:database")
include(":core:datastore")
// 第 17 步：业务通用组件、功能模块、根 Composable（重构计划 §2.2）
include(":core:ui")
include(":feature:bus")
include(":feature:search")
include(":feature:stop")
include(":feature:line")
include(":feature:route")
include(":feature:favorites")
include(":feature:me")
include(":composeApp")
// 第 27 步：Baseline Profile 生成器 + Macrobenchmark（要连真机跑）
include(":baselineprofile")
