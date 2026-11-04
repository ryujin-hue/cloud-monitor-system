package com.cloudalert.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that an in-place update of Cloud Alert does not lose the history.
 *
 * The test creates the version 1 database by hand (the exact DDL of the committed schema
 * `app-alert/schemas/com.cloudalert.data.local.AlertDatabase/1.json`), inserts rows and
 * opens it with Room, which runs the migration and then validates the resulting schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertDatabaseMigrationTest {

    private companion object {
        const val DB_NAME = "alert-migration-test.db"

        /** The identity hash of schema version 1 (from the committed 1.json). */
        const val V1_IDENTITY_HASH = "a5fa7cf6abefd005743b7adf8c7664b7"

        const val CREATE_ALERT_EVENTS_V1 =
            "CREATE TABLE IF NOT EXISTS `alert_events` (`event_id` TEXT NOT NULL, " +
                "`device_id` TEXT NOT NULL, `device_label` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`code` TEXT NOT NULL, `package_name` TEXT, `occurred_at` INTEGER NOT NULL, " +
                "`received_at` INTEGER, `seq` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"

        const val CREATE_ALERT_SUBSCRIPTIONS_V1 =
            "CREATE TABLE IF NOT EXISTS `alert_subscriptions` (`alert_device_id` TEXT NOT NULL, " +
                "`monitor_device_id` TEXT NOT NULL, `subscribed_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`alert_device_id`, `monitor_device_id`))"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        createVersion1Database()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun createVersion1Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_ALERT_EVENTS_V1)
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_alert_events_occurred_at` " +
                                "ON `alert_events` (`occurred_at`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_alert_events_code` " +
                                "ON `alert_events` (`code`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_alert_events_device_id` " +
                                "ON `alert_events` (`device_id`)",
                        )
                        db.execSQL(CREATE_ALERT_SUBSCRIPTIONS_V1)
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                                "VALUES(42, '$V1_IDENTITY_HASH')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        Unit
                })
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO alert_events (event_id, device_id, device_label, type, code, " +
                "package_name, occurred_at, received_at, seq) VALUES " +
                "('evt-1', 'dev-1', 'Cloud phone 1', 'DOWN', 'E-101', 'com.example.game', 1000, 1100, 3)",
        )
        db.execSQL(
            "INSERT INTO alert_subscriptions (alert_device_id, monitor_device_id, subscribed_at) " +
                "VALUES ('alert-1', 'dev-1', 500)",
        )
        helper.close()
    }

    @Test
    fun `migrating from version 1 to 2 keeps the event history`() = runBlocking {
        val database = Room.databaseBuilder(context, AlertDatabase::class.java, DB_NAME)
            .addMigrations(*AlertDatabase.MIGRATIONS)
            .build()

        val events = database.alertEventDao().observeRecent(10).first()
        assertEquals(1, events.size)
        val row = events.first()
        assertEquals("evt-1", row.eventId)
        assertEquals("Cloud phone 1", row.deviceLabel)
        assertEquals("E-101", row.code)
        assertEquals("com.example.game", row.packageName)
        assertEquals(1000L, row.occurredAt)
        assertEquals(1100L, row.receivedAt)

        database.close()
    }

    @Test
    fun `migrating from version 1 to 2 adds the new columns with their defaults`() = runBlocking {
        val database = Room.databaseBuilder(context, AlertDatabase::class.java, DB_NAME)
            .addMigrations(*AlertDatabase.MIGRATIONS)
            .build()

        val events = database.alertEventDao().observeRecent(10).first()
        assertEquals("", events.first().appVersion)
        assertEquals(0L, events.first().versionCode)

        val subscriptions = database.alertSubscriptionDao().observeFor("alert-1").first()
        assertEquals(1, subscriptions.size)
        assertEquals("dev-1", subscriptions.first().monitorDeviceId)
        assertEquals("", subscriptions.first().label)

        database.close()
    }

    @Test
    fun `the database reports version 2 after the migration`() = runBlocking {
        val database = Room.databaseBuilder(context, AlertDatabase::class.java, DB_NAME)
            .addMigrations(*AlertDatabase.MIGRATIONS)
            .build()

        database.alertEventDao().observeCount().first()

        assertEquals(2, database.openHelper.readableDatabase.version)

        database.close()
    }
}
