package org.aloeil.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.Eye
import org.aloeil.app.data.MissingReadingKeyException
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.UnreadableLocalStoreException
import org.junit.Test
import org.junit.runner.RunWith

/** Rehearses key loss and user-confirmed local reset using synthetic values. */
@RunWith(AndroidJUnit4::class)
class ApplicationResetRestoreDeviceTest {
    @Test
    fun missingKeyThenResetAllowsPortableArchiveRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = AndroidKeystoreReadingCipher()
        // Isolate this emulator fixture from any prior app-owned synthetic state.
        ReadingDatabase.open(context).readings().allReadings()
        ReadingDatabase.resetUnreadableStore(context)
        cipher.deleteKeyForRecovery()
        val before = ReadingDatabase.open(context)
        val original = ReadingRepository(before.readings(), cipher, { "UTC" })
        original.startSitting("synthetic-sitting")
        original.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
        val passphrase = "synthetic-recovery-only".toCharArray()
        val archive = original.exportArchive(passphrase)
        cipher.deleteKeyForRecovery()
        val lost = ReadingRepository(before.readings(), AndroidKeystoreReadingCipher())
        val failure = runCatching { lost.verifyReadable() }.exceptionOrNull()
        check(failure is UnreadableLocalStoreException)
        check(failure.cause is MissingReadingKeyException)
        check(before.readings().allReadings().size == 1)

        // The production UI calls these only after the second confirmation action.
        ReadingDatabase.resetUnreadableStore(context)
        cipher.deleteKeyForRecovery()
        val fresh = ReadingDatabase.open(context)
        val restored = ReadingRepository(fresh.readings(), AndroidKeystoreReadingCipher())
        check(restored.verifyReadable() == Unit)
        check(restored.all().isEmpty())
        check(restored.importArchive(archive, passphrase) == 1)
        check(restored.all().single().value == "12.3")
        check(restored.all().single().sittingId == "synthetic-sitting")
    }
}
