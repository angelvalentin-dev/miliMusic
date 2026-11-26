package com.metrolist.desktop.db

import com.metrolist.desktop.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ArtistAliasStore {
    const val BACKUP_FILENAME = "artist_name_aliases.json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file: File by lazy { File(AppPaths.dataDir, BACKUP_FILENAME) }

    private val _aliases = MutableStateFlow<Map<String, String>>(loadFromDisk())
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    private fun loadFromDisk(): Map<String, String> =
        runCatching {
            if (file.exists()) json.decodeFromString<Map<String, String>>(file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())

    private fun saveToDisk(map: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(map))
        }
    }

    fun setAlias(artistId: String?, channelId: String?, originalName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val current = _aliases.value
        val keys = buildSet {
            artistId?.takeIf { it.isNotBlank() }?.let(::add)
            channelId?.takeIf { it.isNotBlank() }?.let(::add)
            add(nameKey(originalName))
            current.filterValues { it == originalName }.keys.forEach(::add)
        }
        val updated = current + keys.associateWith { trimmed }
        _aliases.value = updated
        saveToDisk(updated)
    }

    fun resolve(artistId: String?, fallback: String): String = resolve(_aliases.value, artistId, fallback)

    fun resolve(aliases: Map<String, String>, artistId: String?, fallback: String): String {
        var resolved = artistId?.let(aliases::get) ?: aliases[nameKey(fallback)] ?: fallback
        var next = aliases[nameKey(resolved)] ?: return resolved
        val visited = mutableSetOf(resolved)
        while (visited.add(next)) {
            resolved = next
            next = aliases[nameKey(resolved)] ?: return resolved
        }
        return resolved
    }

    fun serialize(): String = json.encodeToString(_aliases.value)

    fun deserialize(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

    fun restore(restored: Map<String, String>) {
        _aliases.update { restored }
        saveToDisk(restored)
    }

    private fun nameKey(name: String): String = "name:$name"
}
