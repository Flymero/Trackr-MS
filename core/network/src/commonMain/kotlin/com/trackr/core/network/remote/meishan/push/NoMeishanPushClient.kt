package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A platform without TCP support keeps HTTP polling active without starting reconnect jobs. */
object NoMeishanPushClient : MeishanPushClientApi {
    override val state = MutableStateFlow(MeishanPushState.IDLE).asStateFlow()
    override val frames = MutableSharedFlow<MeishanPushFrame>().asSharedFlow()
    override suspend fun acquire(tag: String) = Unit
    override suspend fun release(tag: String) = Unit
}
