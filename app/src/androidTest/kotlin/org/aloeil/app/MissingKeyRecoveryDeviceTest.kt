package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.Eye
import org.aloeil.app.data.MissingReadingKeyException
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.UnreadableLocalStoreException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic records are used; the reset is always confirmed in the UI. */
@RunWith(AndroidJUnit4::class)
class MissingKeyRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unreadableRowsRequireTwoExplicitActionsBeforeReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val original = ReadingRepository(db.readings(), SyntheticCipher())
            runBlocking {
                original.startSitting("synthetic-sitting")
                original.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
            }
            val unreadable = ReadingRepository(db.readings(), SyntheticCipher(8))
            runBlocking {
                val failure = runCatching { unreadable.verifyReadable() }.exceptionOrNull()
                check(failure is UnreadableLocalStoreException)
            }
            val confirmedResets = AtomicInteger(0)
            compose.setContent {
                AloeilApp(unreadable, resetUnreadableStore = {
                    confirmedResets.incrementAndGet()
                })
            }
            fun tap(id: Int) {
                val target = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().performClick()
            }
            tap(R.string.recovery_prepare_reset)
            check(confirmedResets.get() == 0)
            runBlocking { check(db.readings().allReadings().size == 1) }
            tap(R.string.back)
            check(confirmedResets.get() == 0)
            tap(R.string.recovery_prepare_reset)
            tap(R.string.recovery_confirm_reset)
            compose.waitUntil(timeoutMillis = 5_000) { confirmedResets.get() == 1 }
        } finally {
            db.close()
        }
    }

    @Test
    fun missingKeystoreKeyCannotBeSilentlyRegeneratedOnOpen() {
        val alias = "aloeil-synthetic-key-loss-" + java.util.UUID.randomUUID()
        val cipher = AndroidKeystoreReadingCipher(alias)
        try {
            val sealed = cipher.seal("synthetic".toByteArray())
            cipher.deleteKeyForRecovery()
            check(runCatching { cipher.open(sealed) }.exceptionOrNull()
                is MissingReadingKeyException)
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!store.containsAlias(alias))
        } finally {
            cipher.deleteKeyForRecovery()
        }
    }
}
