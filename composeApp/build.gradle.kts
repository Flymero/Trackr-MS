// 根 Composable（重构计划 §2.2、§3.3）：KMP + Compose Multiplatform（Android + iosArm64 + iosSimulatorArm64）。
// TrackrRoot()：主题、底栏（3 个页签）、NavDisplay，把各 feature 的 entry 和 Koin 模块组装起来。不依赖 :androidApp。
// 第 17 步旧收藏页（第 22 步删）放 androidMain：它同时用 :feature:favorites 和 :feature:route 的 ViewModel。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.trackr.kmp.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    android {
        namespace = "com.trackr.composeapp"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:bus"))
            implementation(project(":feature:search"))
            implementation(project(":feature:stop"))
            implementation(project(":feature:line"))
            implementation(project(":feature:route"))
            implementation(project(":feature:favorites"))
            implementation(project(":feature:me"))
            api(project(":core:navigation"))
            implementation(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:ui"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.animation)
            implementation(libs.compose.mp.material3)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            // iOS 底栏图标用设计系统的 Compose 资源（Android 仍用原来的 material 图标）
            implementation(libs.compose.mp.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.material3)
            implementation(libs.androidx.compose.material.icons.core)
            implementation(libs.androidx.compose.material.icons.extended)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:testing"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(project(":core:testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }

    // iOS 侧：产出静态 framework 给 Xcode 宿主工程消费（build phase 调
    // embedAndSignAppleFrameworkForXcode）。目前仓库里还没有 iosApp 工程。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
}
