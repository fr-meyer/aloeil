package org.aloeil.app

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
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

/** The encrypted write owner must survive Activity recreation and erase its buffer. */
@RunWith(AndroidJUnit4::class)
class ArchiveExportJobDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun writeCompletesAfterActivityRecreation() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bytes = ByteArray(64) { 7 }
        try {
            val job = ArchiveExportJob(owner) { _, _, _ ->
                started.complete(Unit)
                release.await()
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val id = job.start(context, Uri.parse("content://synthetic/backup"), bytes)
            runBlocking { withTimeout(10_000) { started.await() } }
            // Model the disposed screen's stale buffer reference while the job owns it.
            job.scrubUnlessOwned(bytes)
            check(bytes.all { it == 7.toByte() })
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
            release.complete(Unit)
            val finished = runBlocking {
                withTimeout(10_000) {
                    job.state.first { it is ArchiveWriteState.Finished && it.id == id }
                }
            } as ArchiveWriteState.Finished
            check(finished.saved)
            check(bytes.all { it == 0.toByte() })
        } finally {
            release.complete(Unit)
            owner.cancel()
        }
    }
}
