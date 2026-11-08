package com.trackr.ui.screens.me

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次性提示（计划 §2.1 UDF）：放在 UiState 的 `userMessages` 里，页面显示完回调 `MessageShown(id)` 再删。
 * 页面用 [MeMessageHost] 显示（第 34 步：`UserMessageHost`，长辈版换常驻提示）。
 */
data class UserMessage(val id: Long, val text: String)

/** 往列表里加一条提示，id 递增 */
internal fun List<UserMessage>.plusMessage(text: String): List<UserMessage> =
    this + UserMessage(id = (maxOfOrNull { it.id } ?: 0L) + 1, text = text)

/**
 * 「我的」页签里跨页的提示（Koin single）：编辑页保存 / 删除后马上返回，提示交给「我的」页显示（§3.5：结果走共享的状态，不走回调）。
 */
class MeNotices {
    private val _messages = MutableStateFlow<List<UserMessage>>(emptyList())
    val messages: StateFlow<List<UserMessage>> = _messages.asStateFlow()

    private var nextId = 1L

    fun post(text: String) {
        _messages.update { it + UserMessage(id = nextId++, text = text) }
    }

    fun consume(id: Long) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }
}

/**
 * 把 [messages] 的第一条交给 [UserMessageHost] 显示（标准版 Snackbar，长辈版点「知道了」才关），
 * 显示完回调 [onShown]，再显示下一条。放在 Scaffold 的 `snackbarHost` 槽里
 */
@Composable
internal fun MeMessageHost(
    messages: List<UserMessage>,
    modifier: Modifier = Modifier,
    onShown: (Long) -> Unit
) {
    UserMessageHost(
        modifier = modifier,
        message = messages.firstOrNull()?.let { HostMessage(key = it.id, text = it.text) },
        onResult = { key, _ -> onShown(key as Long) }
    )
}
