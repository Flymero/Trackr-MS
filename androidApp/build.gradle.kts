import java.io.RandomAccessFile
import java.util.Properties

// 约定插件 trackr.android.application（build-logic/convention）统一配置：
// compileSdk 37 / minSdk 26 / targetSdk 36、JDK 21 工具链、Java 17 字节码、单元测试（含 Android 资源、2 GB 堆），
// 以及 Compose 编译器的稳定性配置与 reports（./gradlew :androidApp:compileReleaseKotlin -PcomposeReports --rerun）。
plugins {
    alias(libs.plugins.trackr.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
    // 第 27 步：消费 :baselineprofile 生成的 Baseline Profile；另会加 nonMinifiedRelease、benchmarkRelease 两个构建类型（生成和测量用）
    alias(libs.plugins.androidx.baselineprofile)
    id("jacoco")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localOrEnv(name: String): String =
    System.getenv(name)?.trim().orEmpty().ifBlank { localProperties.getProperty(name, "").trim() }

// 高德 Android 平台 Key：与「PackageName + 签名 SHA-1」绑定。
// debug 的 PackageName 带 .debug 后缀，需在高德控制台为该包名配置对应的 Android Key。
val amapAndroidKeyFallback = localOrEnv("AMAP_ANDROID_KEY")
// 可分别配置 debug / release 的 Android Key，未配置时回落到通用值。
val amapAndroidKeyDebug = localOrEnv("AMAP_ANDROID_KEY_DEBUG").ifBlank { amapAndroidKeyFallback }
val amapAndroidKeyRelease = localOrEnv("AMAP_ANDROID_KEY_RELEASE").ifBlank { amapAndroidKeyFallback }
// 高德 Web 服务 Key（restapi.amap.com），与 Android 平台 Key 互不通用。
val amapWebServiceKey = localOrEnv("AMAP_WEB_KEY")

android {
    namespace = "com.trackr"

    defaultConfig {
        applicationId = "com.github.u5151561.trackr"
        resValue("string", "shortcut_target_package", applicationId!!)
        versionCode = 16
        versionName = "0.1.12"

        buildConfigField("String", "AMAP_WEB_KEY", "\"$amapWebServiceKey\"")
        // 第 9 步存储开关 USE_NEW_STORE 的默认值（true = Room + DataStore）；运行时可用 files/store_switch 覆盖，见 StoreSwitch
        buildConfigField(
            "boolean",
            "USE_NEW_STORE",
            providers.gradleProperty("trackr.useNewStore").getOrElse("true").toBooleanStrict().toString()
        )
        buildConfigField("String", "AMAP_ANDROID_KEY", "\"$amapAndroidKeyRelease\"")
        manifestPlaceholders["AMAP_ANDROID_KEY"] = amapAndroidKeyRelease

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
                ?: localProperties.getProperty("RELEASE_STORE_FILE")
            val storePwd = System.getenv("RELEASE_STORE_PASSWORD")
                ?: localProperties.getProperty("RELEASE_STORE_PASSWORD")
            val keyAliasStr = System.getenv("RELEASE_KEY_ALIAS")
                ?: localProperties.getProperty("RELEASE_KEY_ALIAS")
            val keyPwd = System.getenv("RELEASE_KEY_PASSWORD")
                ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")

            if (storeFilePath != null && storePwd != null && keyAliasStr != null && keyPwd != null) {
                val candidate1 = file(storeFilePath)
                val candidate2 = rootProject.file(storeFilePath.removePrefix("../"))
                storeFile = if (candidate1.exists()) candidate1 else candidate2
                storePassword = storePwd
                keyAlias = keyAliasStr
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "shortcut_target_package", "${defaultConfig.applicationId}$applicationIdSuffix")
            enableUnitTestCoverage = true
            buildConfigField("String", "AMAP_ANDROID_KEY", "\"$amapAndroidKeyDebug\"")
            manifestPlaceholders["AMAP_ANDROID_KEY"] = amapAndroidKeyDebug
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "AMAP_ANDROID_KEY", "\"$amapAndroidKeyRelease\"")
            manifestPlaceholders["AMAP_ANDROID_KEY"] = amapAndroidKeyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
    }

    // 两个 ABI 各出一个包，不把 56 MB 原生库塞进同一个 APK：arm64 约 51 MB、armv7 约 40 MB。
    // 产物在 build/outputs/apk/release/androidApp-<abi>-release.apk。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    packaging {
        jniLibs {
            // 16 KB 页设备要求 .so 以未压缩方式、按 16 KB 边界在 zip 内对齐；
            // false = 不压缩（AGP 8.5.1+ 会对未压缩 .so 做 16 KB zipalign）。
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:navigation"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    // 第 17 步：页面搬进 :composeApp / :feature:*；:core:ui 给出页面要的 Android 能力接口（LiveUpdateSettings）
    implementation(project(":composeApp"))
    implementation(project(":core:ui"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.google.material)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.client.core)
    implementation(libs.amap.search)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)
    // 第 26 步：桌面小组件迁 Glance，周期刷新走 WorkManager
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)
    // 第 27 步：安装后把 Baseline Profile 交给 ART 预编译（商店外安装也生效）
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(project(":core:testing"))
    // 第 17 步：页面和 ViewModel 搬进了功能模块；截图测试、Koin 图校验要直接用它们（只在测试里，正式代码只依赖 :composeApp）
    testImplementation(project(":feature:bus"))
    testImplementation(project(":feature:search"))
    testImplementation(project(":feature:stop"))
    testImplementation(project(":feature:line"))
    testImplementation(project(":feature:route"))
    testImplementation(project(":feature:favorites"))
    testImplementation(project(":feature:me"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testRuntimeOnly("org.robolectric:android-all:14-robolectric-10818077")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/*
 * 高德 Key 注入状态自检：./gradlew amapKeyStatus
 * 用于核对「开放平台注册的包名 + SHA-1」与当前构建产物是否一致。
 */
tasks.register("amapKeyStatus") {
    group = "help"
    description = "打印高德 Android/Web Key 注入情况与各变体包名"
    doLast {
        fun state(key: String) = if (key.isEmpty()) "❌ 未配置" else "✅ 已配置（${key.length} 位）"
        println(
            """
            |高德 Key 状态
            |  AMAP_ANDROID_KEY          : ${state(amapAndroidKeyFallback)}
            |  AMAP_ANDROID_KEY_DEBUG    : ${state(amapAndroidKeyDebug)}
            |  AMAP_ANDROID_KEY_RELEASE  : ${state(amapAndroidKeyRelease)}
            |  AMAP_WEB_KEY              : ${state(amapWebServiceKey)}
            |
            |包名（提交到高德开放平台时填写）
            |  debug   : com.github.u5151561.trackr.debug
            |  release : com.github.u5151561.trackr
            |
            |debug / release 包名不同，请分别配置匹配包名和签名 SHA-1 的 Android Key。
            |签名 SHA-1 可在 App 内「配置 Key」弹窗一键复制。
            """.trimMargin()
        )
    }
}

/*
 * 16 KB 页大小自检（Android 15+ / 16 KB 页设备）。
 *
 * 要求：ELF 的 PT_LOAD 段 p_align >= 0x4000，且 p_offset 与 p_vaddr 对 0x4000 同余。
 * 不满足时系统会弹出「Android 应用兼容性」警告，后续在这类设备上会直接加载失败。
 * 校验任务在 assemble* 之后自动执行，也可单独运行：
 *   ./gradlew checkDebug16kbPageAlignment
 *   ./gradlew checkRelease16kbPageAlignment
 */

/** 读取 ELF 的 PT_LOAD 段：(p_offset, p_vaddr, p_align) */
fun readElfLoadSegments(file: File): List<Triple<Long, Long, Long>> =
    RandomAccessFile(file, "r").use { raf ->
        val ident = ByteArray(16).also { raf.readFully(it) }
        require(ident[0] == 0x7F.toByte() && ident[1] == 'E'.code.toByte()) { "非 ELF 文件: $file" }
        val is64 = ident[4] == 2.toByte()
        fun u16(off: Long): Int {
            val b = ByteArray(2).also { raf.seek(off); raf.readFully(it) }
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }
        fun u32(off: Long): Long {
            val b = ByteArray(4).also { raf.seek(off); raf.readFully(it) }
            return (0 until 4).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) }
        }
        fun u64(off: Long): Long {
            val b = ByteArray(8).also { raf.seek(off); raf.readFully(it) }
            return (0 until 8).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) }
        }
        val phoff = if (is64) u64(0x20) else u32(0x1C)
        val phentsize = u16(if (is64) 0x36 else 0x2A)
        val phnum = u16(if (is64) 0x38 else 0x2C)
        (0 until phnum).mapNotNull { i ->
            val base = phoff + i.toLong() * phentsize
            if (u32(base) != 1L) null
            else if (is64) Triple(u64(base + 8), u64(base + 16), u64(base + 48))
            else Triple(u32(base + 4), u32(base + 8), u32(base + 28))
        }
    }

