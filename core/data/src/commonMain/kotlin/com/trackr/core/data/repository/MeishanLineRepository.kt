package com.trackr.core.data.repository

import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.network.remote.common.suspendRunCatching
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * 眉山官方线路列表仓库（眉山特化：`/line/list` 全量线路，doc/契约 §4.2）。
 * 第三方源（车来了 / 掌上公交）没有全城线路列表接口，这里走官方后台。
 */
interface MeishanLineRepository {

    /**
     * 全量线路列表。会话内按区域缓存（线路列表不常变）；
     * [forceRefresh] 为 true 时强制重新拉取。失败返回 [Result.failure]，调用方展示错误态。
     */
    suspend fun getLines(area: MeishanArea, forceRefresh: Boolean = false): Result<List<MeishanLine>>
}

/**
 * [MeishanLineRepository] 的实现：官方后台无长期支持承诺（HTTP 明文、旧客户端服务），
 * 失败只影响「线路」页签的错误态，不影响其他数据源。
 */
class DefaultMeishanLineRepository(
    private val client: MeishanTransitClient
) : MeishanLineRepository {

    private val lock = SynchronizedObject()
    private val cache = HashMap<MeishanArea, List<MeishanLine>>()

    override suspend fun getLines(area: MeishanArea, forceRefresh: Boolean): Result<List<MeishanLine>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                synchronized(lock) { cache[area] }?.let { return@withContext Result.success(it) }
            }
            val result = suspendRunCatching { client.getLineList(area, forceRefresh) }
            result.onSuccess { lines ->
                synchronized(lock) { cache[area] = lines }
            }
            result
        }
}
