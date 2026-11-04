package com.cloudalert.di

import android.content.Context
import androidx.room.Room
import com.cloudalert.BuildConfig
import com.cloudalert.data.local.AlertDatabase
import com.cloudalert.data.local.AlertEventDao
import com.cloudalert.data.local.AlertSubscriptionDao
import com.cloudalert.data.remote.AlertApi
import com.cloudalert.fcm.FcmGateway
import com.cloudalert.fcm.FirebaseFcmGateway
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
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
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
    fun provideAlertApi(client: HttpClient, baseUrl: String): AlertApi = AlertApi(client, baseUrl)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AlertDatabase =
        Room.databaseBuilder(context, AlertDatabase::class.java, AlertDatabase.NAME)
            .addMigrations(*AlertDatabase.MIGRATIONS)
            // NO fallbackToDestructiveMigration(): the alert history must survive an update.
            .build()

    @Provides
    fun provideAlertEventDao(database: AlertDatabase): AlertEventDao = database.alertEventDao()

    @Provides
    fun provideAlertSubscriptionDao(database: AlertDatabase): AlertSubscriptionDao = database.alertSubscriptionDao()

    /**
     * FCM is optional. Without `google-services.json` Firebase is never initialised and this
     * factory hands out a gateway that reports "unavailable".
     */
    @Provides
    @Singleton
    fun provideFcmGateway(@ApplicationContext context: Context): FcmGateway {
        val gateway = FirebaseFcmGateway(context)
        return if (gateway.isAvailable) gateway else UnavailableFcmGateway()
    }
}

/**
 * Fallback used when Firebase is not configured.
 */
class UnavailableFcmGateway : FcmGateway {
    override val isAvailable: Boolean = false
    override suspend fun currentToken(): String? = null
    override suspend fun deleteToken() = Unit
}
