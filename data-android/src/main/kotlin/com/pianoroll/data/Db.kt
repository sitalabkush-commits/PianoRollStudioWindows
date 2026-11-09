package com.pianoroll.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ProjectRepository
import com.pianoroll.core.model.ProjectSummary
import kotlinx.serialization.json.Json

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val json: String,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}

class RoomProjectRepository(private val dao: ProjectDao) : ProjectRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun observeAll(): Flow<List<ProjectSummary>> =
        dao.observeAll().map { list -> list.map { ProjectSummary(it.id, it.name, it.updatedAt) } }

    override suspend fun load(id: String): Project? =
        dao.get(id)?.let { json.decodeFromString(Project.serializer(), it.json) }

    override suspend fun save(project: Project) = dao.upsert(
        ProjectEntity(project.id, project.name, System.currentTimeMillis(), json.encodeToString(Project.serializer(), project))
    )

    override suspend fun delete(id: String) = dao.delete(id)
}
