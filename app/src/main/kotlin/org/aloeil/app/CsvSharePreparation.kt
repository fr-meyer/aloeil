package org.aloeil.app

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retain the file even when cancellation discards an IO result during Activity recreation. */
internal suspend fun prepareAndOpenCsvShare(
    write: () -> File,
    remove: (File) -> Unit,
    open: (File) -> Unit,
): Boolean {
    var file: File? = null
    var opened = false
    try {
        withContext(Dispatchers.IO) {
            // Assignment occurs before returning to the possibly cancelled screen scope.
            file = write()
        }
        currentCoroutineContext().ensureActive()
        open(checkNotNull(file))
        opened = true
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    } finally {
        if (!opened) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    // A failed removal retains its scheduled lease and cache-change notification.
                    file?.let { runCatching { remove(it) } }
                }
            }
        }
    }
}
