package com.trackr.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

/**
 * 页面转场：进栈、出栈、预测返回是横向滑动 + 淡入淡出（300ms）；
 * 页签根页之间的切换用 220ms 淡入淡出——页签是平级关系，左右滑动会有「进子页」的错觉。
 *
 * （历史：第 16 步前旧代码有一段页签切换淡入淡出的判断，但条件写错永远为假，页签切换一直是
 * 滑动，当时按「无用户可见变化」删掉；现在用场景栈顶 key 正确判定，真正改成淡入淡出。）
 */
object TrackrTransitions {
    private const val SLIDE_MILLIS = 300
    private const val TAB_FADE_MILLIS = 220

    val push: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
        if (isTabRootSwitch()) tabFade() else slidePush()
    }

    val pop: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
        if (isTabRootSwitch()) tabFade() else popSlide()
    }

    val predictivePop: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = { _ ->
        // 预测返回手势只发生在页内（页签根页的返回是退出应用），固定走 pop 滑动
        popSlide()
    }

    /**
     * 是否为「页签根页 ↔ 页签根页」的切换：两个场景的栈顶都是页签根页且不是同一个。
     * 切页签时 NavDisplay 显示的是目标页签那条栈，栈顶就是它的根页。
     */
    private fun AnimatedContentTransitionScope<Scene<NavKey>>.isTabRootSwitch(): Boolean {
        val from = initialState.entries.lastOrNull()?.contentKey
        val to = targetState.entries.lastOrNull()?.contentKey
        return isTabRootSwitch(from, to)
    }

    // Navigation 3 derives contentKey from the key; it is not the ScreenKey itself.
    private val tabContentKeys = TopLevel.tabs.map { NavEntry<NavKey>(it) {}.contentKey }.toSet()

    internal fun isTabRootSwitch(from: Any?, to: Any?): Boolean =
        from != to && from in tabContentKeys && to in tabContentKeys

    private fun tabFade(): ContentTransform =
        fadeIn(animationSpec = tween(TAB_FADE_MILLIS)) togetherWith
            fadeOut(animationSpec = tween(TAB_FADE_MILLIS))

    private fun slidePush(): ContentTransform =
        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(SLIDE_MILLIS)) +
            fadeIn(animationSpec = tween(SLIDE_MILLIS))) togetherWith
            (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(SLIDE_MILLIS)) +
                fadeOut(animationSpec = tween(SLIDE_MILLIS)))

    private fun popSlide(): ContentTransform =
        (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(SLIDE_MILLIS)) +
            fadeIn(animationSpec = tween(SLIDE_MILLIS))) togetherWith
            (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(SLIDE_MILLIS)) +
                fadeOut(animationSpec = tween(SLIDE_MILLIS)))
}
