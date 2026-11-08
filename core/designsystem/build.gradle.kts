// 设计系统：KMP + Compose Multiplatform（Android + iosArm64 + iosSimulatorArm64）。
// 约定插件 trackr.kmp.library（build-logic/convention）统一配置 compileSdk / minSdk、JDK 工具链、
// Android 主机测试；trackr.kmp.compose 应用 CMP 与 Compose 编译器插件（稳定性配置与 reports）。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.trackr.kmp.compose)
}

kotlin {
    android {
        namespace = "com.trackr.core.designsystem"
        // Compose 资源（图标、通用文案）在 Android 上走 Android 资源 / assets 打进 APK。
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            // TrackrTheme(uiMode) 的参数类型 UiMode 在 :core:model（设置层也要用）
            api(project(":core:model"))
            implementation(libs.material.kolor)
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.material3)
            implementation(libs.compose.mp.components.resources)
            implementation(libs.compose.mp.ui.tooling.preview)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.robolectric)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }
}

compose.resources {
    // 生成的 Res 类：com.trackr.core.designsystem.resources.Res，其他模块也能用这些图标和文案。
    publicResClass = true
    packageOfResClass = "com.trackr.core.designsystem.resources"
}
