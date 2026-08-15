package com.coda.workbench.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.coda.workbench.data.local.CodaDatabase
import com.coda.workbench.data.repository.FaultDraftRepository
import com.coda.workbench.data.repository.AttendanceRepository
import com.coda.workbench.data.repository.DeviceRepository
import com.coda.workbench.core.usecase.FaultUseCase
import com.coda.workbench.core.usecase.HomeUseCase
import com.coda.workbench.core.usecase.FaultEntryUseCase
import com.coda.workbench.core.usecase.FaultDetailUseCase
import com.coda.workbench.core.usecase.ManualWorkUseCase
import com.coda.workbench.core.usecase.HandoverUseCase
import com.coda.workbench.core.usecase.DeviceUseCase
import com.coda.workbench.core.usecase.AttendanceUseCase
import com.coda.workbench.core.usecase.AttendanceQueryUseCase
import com.coda.workbench.core.usecase.ShiftScheduleUseCase
import com.coda.workbench.core.usecase.ShiftScheduleQueryUseCase
import com.coda.workbench.core.usecase.SearchUseCase
import com.coda.workbench.core.usecase.BackupUseCase
import com.coda.workbench.data.repository.BackupRepository
import com.coda.workbench.data.repository.FaultDetailRepository
import com.coda.workbench.data.repository.HomeRepository
import com.coda.workbench.data.repository.SearchRepository
import com.coda.workbench.platform.BackupFileStore
import com.coda.workbench.platform.AlarmGateway
import com.coda.workbench.platform.AndroidAlarmGateway
import com.coda.workbench.platform.AndroidNotificationPoster
import com.coda.workbench.platform.NotificationMaintenance
import com.coda.workbench.platform.NotificationPoster
import com.coda.workbench.platform.NotificationScheduler
import com.coda.workbench.platform.NotificationSettingsStore
import com.coda.workbench.platform.NotificationSettingsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CodaDatabase =
        CodaDatabase.build(context)

    @Provides
    @Singleton
    fun provideDeviceDao(database: CodaDatabase) = database.deviceDao()

    @Provides
    @Singleton
    fun provideDeviceAliasDao(database: CodaDatabase) = database.deviceAliasDao()

    @Provides
    @Singleton
    fun provideFaultRecordDao(database: CodaDatabase) = database.faultRecordDao()

    @Provides
    @Singleton
    fun provideFaultProcessingDao(database: CodaDatabase) = database.faultProcessingDao()

    @Provides
    @Singleton
    fun provideWorkLogDao(database: CodaDatabase) = database.workLogDao()

    @Provides
    @Singleton
    fun provideHandoverItemDao(database: CodaDatabase) = database.handoverItemDao()

    @Provides
    @Singleton
    fun provideAttendanceDao(database: CodaDatabase) = database.attendanceDao()

    @Provides
    @Singleton
    fun provideMonthlyShiftPlanDao(database: CodaDatabase) = database.monthlyShiftPlanDao()

    @Provides
    @Singleton
    fun provideShiftSlotDao(database: CodaDatabase) = database.shiftSlotDao()

    @Provides
    @Singleton
    fun provideBackupImportLogDao(database: CodaDatabase) = database.backupImportLogDao()

    @Provides
    @Singleton
    fun provideFaultDraftRepository(database: CodaDatabase, clock: Clock): FaultDraftRepository =
        FaultDraftRepository(database, clock)

    @Provides
    @Singleton
    fun provideAttendanceRepository(database: CodaDatabase, clock: Clock): AttendanceRepository =
        AttendanceRepository(database, clock = clock)

    @Provides
    @Singleton
    fun provideDeviceRepository(database: CodaDatabase, clock: Clock): DeviceRepository =
        DeviceRepository(database.deviceDao(), clock)

    @Provides
    @Singleton
    fun provideFaultUseCase(
        database: CodaDatabase,
        clock: Clock,
        attendanceRepository: AttendanceRepository,
        notificationScheduler: NotificationScheduler,
    ): FaultUseCase = FaultUseCase(
        database,
        clock,
        attendanceRepository = attendanceRepository,
        notificationScheduler = notificationScheduler,
    )

    @Provides
    @Singleton
    fun provideHomeRepository(database: CodaDatabase, clock: Clock): HomeRepository =
        HomeRepository(database, clock)

    @Provides
    @Singleton
    fun provideHomeUseCase(repository: HomeRepository): HomeUseCase = HomeUseCase(repository)

    @Provides
    @Singleton
    fun provideFaultEntryUseCase(
        drafts: FaultDraftRepository,
        devices: DeviceRepository,
        clock: Clock,
    ): FaultEntryUseCase = FaultEntryUseCase(drafts, devices, clock)

    @Provides
    @Singleton
    fun provideFaultDetailRepository(database: CodaDatabase): FaultDetailRepository =
        FaultDetailRepository(database)

    @Provides
    @Singleton
    fun provideFaultDetailUseCase(
        repository: FaultDetailRepository,
        faultUseCase: FaultUseCase,
        database: CodaDatabase,
        clock: Clock,
    ): FaultDetailUseCase = FaultDetailUseCase(repository, faultUseCase, database, clock)

    @Provides
    @Singleton
    fun provideManualWorkUseCase(
        drafts: FaultDraftRepository,
        database: CodaDatabase,
        clock: Clock,
    ): ManualWorkUseCase = ManualWorkUseCase(drafts, database, clock)

    @Provides
    @Singleton
    fun provideDeviceUseCase(database: CodaDatabase, clock: Clock): DeviceUseCase =
        DeviceUseCase(database, clock)

    @Provides
    @Singleton
    fun provideHandoverUseCase(
        database: CodaDatabase,
        clock: Clock,
        notificationScheduler: NotificationScheduler,
    ): HandoverUseCase = HandoverUseCase(database, clock, notificationScheduler = notificationScheduler)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("coda_preferences")
        }

    // ---- M5 出勤/排班/通知 ----

    @Provides
    @Singleton
    fun provideAlarmGateway(@ApplicationContext context: Context): AlarmGateway =
        AndroidAlarmGateway(context)

    @Provides
    @Singleton
    fun provideNotificationPoster(@ApplicationContext context: Context): NotificationPoster =
        AndroidNotificationPoster(context)

    @Provides
    @Singleton
    fun provideNotificationSettingsStore(dataStore: DataStore<Preferences>): NotificationSettingsStore =
        NotificationSettingsStore(dataStore)

    @Provides
    @Singleton
    fun provideNotificationScheduler(
        @ApplicationContext context: Context,
        database: CodaDatabase,
        clock: Clock,
        settings: NotificationSettingsStore,
        gateway: AlarmGateway,
    ): NotificationScheduler = NotificationScheduler(context, database, clock, settings, gateway = gateway)

    @Provides
    @Singleton
    fun provideNotificationMaintenance(
        database: CodaDatabase,
        clock: Clock,
        settings: NotificationSettingsStore,
        poster: NotificationPoster,
    ): NotificationMaintenance = NotificationMaintenance(database, clock, settings = settings, poster = poster)

    @Provides
    @Singleton
    fun provideNotificationSettingsUseCase(
        @ApplicationContext context: Context,
        settings: NotificationSettingsStore,
        scheduler: NotificationScheduler,
    ): NotificationSettingsUseCase = NotificationSettingsUseCase(context, settings, scheduler)

    @Provides
    @Singleton
    fun provideAttendanceUseCase(database: CodaDatabase, clock: Clock): AttendanceUseCase =
        AttendanceUseCase(database, clock)

    @Provides
    @Singleton
    fun provideAttendanceQueryUseCase(database: CodaDatabase): AttendanceQueryUseCase =
        AttendanceQueryUseCase(database)

    @Provides
    @Singleton
    fun provideShiftScheduleUseCase(
        database: CodaDatabase,
        clock: Clock,
        scheduler: NotificationScheduler,
    ): ShiftScheduleUseCase = ShiftScheduleUseCase(database, clock, notificationTrigger = scheduler)

    @Provides
    @Singleton
    fun provideShiftScheduleQueryUseCase(database: CodaDatabase, clock: Clock): ShiftScheduleQueryUseCase =
        ShiftScheduleQueryUseCase(database, clock)

    // ---- M6 搜索 ----

    @Provides
    @Singleton
    fun provideSearchRepository(database: CodaDatabase): SearchRepository =
        SearchRepository(database)

    @Provides
    @Singleton
    fun provideSearchUseCase(repository: SearchRepository): SearchUseCase =
        SearchUseCase(repository)

    // ---- M7 备份恢复 ----

    @Provides
    @Singleton
    fun provideBackupFileStore(@ApplicationContext context: Context): BackupFileStore =
        BackupFileStore(context)

    @Provides
    @Singleton
    fun provideBackupRepository(database: CodaDatabase): BackupRepository =
        BackupRepository(database)

    @Provides
    @Singleton
    fun provideBackupUseCase(
        repository: BackupRepository,
        fileStore: BackupFileStore,
        clock: Clock,
        scheduler: NotificationScheduler,
    ): BackupUseCase = BackupUseCase(
        repository = repository,
        fileStore = fileStore,
        clock = clock,
        appVersion = com.coda.workbench.BuildConfig.VERSION_NAME,
        notificationTrigger = scheduler,
    )
}
