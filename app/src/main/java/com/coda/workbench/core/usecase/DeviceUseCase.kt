package com.coda.workbench.core.usecase

import com.coda.workbench.core.rules.TextRules
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.local.DeviceAliasEntity
import com.coda.workbench.data.local.DeviceEntity
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class DeviceUseCase(
    private val database: CodaDatabase,
    private val clock: Clock,
) {
    fun observeAll(): Flow<List<DeviceEntity>> = database.deviceDao().observeAll()

    fun observeRecent(): Flow<List<DeviceEntity>> = database.deviceDao().observeRecent()

    fun observeAliases(deviceId: String): Flow<List<DeviceAliasEntity>> =
        database.deviceAliasDao().observeForDevice(deviceId)

    suspend fun create(name: String): String {
        require(name.isNotBlank()) { "设备名称不能为空" }
        val normalized = TextRules.normalize(name)
        if (database.deviceDao().findByNormalizedName(normalized) != null) error("设备名称已存在")
        val now = clock.millis()
        val entity = DeviceEntity(
            id = UUID.randomUUID().toString(),
            name = normalized,
            normalizedName = normalized,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        database.deviceDao().insert(entity)
        return entity.id
    }

    suspend fun rename(id: String, name: String) {
        require(name.isNotBlank()) { "设备名称不能为空" }
        val normalized = TextRules.normalize(name)
        if (database.deviceDao().findByNormalizedNameExcluding(normalized, id) != null) error("设备名称已存在")
        database.deviceDao().updateName(id, normalized, normalized, clock.millis())
    }

    suspend fun addAlias(deviceId: String, alias: String) {
        require(alias.isNotBlank()) { "别名不能为空" }
        val normalized = TextRules.normalize(alias)
        if (database.deviceAliasDao().findByValue(deviceId, normalized) != null) error("该别名已存在")
        database.deviceAliasDao().insert(
            DeviceAliasEntity(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                alias = normalized,
                normalizedAlias = normalized,
                createdAt = clock.millis(),
                updatedAt = clock.millis(),
            ),
        )
    }

    suspend fun removeAlias(deviceId: String, alias: String) {
        database.deviceAliasDao().deleteByValue(deviceId, alias)
    }

    suspend fun setActive(id: String, active: Boolean) {
        database.deviceDao().setActive(id, active, clock.millis())
    }
}
