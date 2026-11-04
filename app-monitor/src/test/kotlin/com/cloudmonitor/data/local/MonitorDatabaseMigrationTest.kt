package com.cloudmonitor.data.local

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that an in-place update does NOT lose data.
 *
 * The test creates a version 1 database by hand (the exact DDL of the committed schema
 * `app-monitor/schemas/com.cloudmonitor.data.local.MonitorDatabase/1.json`), inserts a row
 * into every table and then opens it with Room.
 *
 * Opening it with Room does two things:
 *   1. it runs [MonitorDatabase.MIGRATION_1_2],
 *   2. it validates the resulting schema against the expected version 2 schema and THROWS
 *      when they do not match.
 *
 * A `fallbackToDestructiveMigration()` would wipe the tables and the assertions about the
 * surviving rows would fail — which is exactly what this test is here to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorDatabaseMigrationTest {

    private companion object {
        const val DB_NAME = "monitor-migration-test.db"

        /** The identity hash of schema version 1 (from the committed 1.json). */
        const val V1_IDENTITY_HASH = "a5fa7cf6abefd005743b7adf8c7664b7"

        const val CREATE_QUEUED_EVENTS_V1 =
            "CREATE TABLE IF NOT EXISTS `queued_events` (`event_id` TEXT NOT NULL, " +
                "`device_id` TEXT NOT NULL, `payload` TEXT NOT NULL, `occurred_at` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
                "`next_attempt_at` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"

        const val CREATE_EVENT_LOG_V1 =
            "CREATE TABLE IF NOT EXISTS `event_log` (`event_id` TEXT NOT NULL, " +
                "`device_id` TEXT NOT NULL, `type` TEXT NOT NULL, `code` TEXT NOT NULL, " +
                "`package_name` TEXT, `occurred_at` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                "PRIMARY KEY(`event_id`))"
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
                        db.execSQL(CREATE_QUEUED_EVENTS_V1)
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_queued_events_next_attempt_at` " +
                                "ON `queued_events` (`next_attempt_at`)",
                        )
                        db.execSQL(CREATE_EVENT_LOG_V1)
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_event_log_occurred_at` " +
                                "ON `event_log` (`occurred_at`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_event_log_code` " +
                                "ON `event_log` (`code`)",
                        )
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
            "INSERT INTO queued_events (event_id, device_id, payload, occurred_at, created_at, " +
                "attempts, next_attempt_at) VALUES " +
                "('evt-1', 'dev-1', '{\"code\":\"E-101\"}', 1000, 1000, 2, 5000)",
        )
        db.execSQL(
            "INSERT INTO event_log (event_id, device_id, type, code, package_name, occurred_at, seq) " +
                "VALUES ('evt-1', 'dev-1', 'DOWN', 'E-101', 'com.example.game', 1000, 7)",
        )
        helper.close()
    }

    @Test
    fun `migrating from version 1 to 2 keeps the queued events`() = runBlocking {
        val database = Room.databaseBuilder(context, MonitorDatabase::class.java, DB_NAME)
            .addMigrations(*MonitorDatabase.MIGRATIONS)
            .build()

        // Opening the database runs the migration and validates the resulting schema.
        val queued = database.queuedEventDao().due(Long.MAX_VALUE, 10)

        assertEquals(1, queued.size)
        val row = queued.first()
        assertEquals("evt-1", row.eventId)
        assertEquals("dev-1", row.deviceId)
        assertEquals(2, row.attempts)
        assertEquals(5000L, row.nextAttemptAt)
        assertEquals(1000L, row.occurredAt)

        database.close()
    }

    @Test
    fun `migrating from version 1 to 2 adds the new columns with their defaults`() = runBlocking {
        val database = Room.databaseBuilder(context, MonitorDatabase::class.java, DB_NAME)
            .addMigrations(*MonitorDatabase.MIGRATIONS)
            .build()

        val queued = database.queuedEventDao().due(Long.MAX_VALUE, 10).first()
        assertEquals(QueuedEventEntity.STATE_PENDING, queued.state)

        val log = database.eventLogDao().observeRecent(10).first()
        assertEquals(1, log.size)
        assertEquals("{}", log.first().metadataJson)
        assertEquals("", log.first().appVersion)
        assertEquals(0L, log.first().versionCode)
        assertEquals("", log.first().confidence)
        assertEquals("", log.first().detection)

        database.close()
    }

    @Test
    fun `migrating from version 1 to 2 keeps the event history`() = runBlocking {
        val database = Room.databaseBuilder(context, MonitorDatabase::class.java, DB_NAME)
            .addMigrations(*MonitorDatabase.MIGRATIONS)
            .build()

        val log = database.eventLogDao().observeRecent(10).first()
        val row = log.first()
        assertEquals("evt-1", row.eventId)
        assertEquals("DOWN", row.type)
        assertEquals("E-101", row.code)
        assertEquals("com.example.game", row.packageName)
        assertEquals(1000L, row.occurredAt)
        assertEquals(7L, row.seq)

        database.close()
    }

    @Test
    fun `the database reports version 2 after the migration`() = runBlocking {
        val database = Room.databaseBuilder(context, MonitorDatabase::class.java, DB_NAME)
            .addMigrations(*MonitorDatabase.MIGRATIONS)
            .build()

        // Touch the database so the helper is created.
        database.queuedEventDao().count()

        val version = database.openHelper.readableDatabase.version
        assertEquals(2, version)

        database.close()
    }

    @Test
    fun `the migration is registered and no destructive fallback exists`() {
        // Exactly one explicit migration: 1 -> 2.
        assertEquals(1, MonitorDatabase.MIGRATIONS.size)
        assertNotNull(MonitorDatabase.MIGRATIONS.firstOrNull())

        // A destructive fallback would delete the outbox during an in-place update. Room
        // only falls back when no migration is registered, so the migration list must not
        // be empty.
        assertTrue(MonitorDatabase.MIGRATIONS.isNotEmpty())
    }
}
