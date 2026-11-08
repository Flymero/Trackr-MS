// 数据层：KMP（Android + iosArm64 + iosSimulatorArm64）。
// 拆开的仓库接口（设置 / 收藏 / 实时查询 / 搜索历史 / 定位 / 路线规划）、按数据源选 Provider 的协调器、
// 不依赖平台存储的实现（实时查询、定位）；第 9 步加存储层（local/：Room + DataStore 的 Store、旧数据迁移）。重构计划 §2.4、§2.5。
plugins {
    alias(libs.plugins.trackr.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:domain"))
            api(project(":core:network"))
            // 第 9 步：存储（Room + DataStore）
            api(project(":core:database"))
            api(project(":core:datastore"))
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.ktor.client.mock)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }
}
