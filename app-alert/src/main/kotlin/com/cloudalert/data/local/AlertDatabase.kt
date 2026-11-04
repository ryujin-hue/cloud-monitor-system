package com.cloudalert.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local history for Cloud Alert.
 *
 * NO `fallbackToDestructiveMigration()`: the alert history must survive an in-place update.
 */
@Database(
    entities = [AlertEventEntity::class, AlertSubscriptionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AlertDatabase : RoomDatabase() {

    abstract fun alertEventDao(): AlertEventDao

    abstract fun alertSubscriptionDao(): AlertSubscriptionDao

    companion object {
        const val NAME = "alert.db"

        /**
         * Version 1 -> 2.
         *
         * Adds the Cloud Monitor version to the stored events and the cloud phone name to
         * the subscriptions. Both statements are additive.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `alert_events` ADD COLUMN `app_version` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `alert_events` ADD COLUMN `version_code` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `alert_subscriptions` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Explicit migrations.
         *
         * There is deliberately NO `fallbackToDestructiveMigration()`: the alert history
         * must survive an in-place update.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
