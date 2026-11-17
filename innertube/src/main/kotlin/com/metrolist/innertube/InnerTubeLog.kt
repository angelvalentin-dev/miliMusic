package com.metrolist.innertube

object InnerTubeLog {
    enum class Level { DEBUG, WARN, ERROR }

    @Volatile
    var handler: ((Level, String, Throwable?) -> Unit)? = null

    fun d(message: String, vararg args: Any?) {
        val formatted = if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
        handler?.invoke(Level.DEBUG, formatted, null)
    }

    fun w(message: String, vararg args: Any?) {
        val formatted = if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
        handler?.invoke(Level.WARN, formatted, null)
    }

    fun e(throwable: Throwable? = null, message: String, vararg args: Any?) {
        val formatted = if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
        handler?.invoke(Level.ERROR, formatted, throwable)
    }
}
