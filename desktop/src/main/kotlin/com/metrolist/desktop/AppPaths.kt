package com.metrolist.desktop

import java.io.File

object AppPaths {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val dataDir: File by lazy {
        val base = if (isWindows) {
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(System.getProperty("user.home"), "AppData/Roaming")
        } else {
            System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
        }
        File(base, "miliMusic").apply { mkdirs() }
    }

    val dbFile: File by lazy { File(dataDir, "song.db") }
    val preferencesFile: File by lazy { File(dataDir, "settings.preferences_pb") }
    val songCacheDir: File by lazy { File(dataDir, "cache/songs").apply { mkdirs() } }
    val imageCacheDir: File by lazy { File(dataDir, "cache/images").apply { mkdirs() } }
    val defaultDownloadsDir: File by lazy {
        File(File(System.getProperty("user.home"), "Music"), "miliMusic").apply { mkdirs() }
    }

    val bundledResourcesDir: File? by lazy {
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?: runCatching {
                val codeDir = File(AppPaths::class.java.protectionDomain.codeSource.location.toURI()).parentFile
                listOfNotNull(
                    codeDir?.let { File(it, "resources") },
                    codeDir?.parentFile?.let { File(it, "app/resources") },
                ).firstOrNull { it.exists() }
            }.getOrNull()
    }

    fun bundledFile(relativePath: String): File? {
        val fromCompose = bundledResourcesDir?.let { File(it, relativePath) }?.takeIf { it.exists() }
        if (fromCompose != null) return fromCompose
        val devFallback = File("desktop/resources/windows", relativePath).takeIf { it.exists() }
        return devFallback
    }
}
