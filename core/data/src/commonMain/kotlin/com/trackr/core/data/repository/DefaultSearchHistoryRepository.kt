package com.trackr.core.data.repository

import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.local.TransitLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared persistent history: latest first, case-insensitive, at most 20 entries. */
class DefaultSearchHistoryRepository private constructor(
    private val store: TransitLocalStore,
    private val writer: SerialWriter,
    initial: List<String>
) : SearchHistoryRepository {
    private val mutex = Mutex()
    private val history = MutableStateFlow(initial)
    override val searchHistory = history.asStateFlow()

    override suspend fun addSearchHistory(query: String) {
        val value = query.trim()
        if (value.isBlank()) return
        mutex.withLock {
            history.value = (listOf(value) + history.value.filterNot { it.equals(value, true) }).take(20)
            val snapshot = history.value
            writer.enqueue { store.saveSearchHistory(snapshot) }.join()
        }
    }

    override suspend fun removeSearchHistory(query: String) = mutex.withLock {
        history.value = history.value.filterNot { it.equals(query.trim(), true) }
        val snapshot = history.value
        writer.enqueue { store.saveSearchHistory(snapshot) }.join()
    }

    override suspend fun clearSearchHistory() = mutex.withLock {
        history.value = emptyList()
        writer.enqueue { store.clearSearchHistory() }.join()
    }

    companion object {
        suspend fun create(store: TransitLocalStore, writer: SerialWriter) =
            DefaultSearchHistoryRepository(store, writer, store.load().searchHistory)
    }
}
