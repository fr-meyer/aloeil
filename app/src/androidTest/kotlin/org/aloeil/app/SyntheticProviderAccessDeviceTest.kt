package org.aloeil.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
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
    private val fixturePermission =
        "org.aloeil.app.test.permission.ACCESS_SYNTHETIC_CSV_PROVIDER"

    @Test
    fun targetCanUseSignatureProtectedFixtureButShellCannotReadOrControlIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packages = context.packageManager
        val provider = packages.resolveContentProvider(
            "org.aloeil.app.test.syntheticcsv", PackageManager.GET_META_DATA,
        ) ?: error("Synthetic fixture provider unavailable")
        check(provider.exported)
        check(provider.grantUriPermissions)
        check(provider.readPermission == fixturePermission)
        check(provider.writePermission == fixturePermission)
        val permission = packages.getPermissionInfo(fixturePermission, 0)
        check(permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
            PermissionInfo.PROTECTION_SIGNATURE)
        val target = packages.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        check(fixturePermission in (target.requestedPermissions ?: emptyArray()))

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
            val before = control("deniedCalls")?.getInt("count")
                ?: error("Fixture rejection count unavailable")
            // The package manager rejects shell before provider code runs. A successful content
            // read would print the non-empty seed; a successful call would print its Bundle.
            val deniedRead = shell("content read --uri content://org.aloeil.app.test.syntheticcsv/export.csv")
            check(deniedRead.isEmpty())
            check(control("deniedCalls")?.getInt("count") == before)
            val deniedControl = shell("content call --uri content://org.aloeil.app.test.syntheticcsv/export.csv --method hold")
            check(deniedControl.isEmpty())
            check(control("deniedCalls")?.getInt("count") == before)
            check(control("waiting")?.getBoolean("waiting") == false)
            // A denied caller cannot corrupt the target's fixture or prevent its use.
            check(resolver.openInputStream(uri)?.use { it.readBytes() }?.contentEquals(seed) == true)
        } finally {
            control("release")
            seed.fill(0)
        }
    }
}
