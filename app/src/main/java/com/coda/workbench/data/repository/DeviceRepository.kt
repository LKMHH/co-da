package com.coda.workbench.data.repository

import com.coda.workbench.core.rules.TextRules
import com.coda.workbench.data.local.DeviceDao
import com.coda.workbench.data.local.DeviceEntity
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class DeviceRepository(
    private val dao: DeviceDao,
    private val clock: Clock,
) {
    suspend fun create(name: String, idFactory: () -> String = { UUID.randomUUID().toString() }): DeviceEntity {
        val normalized = TextRules.normalize(name)
        require(normalized.isNotEmpty()) { "device name must not be blank" }
        val now = clock.millis()
        val entity = DeviceEntity(
            id = idFactory(),
            name = normalized,
            normalizedName = normalized,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(entity)
        return entity
    }

    suspend fun findById(id: String): DeviceEntity? = dao.findById(id)

    fun observeRecent(): Flow<List<DeviceEntity>> = dao.observeRecent()
}
