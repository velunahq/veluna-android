package com.example.data.local

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VelunaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelunaDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesExistingDataAndCreatesNewTables() {
        val dbName = "veluna_migration_1_2.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_v1', '{\"videoId\":\"trk_v1\",\"title\":\"V1 Track\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (1, 'My V1 List', 'Desc', NULL, 1700000000000, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) VALUES (10, 1, 'trk_v1', '{}', 0, 1700000000000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            VelunaDatabase.MIGRATION_1_2
        )

        migratedDb.query("SELECT videoId FROM liked_songs").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("trk_v1")
        }
        migratedDb.query("SELECT title FROM local_playlists WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("My V1 List")
        }

        migratedDb.execSQL(
            "INSERT INTO sync_tombstones (entityType, entityKey, clientUpdatedAt, deletedAt) VALUES ('LIKED', 'trk_del', 1700000001000, 1700000001000)"
        )
        migratedDb.execSQL(
            "INSERT INTO pending_scrobbles (service, payloadJson, startedAtSecs, attempts, nextAttemptAt, lastError) VALUES ('LASTFM', '{}', 1700000000, 0, 0, NULL)"
        )
        migratedDb.query("SELECT COUNT(*) FROM sync_tombstones").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migratedDb.query("SELECT COUNT(*) FROM pending_scrobbles").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migratedDb.close()
    }

    @Test
    fun migrate2To3_assignsLegacyCloudKeysAndCreatesSyncBaselines() {
        val dbName = "veluna_migration_2_3.db"
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (7, 'Late Night', 'Synth', NULL, 1700000000000, 1700000000500)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (12, 'Workout', '', NULL, 1700000001000, 1700000001000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VelunaDatabase.MIGRATION_2_3
        )

        migratedDb.query("SELECT id, cloudKey, syncRevision FROM local_playlists ORDER BY id ASC").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(7L)
            assertThat(c.getString(1)).isEqualTo("pl_local_7")
            assertThat(c.getLong(2)).isEqualTo(0L)

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(12L)
            assertThat(c.getString(1)).isEqualTo("pl_local_12")
            assertThat(c.getLong(2)).isEqualTo(0L)
        }

        // Verify unique index on cloudKey rejects duplicates
        try {
            migratedDb.execSQL(
                "INSERT INTO local_playlists (cloudKey, title, description, coverUri, createdAt, updatedAt, syncRevision) VALUES ('pl_local_7', 'Dup', '', NULL, 1, 1, 1)"
            )
            fail("Expected SQLiteConstraintException on duplicate cloudKey")
        } catch (_: SQLiteConstraintException) {
            // Expected
        }

        // Verify sync_baselines table accepts inserts
        migratedDb.execSQL(
            "INSERT INTO sync_baselines (userId, entityType, entityKey, revision, signature, clientUpdatedAt, deleted, updatedAt) VALUES ('uid_1', 'PLAYLIST', 'pl_local_7', 1, 'sig_abc', 1700000000500, 0, 1700000000500)"
        )
        migratedDb.query("SELECT signature, revision FROM sync_baselines WHERE userId = 'uid_1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("sig_abc")
            assertThat(c.getLong(1)).isEqualTo(1L)
        }
        migratedDb.close()
    }

    @Test
    fun migrate1To3_preservesAllDataEndToEnd() {
        val dbName = "veluna_migration_1_3.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_v1_e2e', '{\"videoId\":\"trk_v1_e2e\",\"title\":\"E2E\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (3, 'Road Trip', 'Highway', NULL, 1700000000000, 1700000002000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VelunaDatabase.MIGRATION_1_2,
            VelunaDatabase.MIGRATION_2_3
        )

        migratedDb.query("SELECT cloudKey, title, syncRevision FROM local_playlists WHERE id = 3").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_local_3")
            assertThat(c.getString(1)).isEqualTo("Road Trip")
            assertThat(c.getLong(2)).isEqualTo(0L)
        }
        migratedDb.query("SELECT videoId FROM liked_songs").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_v1_e2e")
        }
        migratedDb.close()
    }

    @Test
    fun defaultCloudKey_isUniqueUuidPerPlaylist_andRejectsBlank(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.velunaDao()
            val p1 = LocalPlaylistEntity(title = "Same Title")
            val p2 = LocalPlaylistEntity(title = "Same Title")
            assertThat(p1.cloudKey).startsWith("pl_")
            assertThat(p2.cloudKey).startsWith("pl_")
            assertThat(p1.cloudKey).isNotEqualTo(p2.cloudKey)

            dao.insertPlaylist(p1)
            dao.insertPlaylist(p2)
            assertThat(dao.getPlaylistsOnce().map { it.cloudKey }).containsExactly(p1.cloudKey, p2.cloudKey)

            try {
                LocalPlaylistEntity(cloudKey = "   ", title = "Invalid")
                fail("Expected IllegalArgumentException for blank cloudKey")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4_backfillsRemoteCoverUriAndBaselineSnapshotJson() {
        val dbName = "veluna_migration_3_4.db"
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO local_playlists (id, cloudKey, title, description, coverUri, createdAt, updatedAt, syncRevision) " +
                    "VALUES (5, 'pl_v3_https', 'HTTPS Art', 'Desc', 'https://cdn.example.com/art.jpg', 1700000000000, 1700000001000, 3)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, cloudKey, title, description, coverUri, createdAt, updatedAt, syncRevision) " +
                    "VALUES (6, 'pl_v3_file', 'Local Art', 'Desc', 'file:///data/user/0/com.veluna.music/files/playlist_covers/pl_6.jpg', 1700000000000, 1700000002000, 2)"
            )
            db.execSQL(
                "INSERT INTO sync_baselines (userId, entityType, entityKey, revision, signature, clientUpdatedAt, deleted, updatedAt) " +
                    "VALUES ('uid_v3', 'PLAYLIST', 'pl_v3_https', 3, 'sig_123', 1700000001000, 0, 1700000001000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            VelunaDatabase.MIGRATION_3_4
        )

        migratedDb.query(
            "SELECT cloudKey, coverUri, remoteCoverUri, coverRemoved FROM local_playlists ORDER BY id ASC"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_v3_https")
            assertThat(c.getString(1)).isEqualTo("https://cdn.example.com/art.jpg")
            assertThat(c.getString(2)).isEqualTo("https://cdn.example.com/art.jpg")
            assertThat(c.getInt(3)).isEqualTo(0)

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_v3_file")
            assertThat(c.isNull(2)).isTrue()
            assertThat(c.getInt(3)).isEqualTo(0)
        }

        migratedDb.query("SELECT snapshotJson FROM sync_baselines WHERE entityKey = 'pl_v3_https'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("")
        }
        migratedDb.close()
    }

    @Test
    fun migrate1To4_preservesAllLegacyTablesTracksAndCoverMetadata() {
        val dbName = "veluna_migration_1_4.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_legacy_1', '{\"videoId\":\"trk_legacy_1\",\"title\":\"Legacy\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) " +
                    "VALUES (9, 'V1 Playlist', 'Since v1', 'https://cdn.example.com/v1.png', 1700000000000, 1700000005000)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) " +
                    "VALUES (100, 9, 'trk_legacy_1', '{}', 0, 1700000000500)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            VelunaDatabase.MIGRATION_1_2,
            VelunaDatabase.MIGRATION_2_3,
            VelunaDatabase.MIGRATION_3_4
        )

        migratedDb.query(
            "SELECT cloudKey, remoteCoverUri, coverRemoved, syncRevision FROM local_playlists WHERE id = 9"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_local_9")
            assertThat(c.getString(1)).isEqualTo("https://cdn.example.com/v1.png")
            assertThat(c.getInt(2)).isEqualTo(0)
            assertThat(c.getLong(3)).isEqualTo(0L)
        }
        migratedDb.query("SELECT videoId FROM local_playlist_tracks WHERE playlistId = 9").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_legacy_1")
        }
        migratedDb.close()
    }

    @Test
    fun migrate4To5_createsCausalOpTablesAndBackfillsExistingTracks() {
        val dbName = "veluna_migration_4_5.db"
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO local_playlists (id, cloudKey, title, description, coverUri, remoteCoverUri, coverRemoved, createdAt, updatedAt, syncRevision) " +
                    "VALUES (5, 'pl_local_5', 'Focus v4', 'Desc', 'https://cdn.example.com/c.jpg', 'https://cdn.example.com/c.jpg', 0, 1700000000000, 1700000001000, 3)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) " +
                    "VALUES (21, 5, 'trk_a', '{\"videoId\":\"trk_a\",\"title\":\"A\"}', 0, 1700000000100)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) " +
                    "VALUES (22, 5, 'trk_b', '{\"videoId\":\"trk_b\",\"title\":\"B\"}', 1, 1700000000200)"
            )
            db.execSQL(
                "INSERT INTO sync_baselines (userId, entityType, entityKey, revision, signature, clientUpdatedAt, deleted, updatedAt, snapshotJson) " +
                    "VALUES ('uid_4', 'PLAYLIST', 'pl_local_5', 3, 'sig_4', 1700000001000, 0, 1700000001000, '{\"title\":\"Focus v4\",\"description\":\"Desc\"}')"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            VelunaDatabase.MIGRATION_4_5
        )

        migratedDb.query("SELECT cloudKey, syncRevision FROM local_playlists WHERE id = 5").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_local_5")
            assertThat(c.getLong(1)).isEqualTo(3L)
        }
        migratedDb.query(
            "SELECT videoId, deleted, timestampMs, opSeq, deviceId, opId FROM playlist_track_ops WHERE playlistId = 5 ORDER BY videoId ASC"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_a")
            assertThat(c.getInt(1)).isEqualTo(0)
            assertThat(c.getLong(2)).isEqualTo(1700000000100L)
            assertThat(c.getLong(3)).isEqualTo(21L)
            assertThat(c.getString(4)).isEqualTo("legacy_v4")
            assertThat(c.getString(5)).isEqualTo("mig_trk_21")

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_b")
            assertThat(c.getString(5)).isEqualTo("mig_trk_22")
        }
        migratedDb.query("SELECT COUNT(*) FROM playlist_field_ops").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        migratedDb.query("SELECT COUNT(*) FROM local_device_clock").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        // Unique index on cloudKey remains enforced in v5
        try {
            migratedDb.execSQL(
                "INSERT INTO local_playlists (cloudKey, title, description, coverUri, remoteCoverUri, coverRemoved, createdAt, updatedAt, syncRevision) " +
                    "VALUES ('pl_local_5', 'Dup', '', NULL, NULL, 0, 1, 1, 1)"
            )
            fail("Expected SQLiteConstraintException on duplicate cloudKey in v5")
        } catch (_: SQLiteConstraintException) {
            // Expected
        }
        migratedDb.close()
    }

    @Test
    fun migrate1To5_preservesLegacyCloudKeyTracksAndAllSyncTables() {
        val dbName = "veluna_migration_1_5.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_v1_five', '{\"videoId\":\"trk_v1_five\",\"title\":\"E2E v5\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) " +
                    "VALUES (9, 'V1 Playlist', 'Since v1', 'https://cdn.example.com/v1.png', 1700000000000, 1700000005000)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) " +
                    "VALUES (100, 9, 'trk_legacy_1', '{}', 0, 1700000000500)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            VelunaDatabase.MIGRATION_1_2,
            VelunaDatabase.MIGRATION_2_3,
            VelunaDatabase.MIGRATION_3_4,
            VelunaDatabase.MIGRATION_4_5
        )

        migratedDb.query(
            "SELECT cloudKey, remoteCoverUri, coverRemoved, syncRevision FROM local_playlists WHERE id = 9"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_local_9")
            assertThat(c.getString(1)).isEqualTo("https://cdn.example.com/v1.png")
            assertThat(c.getInt(2)).isEqualTo(0)
            assertThat(c.getLong(3)).isEqualTo(0L)
        }
        migratedDb.query("SELECT videoId, deviceId FROM playlist_track_ops WHERE playlistId = 9").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_legacy_1")
            assertThat(c.getString(1)).isEqualTo("legacy_v4")
        }
        migratedDb.query("SELECT COUNT(*) FROM local_playlists WHERE TRIM(cloudKey) = ''").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        migratedDb.close()
    }
}

