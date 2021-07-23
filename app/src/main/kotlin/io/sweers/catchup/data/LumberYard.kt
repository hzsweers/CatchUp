/*
 * Copyright (C) 2019. Zac Sweers
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
package io.sweers.catchup.data

import android.app.Application
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.BufferedSink
import okio.buffer
import okio.sink
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import java.util.ArrayDeque
import java.util.ArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class LumberYard @Inject constructor(private val app: Application) {

  private val entries = ArrayDeque<Entry>(BUFFER_SIZE + 1)
  private val sharedFlow = MutableSharedFlow<Entry>()

  fun tree(): Timber.Tree {
    return object : Timber.DebugTree() {
      override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        addEntry(Entry(priority, tag, message))
      }
    }
  }

  @Synchronized
  private fun addEntry(entry: Entry) {
    entries.addLast(entry)
    if (entries.size > BUFFER_SIZE) {
      entries.removeFirst()
    }

    sharedFlow.tryEmit(entry)
  }

  fun bufferedLogs() = ArrayList(entries)

  fun logs(): Flow<Entry> = sharedFlow.asSharedFlow()

  /**
   * Save the current logs to disk.
   */
  suspend fun save(): File = suspendCancellableCoroutine { continuation ->
    val folder = app.getExternalFilesDir(null)
    if (folder == null) {
      continuation.resumeWithException(IOException("External storage is not mounted."))
    } else {
      val fileName = ISO_LOCAL_DATE_TIME.format(LocalDateTime.now())
      val output = File(folder, fileName)

      var sink: BufferedSink? = null
      try {
        sink = output.sink().buffer()
        val entries1 = bufferedLogs()
        for (entry in entries1) {
          sink.writeUtf8(entry.prettyPrint()).writeByte('\n'.code)
        }
        // need to close before emiting file to the subscriber, because when subscriber receives
        // data in the same thread the file may be truncated
        sink.close()
        sink = null

        continuation.resume(output)
      } catch (e: IOException) {
        continuation.resumeWithException(e)
      } finally {
        if (sink != null) {
          try {
            sink.close()
          } catch (e: IOException) {
            continuation.resumeWithException(e)
          }
        }
      }
    }
  }

  /**
   * Delete all of the log files saved to disk. Be careful not to call this before any intents have
   * finished using the file reference.
   */
  @WorkerThread
  fun cleanUp(): Long {
    return app.getExternalFilesDir(null)?.let { folder ->
      val initialSize = folder.length()
      (folder.listFiles() ?: return -1L)
        .asSequence()
        .filter { it.name.endsWith(".log") }
        .forEach { it.delete() }
      return@let initialSize - folder.length()
    } ?: -1L
  }

  data class Entry(val level: Int, val tag: String?, val message: String) {
    fun prettyPrint(): String {
      return String.format(
        "%22s %s %s",
        tag ?: "CATCHUP",
        displayLevel(),
        // Indent newlines to match the original indentation.
        message.replace("\\n".toRegex(), "\n                         ")
      )
    }

    fun displayLevel() = when (level) {
      Log.VERBOSE -> "V"
      Log.DEBUG -> "D"
      Log.INFO -> "I"
      Log.WARN -> "W"
      Log.ERROR -> "E"
      Log.ASSERT -> "A"
      else -> "?"
    }
  }

  companion object {
    private const val BUFFER_SIZE = 200
  }
}
