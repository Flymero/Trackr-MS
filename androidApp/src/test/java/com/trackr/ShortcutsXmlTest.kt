package com.trackr

import android.content.Intent
import android.content.res.XmlResourceParser
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.deeplink.DeepLinkIntents
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 静态快捷方式（`res/xml/shortcuts.xml`）。issue #50：targetPackage 用 `@string` 引用时，ColorOS / MIUI 桌面
 * 自己读 XML 拿到的是 "@0x7f…"，长按图标点乘车码启动失败。这里按原始属性值读，确认写的是字面量。
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutsXmlTest {

    private data class ShortcutIntent(val action: String?, val data: String?, val targetPackage: String?, val targetClass: String?)

    private fun readShortcutIntents(): List<ShortcutIntent> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parser = context.resources.getXml(R.xml.shortcuts)
        val result = mutableListOf<ShortcutIntent>()
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "intent") {
                fun raw(name: String) = parser.getAttributeValue(ANDROID_NS, name)
                result += ShortcutIntent(raw("action"), raw("data"), raw("targetPackage"), raw("targetClass"))
            }
        }
        return result
    }

    @Test
    fun `targetPackage 和 targetClass 是字面量且指向本包的 MainActivity`() {
        val intents = readShortcutIntents()
        assertEquals(2, intents.size)
        intents.forEach {
            assertEquals(BuildConfig.APPLICATION_ID, it.targetPackage)
            assertEquals(MainActivity::class.java.name, it.targetClass)
            assertEquals(Intent.ACTION_VIEW, it.action)
        }
    }

    @Test
    fun `两个快捷方式分别解析成支付宝和云闪付乘车码`() {
        val providers = readShortcutIntents().map { shortcut ->
            val intent = Intent(shortcut.action, Uri.parse(shortcut.data))
            (DeepLinkIntents.parse(intent) as DeepLink.TransitCode).provider
        }
        assertEquals(listOf(TransitCodeProvider.ALIPAY, TransitCodeProvider.UNIONPAY), providers)
    }

    @Test
    fun `debug 版 shortcuts xml 除包名外和 main 一致`() {
        val main = File("src/main/res/xml/shortcuts.xml").readText()
        val debug = File("src/debug/res/xml/shortcuts.xml").readText()
        assertEquals(
            main.replace("\"$RELEASE_PACKAGE\"", "\"$RELEASE_PACKAGE.debug\""),
            debug,
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val RELEASE_PACKAGE = "com.github.u5151561.trackr"
    }
}