/** 16 KB 页兼容性：所有 PT_LOAD 段都按 16 KB 对齐，且 offset/vaddr 同余 */
fun File.is16kbPageAligned(): Boolean {
    val pageSize = 0x4000L
    val segments = readElfLoadSegments(this)
    return segments.isNotEmpty() && segments.all { (offset, vaddr, align) ->
        align >= pageSize && offset % pageSize == vaddr % pageSize
    }
}

/** 32 位 ABI 不会运行在 16 KB 页设备上，只校验 64 位库 */
val CHECKED_ABIS = setOf("arm64-v8a", "x86_64")

listOf("Debug", "Release").forEach { variant ->
    val mergedDir = layout.buildDirectory.dir(
        "intermediates/merged_native_libs/${variant.lowercase()}/merge${variant}NativeLibs/out/lib"
    )
    val checkTask = tasks.register("check${variant}16kbPageAlignment") {
        group = "verification"
        description = "校验 ${variant} 变体合并后的 .so 是否满足 16 KB 页对齐"
        // 用任务名字符串声明依赖：tasks.matching {} 在解析依赖时会实例化全部任务，
        // 而 AGP 9.5 的 generateReleaseComposePreviewRunfiles 在 release（无单元测试）上一实例化就报错。
        dependsOn("merge${variant}NativeLibs")
        inputs.dir(mergedDir).withPropertyName("mergedNativeLibs")
        outputs.upToDateWhen { false }

        doLast {
            val root = mergedDir.get().asFile
            val libs = if (root.exists()) {
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "so" && it.parentFile?.name in CHECKED_ABIS }
                    .sortedBy { it.path }
                    .toList()
            } else {
                emptyList()
            }
            if (libs.isEmpty()) {
                logger.lifecycle("check${variant}16kbPageAlignment: 未找到 64 位共享库，跳过")
                return@doLast
            }
            libs.forEach { logger.lifecycle("  ✔ ${it.parentFile.name}/${it.name}") }

            val bad = libs.filterNot { it.is16kbPageAligned() }
            if (bad.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("以下共享库未按 16 KB 页对齐，会在 16 KB 页大小的设备上加载失败：")
                        bad.forEach {
                            val aligns = readElfLoadSegments(it)
                                .joinToString { seg -> "0x" + seg.third.toString(16) }
                            appendLine("  ✘ ${it.parentFile.name}/${it.name}  (p_align: $aligns)")
                        }
                        append("请将对应依赖升级到 16 KB 兼容版本（自编译 .so 需 NDK r28+）。")
                    }
                )
            }
            logger.lifecycle("check${variant}16kbPageAlignment: ${libs.size} 个 64 位共享库全部通过")
        }
    }
    tasks.named { it == "assemble${variant}" }.configureEach { finalizedBy(checkTask) }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*"
    )
    val debugTree = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
        exclude(fileFilter)
    }
    val kotlinTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree, kotlinTree))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}
