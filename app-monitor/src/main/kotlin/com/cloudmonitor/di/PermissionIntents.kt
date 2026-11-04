package com.cloudmonitor.di

import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The intents that open the settings screens the monitor depends on.
 *
 * Exposed as a small wrapper so the UI never has to build intents itself and the VM never
 * has to leak a Context.
 */
@Singleton
class PermissionIntents(private val provider: PermissionStatusProvider) {
    val notificationAccess: Intent get() = provider.notificationAccessIntent()
    val usageAccess: Intent get() = provider.usageAccessIntent()
    val batteryExemption: Intent get() = provider.batteryExemptionIntent()
    val notifications: Intent get() = provider.notificationPermissionIntent()
    val accessibility: Intent get() = provider.accessibilityIntent()
}

@Module
@InstallIn(SingletonComponent::class)
object PermissionIntentsModule {
    @Provides
    @Singleton
    fun providePermissionIntents(provider: PermissionStatusProvider): PermissionIntents =
        PermissionIntents(provider)
}
