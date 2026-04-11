package com.oli.projectsai.data.repository

import com.oli.projectsai.data.db.dao.ProjectDao
import com.oli.projectsai.data.db.dao.QuickActionDao
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.db.entity.QuickAction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val quickActionDao: QuickActionDao
) {
    fun getAllProjects(): Flow<List<Project>> = projectDao.getAll()

    suspend fun getProject(id: Long): Project? = projectDao.getById(id)

    fun getProjectFlow(id: Long): Flow<Project?> = projectDao.getByIdFlow(id)

    suspend fun createProject(project: Project): Long = projectDao.insert(project)

    suspend fun updateProject(project: Project) = projectDao.update(
        project.copy(updatedAt = System.currentTimeMillis())
    )

    suspend fun deleteProject(project: Project) = projectDao.delete(project)

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

    suspend fun deleteQuickAction(action: QuickAction) = quickActionDao.delete(action)
}
