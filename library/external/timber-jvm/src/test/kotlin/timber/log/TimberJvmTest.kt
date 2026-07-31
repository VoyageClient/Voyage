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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TimberJvmTest {

    private data class Line(val priority: Int, val tag: String?, val message: String, val throwable: Throwable?)

    private val lines = mutableListOf<Line>()
    private val defaultSink = Timber.sink

    private fun captureSink() {
        Timber.sink = { priority, tag, message, throwable -> lines.add(Line(priority, tag, message, throwable)) }
    }

    @After
    fun tearDown() {
        Timber.sink = defaultSink
        Timber.minPriority = Timber.VERBOSE
    }

    @Test
    fun `formats args, keeps raw message on bad format, routes tags and throwables`() {
        captureSink()

        Timber.d("hello %s %d", "world", 42)
        Timber.tag("MyTag").w("warn %s", "arg")
        val boom = RuntimeException("boom")
        Timber.e(boom, "failed")
        Timber.i("100% raw", "unused")

        assertEquals(
                listOf(
                        Line(Timber.DEBUG, null, "hello world 42", null),
                        Line(Timber.WARN, "MyTag", "warn arg", null),
                        Line(Timber.ERROR, null, "failed", boom),
                        Line(Timber.INFO, null, "100% raw", null),
                ),
                lines
        )
    }

    @Test
    fun `minPriority drops lower levels`() {
        captureSink()
        Timber.minPriority = Timber.WARN

        Timber.v("dropped")
        Timber.d("dropped")
        Timber.tag("T").i("dropped")
        Timber.e("kept")

        assertEquals(listOf(Line(Timber.ERROR, null, "kept", null)), lines)
    }
}
