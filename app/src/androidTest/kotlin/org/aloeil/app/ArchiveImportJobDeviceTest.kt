package org.aloeil.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Activity recreation must not erase an archive while restore still owns it. */
@RunWith(AndroidJUnit4::class)
class ArchiveImportJobDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun restoreCompletesAfterActivityRecreation() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bytes = ByteArray(64) { 7 }
        val passphrase = "synthetic-only".toCharArray()
        try {
            val job = ArchiveImportJob(owner)
            val id = job.start(bytes, passphrase) { payload, secret ->
                check(payload === bytes && secret === passphrase)
                started.complete(Unit)
                release.await()
                check(payload.all { it == 7.toByte() })
                3
            }
            runBlocking { withTimeout(10_000) { started.await() } }
            var originalActivity = 0
            compose.activityRule.scenario.onActivity { activity ->
                originalActivity = System.identityHashCode(activity)
                activity.recreate()
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    var recreated = false
                    compose.activityRule.scenario.onActivity { activity ->
                        recreated = System.identityHashCode(activity) != originalActivity
                    }
                    recreated
                }.getOrDefault(false)
            }
            // Model the disposed screen's stale reference while restore owns it.
            if (!job.owns(bytes)) archiveExportJob.scrubUnlessOwned(bytes)
            check(bytes.all { it == 7.toByte() })
            release.complete(Unit)
            val finished = runBlocking {
                withTimeout(10_000) {
                    job.state.first { it is ArchiveImportState.Finished && it.id == id }
                }
            } as ArchiveImportState.Finished
            check(finished.success && finished.added == 3)
            check(bytes.all { it == 0.toByte() })
            check(passphrase.all { it == '\u0000' })
        } finally {
            release.complete(Unit)
            owner.cancel()
        }
    }
}
