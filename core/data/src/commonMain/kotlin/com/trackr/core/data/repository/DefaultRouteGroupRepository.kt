package com.trackr.core.data.repository

import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.data.repository.IRouteGroupRepository
import kotlinx.coroutines.flow.StateFlow

/** Adapts the shared favorites owner to the existing route ViewModels. */
class DefaultRouteGroupRepository(private val favorites: DefaultFavoritesRepository) : IRouteGroupRepository {
    override val groups: StateFlow<List<TransitGroup>> get() = favorites.routes

    override val favoriteGroups: StateFlow<List<FavoriteGroup>> get() = favorites.favoriteGroups

    override suspend fun addGroup(group: TransitGroup) = favorites.saveRoute(group)

    override suspend fun updateGroup(group: TransitGroup) = favorites.updateRoute(group)

    override suspend fun deleteGroup(groupId: String) = favorites.removeRoute(groupId)

    override suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String?,
        newCategory: GroupCategory?,
        customGroupId: String?
    ) = favorites.saveOrConvertTemporaryRoute(groupId, newName, newCategory, customGroupId)

    override suspend fun reorderGroups(groupOrderIds: List<String>) = favorites.reorderRoutes(groupOrderIds)

    override fun getGroupById(groupId: String): TransitGroup? = favorites.getRoute(groupId)

    override suspend fun createFavoriteGroup(name: String): FavoriteGroup = favorites.createGroup(name)

    override suspend fun renameFavoriteGroup(groupId: String, newName: String) = favorites.renameGroup(groupId, newName)

    override suspend fun deleteFavoriteGroup(groupId: String) = favorites.deleteGroup(groupId)

    override suspend fun reorderFavoriteGroups(groupIds: List<String>) = favorites.reorderGroups(groupIds)

    override suspend fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) =
        favorites.addCandidateLineToLeg(groupId, legIndex, candidateLine)

}
