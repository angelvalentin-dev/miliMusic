package com.metrolist.desktop.auth

import com.metrolist.desktop.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WebView2AuthResult(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String = "",
    val authUser: String = "0",
)

object WindowsWebView2Auth {
    private val json = Json { ignoreUnknownKeys = true }

    fun findHelperExecutable() =
        AppPaths.bundledFile("milimusic-login.exe")

    fun isAvailable(): Boolean =
        AppPaths.isWindows && findHelperExecutable() != null

    suspend fun launchInteractiveLogin(switchChannel: Boolean = false): Result<WebView2AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val exe = findHelperExecutable()
                    ?: error("No se encontró milimusic-login.exe (WebView2)")
                val args = buildList {
                    add(exe.absolutePath)
                    if (switchChannel) add("--switch")
                }
                val process = ProcessBuilder(args)
                    .redirectErrorStream(false)
                    .start()

                val stdout = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                check(exitCode == 0) {
                    stderr.ifBlank { "Inicio de sesión cancelado (código $exitCode)" }
                }
                val jsonLine = stdout.lines().lastOrNull { it.trim().startsWith("{") }
                    ?: error("WebView2 no devolvió datos de sesión")
                json.decodeFromString<WebView2AuthResult>(jsonLine)
            }
        }
}
