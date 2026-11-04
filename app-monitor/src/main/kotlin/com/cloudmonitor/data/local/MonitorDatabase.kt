package com.cloudmonitor.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase

/**
 * Offline queue and local history for Cloud Monitor.
 *
 * IMPORTANT: there is deliberately NO `fallbackToDestructiveMigration()`. A destructive
 * migration would silently delete the outbox on an in-place update, losing exactly the
 * events that matter most (the ones captured while the network was down).
 */
@Database(
    entities = [QueuedEventEntity::class, EventLogEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MonitorDatabase : RoomDatabase() {

    abstract fun queuedEventDao(): QueuedEventDao

    abstract fun eventLogDao(): EventLogDao

    companion object {
        const val NAME = "monitor.db"

        /**
         * Version 1 -> 2.
         *
         * Adds the delivery state to the outbox and the evidence / version columns to the
         * local history. Every statement is additive, so the rows written by version 1 keep
         * their data.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `queued_events` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'PENDING'",
                )
                db.execSQL(
                    "ALTER TABLE `event_log` ADD COLUMN `metadata_json` TEXT NOT NULL DEFAULT '{}'",
                )
                db.execSQL(
                    "ALTER TABLE `event_log` ADD COLUMN `app_version` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `event_log` ADD COLUMN `version_code` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `event_log` ADD COLUMN `confidence` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `event_log` ADD COLUMN `detection` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Explicit migrations.
         *
         * There is deliberately NO `fallbackToDestructiveMigration()`: a destructive
         * migration would silently delete the outbox during an in-place update, losing
         * exactly the events that matter most.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
