package com.cloudmonitor.di

import android.content.Context
import androidx.room.Room
import com.cloudmonitor.BuildConfig
import com.cloudmonitor.data.local.EventLogDao
import com.cloudmonitor.data.local.MonitorDatabase
import com.cloudmonitor.data.local.QueuedEventDao
import com.cloudmonitor.data.remote.MonitorApi
import com.cloudmonitor.domain.ProcessProbe
import com.cloudmonitor.domain.RootProcessProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The backend may add fields before the app is updated; never crash on them.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }

    @Provides
    @Singleton
    fun provideBackendBaseUrl(): String = BuildConfig.BACKEND_BASE_URL

    @Provides
    @Singleton
    fun provideMonitorApi(client: HttpClient, baseUrl: String): MonitorApi = MonitorApi(client, baseUrl)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MonitorDatabase =
        Room.databaseBuilder(context, MonitorDatabase::class.java, MonitorDatabase.NAME)
            .addMigrations(*MonitorDatabase.MIGRATIONS)
            // NO fallbackToDestructiveMigration(): the outbox must survive an in-place update.
            .build()

    @Provides
    fun provideQueuedEventDao(database: MonitorDatabase): QueuedEventDao = database.queuedEventDao()

    @Provides
    fun provideEventLogDao(database: MonitorDatabase): EventLogDao = database.eventLogDao()

    @Provides
    @Singleton
    fun provideProcessProbe(): ProcessProbe = RootProcessProbe()

    /**
     * Application-scoped coroutine scope for work that outlives a single service instance.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
