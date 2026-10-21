package org.aloeil.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aloeil.app.data.ReadingDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Activity recreation must reuse the application-owned Room instance. */
@RunWith(AndroidJUnit4::class)
class ActivityRecreationDatabaseDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun recreationKeepsOneDatabaseInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = ReadingDatabase.open(context)
        activity.scenario.recreate()
        val after = ReadingDatabase.open(context)
        check(before === after)
    }
}
