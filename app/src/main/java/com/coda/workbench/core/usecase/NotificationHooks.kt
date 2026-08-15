package com.coda.workbench.core.usecase

/**
 * 通知调度挂钩（事务提交后执行；调度失败不回滚业务数据）。
 * 与 FaultNotificationScheduler 同模式：核心层只依赖接口，平台层提供真实实现。
 */
interface HandoverNotificationScheduler {
    suspend fun reconcile(handoverId: String)
}

object NoOpHandoverNotificationScheduler : HandoverNotificationScheduler {
    override suspend fun reconcile(handoverId: String) = Unit
}

interface ShiftScheduleNotificationTrigger {
    suspend fun reconcileAll()
}

object NoOpShiftScheduleNotificationTrigger : ShiftScheduleNotificationTrigger {
    override suspend fun reconcileAll() = Unit
}
