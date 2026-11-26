package com.metrolist.desktop.backup

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.db.ArtistAliasStore
import com.metrolist.desktop.db.DesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPreview(
    val hasDatabase: Boolean,
    val hasSettings: Boolean,
    val hasAliases: Boolean,
    val dbVersion: Int,
    val songCount: Int,
    val playlistCount: Int,
    val compatible: Boolean,
)

class BackupRestoreService(
    private val database: DesktopDatabase,
    private val preferences: DesktopPreferences,
    private val dbFile: File = AppPaths.dbFile,
    private val preferencesFile: File = AppPaths.preferencesFile,
) {
    suspend fun exportBackup(targetFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            database.checkpoint()
            targetFile.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(targetFile).buffered()).use { zip ->
                if (preferencesFile.exists()) {
                    zip.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                    FileInputStream(preferencesFile).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                if (dbFile.exists()) {
                    zip.putNextEntry(ZipEntry(DB_NAME))
                    FileInputStream(dbFile).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val aliasesJson = ArtistAliasStore.serialize()
                if (aliasesJson != "{}") {
                    zip.putNextEntry(ZipEntry(ArtistAliasStore.BACKUP_FILENAME))
                    zip.write(aliasesJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun previewBackup(sourceFile: File): Result<BackupPreview> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists()) { "El archivo de respaldo no existe" }
            var dbBytes: ByteArray? = null
            var hasSettings = false
            var hasAliases = false
            ZipInputStream(FileInputStream(sourceFile).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (File(entry.name).name) {
                        SETTINGS_FILENAME -> hasSettings = true
                        DB_NAME -> dbBytes = zip.readBytes()
                        ArtistAliasStore.BACKUP_FILENAME -> hasAliases = true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            var dbVersion = 0
            var songCount = 0
            var playlistCount = 0
            if (dbBytes != null) {
                val tmp = File.createTempFile("milimusic_preview_", ".db")
                try {
                    tmp.writeBytes(dbBytes)
                    DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}").use { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("PRAGMA user_version").use { if (it.next()) dbVersion = it.getInt(1) }
                            runCatching {
                                st.executeQuery("SELECT COUNT(*) FROM song").use { if (it.next()) songCount = it.getInt(1) }
                            }
                            runCatching {
                                st.executeQuery("SELECT COUNT(*) FROM playlist").use { if (it.next()) playlistCount = it.getInt(1) }
                            }
                        }
                    }
                } finally {
                    tmp.delete()
                }
            }
            BackupPreview(
                hasDatabase = dbBytes != null,
                hasSettings = hasSettings,
                hasAliases = hasAliases,
                dbVersion = dbVersion,
                songCount = songCount,
                playlistCount = playlistCount,
                compatible = dbBytes != null && (dbVersion == 0 || dbVersion <= CURRENT_DB_VERSION),
            )
        }
    }

    suspend fun importBackup(sourceFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists()) { "Backup file does not exist" }
            var dbBytes: ByteArray? = null
            var prefsBytes: ByteArray? = null
            var aliasesJson: String? = null
            ZipInputStream(FileInputStream(sourceFile).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    when (name) {
                        SETTINGS_FILENAME -> prefsBytes = zip.readBytes()
                        DB_NAME -> dbBytes = zip.readBytes()
                        ArtistAliasStore.BACKUP_FILENAME -> aliasesJson = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val validDb = dbBytes ?: error("Invalid backup archive: missing $DB_NAME")
            database.replaceDatabaseFile(validDb)
            prefsBytes?.let { preferences.importFromBytes(it) }
            aliasesJson?.let { ArtistAliasStore.restore(ArtistAliasStore.deserialize(it)) }
            Unit
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
        const val DB_NAME = "song.db"
        const val CURRENT_DB_VERSION = 38
    }
}
