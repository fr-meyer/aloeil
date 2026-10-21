package org.aloeil.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Test APK access checks run only against this deliberately synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class SyntheticProviderAccessDeviceTest {
    @Test
    fun targetCanUseFixtureButUnrelatedBinderCallerCannotReadOrControlIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val uri = Uri.parse("content://org.aloeil.app.test.syntheticcsv/export.csv")
        val seed = "synthetic-provider-only".toByteArray()
        fun control(method: String) = resolver.call(uri, method, null, null)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        try {
            control("release")
            resolver.openOutputStream(uri)?.use { it.write(seed) }
                ?: error("Synthetic fixture write unavailable")
            val read = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Synthetic fixture read unavailable")
            check(read.contentEquals(seed))
            val before = control("deniedCalls")?.getInt("count") ?: error("Fixture rejection count unavailable")
            // API 30 UiAutomation captures stdout only and does not interpret shell redirection.
            // The authorized counter proves both commands reached the provider's denial guard.
            val deniedRead = shell("content read --uri content://org.aloeil.app.test.syntheticcsv/export.csv")
            check(deniedRead.isEmpty())
            check(control("deniedCalls")?.getInt("count") == before + 1)
            val deniedControl = shell("content call --uri content://org.aloeil.app.test.syntheticcsv/export.csv --method hold")
            check(deniedControl.isEmpty())
            check(control("deniedCalls")?.getInt("count") == before + 2)
            check(control("waiting")?.getBoolean("waiting") == false)
            // A denied caller cannot corrupt the target's fixture or prevent its use.
            check(resolver.openInputStream(uri)?.use { it.readBytes() }?.contentEquals(seed) == true)
        } finally {
            control("release")
            seed.fill(0)
        }
    }
}
