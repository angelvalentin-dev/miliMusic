package com.metrolist.desktop.backup

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.db.DesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
            }
        }
    }

    suspend fun importBackup(sourceFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists()) { "Backup file does not exist" }
            var foundDb = false
            var prefsBytes: ByteArray? = null
            ZipInputStream(FileInputStream(sourceFile).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    when (name) {
                        SETTINGS_FILENAME -> {
                            prefsBytes = zip.readBytes()
                        }
                        DB_NAME -> {
                            dbFile.parentFile?.mkdirs()
                            File("${dbFile.absolutePath}-wal").delete()
                            File("${dbFile.absolutePath}-shm").delete()
                            FileOutputStream(dbFile).use { zip.copyTo(it) }
                            foundDb = true
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            require(foundDb) { "Invalid backup archive: missing $DB_NAME" }
            prefsBytes?.let { preferences.importFromBytes(it) }
            database.reloadFromDisk()
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
        const val DB_NAME = "song.db"
    }
}
