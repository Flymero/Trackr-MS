package com.trackr.app.di

import com.trackr.core.network.provider.meishan.MeishanTransitProvider
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.push.MeishanPushClientApi
import com.trackr.core.network.remote.meishan.push.NoMeishanPushClient
import org.koin.core.module.Module
import org.koin.dsl.module

/** All dependencies required by the shared line entries on an HTTP-only platform. */
internal fun meishanHttpOnlyModule(
    client: MeishanTransitClient,
    provider: MeishanTransitProvider
): Module = module {
    single { client }
    single { provider }
    single<MeishanPushClientApi> { NoMeishanPushClient }
}
