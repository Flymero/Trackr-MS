package com.trackr.data.repository

import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.data.repository.RealtimeRepository
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

/** The production Android DI wrapper must forward the stream, not inherit emptyFlow(). */
@RunWith(RobolectricTestRunner::class)
class TransitRepositoryFlowContractTest {
    @Test fun injectedRealtimeUsesTheUnderlyingSharedLineDetailStream() {
        ApplicationProvider.getApplicationContext<TrackrApp>()
        val repository = GlobalContext.get().get<ITransitRepository>() as TransitRepository
        val field = TransitRepository::class.java.getDeclaredField("realtime").apply { isAccessible = true }
        val underlying = field.get(repository) as RealtimeRepository
        assertSame(underlying.lineDetailUpdates, repository.lineDetailUpdates)
        assertSame(repository.lineDetailUpdates, GlobalContext.get().get<RealtimeRepository>().lineDetailUpdates)
    }
}
