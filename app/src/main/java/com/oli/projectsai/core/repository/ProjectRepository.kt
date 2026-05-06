package com.oli.projectsai.core.repository

import com.oli.projectsai.core.db.dao.ChatDao
import com.oli.projectsai.core.db.dao.MessageDao
import com.oli.projectsai.core.db.dao.ProjectDao
import com.oli.projectsai.core.db.dao.QuickActionDao
import com.oli.projectsai.core.db.entity.Project
import com.oli.projectsai.core.db.entity.QuickAction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val quickActionDao: QuickActionDao
) {
    fun getAllProjects(): Flow<List<Project>> = projectDao.getAll()

    fun getSecretProjects(): Flow<List<Project>> = projectDao.getSecretAll()

    suspend fun getProject(id: Long): Project? = projectDao.getById(id)

    fun getProjectFlow(id: Long): Flow<Project?> = projectDao.getByIdFlow(id)

    suspend fun createProject(project: Project): Long = projectDao.insert(project)

    suspend fun updateProject(project: Project) = projectDao.update(
        project.copy(updatedAt = System.currentTimeMillis())
    )

    suspend fun deleteProject(project: Project) {
        // Soft-delete the project and cascade to its chats, their messages, and
        // its quick actions — so each table's push path sees a fresh tombstone
        // and the UI updates immediately. The server also cascades on its side,
        // but we can't rely on that until the next sync round-trip.
        val now = System.currentTimeMillis()
        val chats = chatDao.getAllForSync().filter { it.projectId == project.id }
        chats.forEach { messageDao.softDeleteByChat(it.id, now) }
        chatDao.softDeleteByProject(project.id, now)
        quickActionDao.softDeleteByProject(project.id, now)
        projectDao.softDelete(project.id, now)
    }

    suspend fun updateManualContext(projectId: Long, context: String) =
        projectDao.updateManualContext(projectId, context)

    suspend fun updateMemory(projectId: Long, memory: String) =
        projectDao.updateMemory(projectId, memory)

    suspend fun updatePinnedMemories(projectId: Long, pinned: List<String>) =
        projectDao.updatePinnedMemories(projectId, pinned)

    fun getQuickActions(projectId: Long): Flow<List<QuickAction>> =
        quickActionDao.getByProject(projectId)

    suspend fun createQuickAction(action: QuickAction): Long = quickActionDao.insert(action)

    suspend fun updateQuickAction(action: QuickAction) = quickActionDao.update(action)

    suspend fun deleteQuickAction(action: QuickAction) =
        quickActionDao.softDelete(action.id, System.currentTimeMillis())
}
