package com.trackr.data.local

import android.content.Context
import com.trackr.BuildConfig
import java.io.File

/**
 * 运行时开关 USE_NEW_STORE（重构计划 §2.4、风险 R1）。
 *
 * - 默认值：`BuildConfig.USE_NEW_STORE`（Gradle 属性 `-Ptrackr.useNewStore=false` 可以打一个默认走旧存储的包）。
 * - 运行时覆盖：`files/store_switch` 文件，内容 `old` 走旧存储（SharedPreferences），`new` 走新存储（Room + DataStore），
 *   文件不存在或内容认不出时用默认值。改完要杀进程重开才生效：
 *
 *   ```
 *   adb shell run-as com.github.u5151561.trackr.ms sh -c 'echo old > files/store_switch'
 *   adb shell am force-stop com.github.u5151561.trackr.ms
 *   ```
 *   恢复：把 `old` 换成 `new`，或 `rm files/store_switch`。
 *
 * 「代数」：每次以旧存储启动，`trackr_store_switch` 里的 `old_store_sessions` +1。新存储里记着上次从旧文件导入时的代数，
 * 和这里不同就重新导入一次，这样切回新存储时能带上用旧存储期间的改动（LegacyLibraryMigration）。
 * `store_switch` 覆盖文件仍只属于本机；记录代数的 SharedPreferences 会参与备份，
 * 这样换机恢复时新库的导入代数不会落后于旧文件。
 */
object StoreSwitch {
    const val OVERRIDE_FILE = "store_switch"
    const val PREFS_NAME = "trackr_store_switch"
    private const val KEY_OLD_STORE_SESSIONS = "old_store_sessions"

    /** 这次启动用不用新存储 */
    fun useNewStore(context: Context): Boolean {
        val override = runCatching {
            File(context.filesDir, OVERRIDE_FILE).takeIf { it.exists() }?.readText()?.trim()?.lowercase()
        }.getOrNull()
        return when (override) {
            "old" -> false
            "new" -> true
            else -> BuildConfig.USE_NEW_STORE
        }
    }

    /** 当前代数 = 以旧存储启动过的次数 */
    fun generation(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_OLD_STORE_SESSIONS, 0)

    /** 这次以旧存储启动：代数 +1（同步写盘，下次启动一定能看到） */
    fun recordOldStoreSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OLD_STORE_SESSIONS, prefs.getInt(KEY_OLD_STORE_SESSIONS, 0) + 1).commit()
    }
}
