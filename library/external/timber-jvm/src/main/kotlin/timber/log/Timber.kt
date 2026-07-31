/*
 * Copyright 2026 New Vector Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package timber.log

/**
 * Minimal JVM stand-in for JakeWharton's Timber (which is Android-only): the static log methods and
 * [tag], which is all the matrix-sdk core uses. Output goes to a swappable [sink]; the default
 * prints `LEVEL/tag: message` to stdout (errors and warnings to stderr).
 */
object Timber {

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    /** Messages below this priority are dropped. */
    @JvmStatic
    @Volatile
    var minPriority: Int = VERBOSE

    /** Swap to redirect logging (e.g. into a file or a desktop UI console). */
    @JvmStatic
    @Volatile
    var sink: (priority: Int, tag: String?, message: String, throwable: Throwable?) -> Unit = { priority, tag, message, throwable ->
        val level = when (priority) {
            VERBOSE -> "V"
            DEBUG -> "D"
            INFO -> "I"
            WARN -> "W"
            else -> "E"
        }
        val line = "$level/${tag ?: "Matrix"}: $message"
        val stream = if (priority >= WARN) System.err else System.out
        stream.println(line)
        throwable?.printStackTrace(stream)
    }

    @JvmStatic
    fun tag(tag: String): Tree = Tree(tag)

    @JvmStatic
    fun v(message: String?, vararg args: Any?) = log(VERBOSE, null, null, message, args)

    @JvmStatic
    fun v(t: Throwable?, message: String? = null, vararg args: Any?) = log(VERBOSE, null, t, message, args)

    @JvmStatic
    fun d(message: String?, vararg args: Any?) = log(DEBUG, null, null, message, args)

    @JvmStatic
    fun d(t: Throwable?, message: String? = null, vararg args: Any?) = log(DEBUG, null, t, message, args)

    @JvmStatic
    fun i(message: String?, vararg args: Any?) = log(INFO, null, null, message, args)

    @JvmStatic
    fun i(t: Throwable?, message: String? = null, vararg args: Any?) = log(INFO, null, t, message, args)

    @JvmStatic
    fun w(message: String?, vararg args: Any?) = log(WARN, null, null, message, args)

    @JvmStatic
    fun w(t: Throwable?, message: String? = null, vararg args: Any?) = log(WARN, null, t, message, args)

    @JvmStatic
    fun e(message: String?, vararg args: Any?) = log(ERROR, null, null, message, args)

    @JvmStatic
    fun e(t: Throwable?, message: String? = null, vararg args: Any?) = log(ERROR, null, t, message, args)

    internal fun log(priority: Int, tag: String?, t: Throwable?, message: String?, args: Array<out Any?>) {
        if (priority < minPriority) return
        if (message == null && t == null) return
        val formatted = when {
            message == null -> t.toString()
            args.isEmpty() -> message
            else -> runCatching { message.format(*args) }.getOrDefault(message)
        }
        sink(priority, tag, formatted, t)
    }

    /** The tagged view returned by [tag], mirroring Timber's Tree call surface. */
    class Tree internal constructor(private val tag: String) {

        fun v(message: String?, vararg args: Any?) = log(VERBOSE, tag, null, message, args)

        fun v(t: Throwable?, message: String? = null, vararg args: Any?) = log(VERBOSE, tag, t, message, args)

        fun d(message: String?, vararg args: Any?) = log(DEBUG, tag, null, message, args)

        fun d(t: Throwable?, message: String? = null, vararg args: Any?) = log(DEBUG, tag, t, message, args)

        fun i(message: String?, vararg args: Any?) = log(INFO, tag, null, message, args)

        fun i(t: Throwable?, message: String? = null, vararg args: Any?) = log(INFO, tag, t, message, args)

        fun w(message: String?, vararg args: Any?) = log(WARN, tag, null, message, args)

        fun w(t: Throwable?, message: String? = null, vararg args: Any?) = log(WARN, tag, t, message, args)

        fun e(message: String?, vararg args: Any?) = log(ERROR, tag, null, message, args)

        fun e(t: Throwable?, message: String? = null, vararg args: Any?) = log(ERROR, tag, t, message, args)
    }
}
