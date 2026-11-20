package com.metrolist.desktop

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enforces a single running desktop instance using an OS-level file lock and
 * notifies the primary instance over loopback so its window is brought to front.
 */
class SingleInstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val serverSocket: ServerSocket,
    private val portFile: File,
    @Volatile private var onActivateCallback: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        Thread({
            while (!closed.get() && !serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { client ->
                        client.soTimeout = 1000
                        val msg = client.getInputStream().bufferedReader().readLine()
                        if (msg == CMD_SHOW) {
                            runCatching { onActivateCallback() }
                        }
                    }
                } catch (_: Throwable) {
                    if (closed.get() || serverSocket.isClosed) break
                }
            }
        }, "miliMusic-instance-listener").apply {
            isDaemon = true
            start()
        }
    }

    fun setOnActivate(callback: () -> Unit) {
        onActivateCallback = callback
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        runCatching { portFile.delete() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val CMD_SHOW = "SHOW"

        fun tryAcquire(
            dir: File = AppPaths.dataDir,
            onActivate: () -> Unit = {},
        ): SingleInstanceGuard? {
            dir.mkdirs()
            val lockFile = File(dir, "instance.lock")
            val portFile = File(dir, "instance.port")

            val channel = try {
                FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (_: Throwable) {
                notifyExistingInstance(portFile)
                return null
            }

            val lock = try {
                channel.tryLock()
            } catch (_: Throwable) {
                null
            }

            if (lock == null) {
                runCatching { channel.close() }
                notifyExistingInstance(portFile)
                return null
            }

            return try {
                val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
                portFile.writeText(server.localPort.toString())
                SingleInstanceGuard(channel, lock, server, portFile, onActivate)
            } catch (_: Throwable) {
                runCatching { lock.release() }
                runCatching { channel.close() }
                null
            }
        }

        private fun notifyExistingInstance(portFile: File) {
            val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return
            if (port !in 1..65535) return
            runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                    sock.getOutputStream().write("$CMD_SHOW\n".toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().flush()
                }
            }
        }
    }
}
