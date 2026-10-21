package org.aloeil.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the installed manifest rather than just the source declaration. */
@RunWith(AndroidJUnit4::class)
class InstalledPrivacyDeviceTest {
    @Test
    fun installedAppHasNoNetworkOrAutomaticBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        )
        check("android.permission.INTERNET" !in (packageInfo.requestedPermissions ?: emptyArray()))
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
    }

    @Test
    fun shareProviderIsPrivateAndGrantsTemporaryUriAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = context.packageManager.resolveContentProvider(
            "org.aloeil.app.fileprovider", PackageManager.GET_META_DATA,
        ) ?: error("Share provider missing")
        check(!provider.exported)
        check(provider.grantUriPermissions)
    }
}
