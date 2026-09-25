package org.aloeil.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Guard against moving encrypted Room rows without their non-exportable key. */
@RunWith(AndroidJUnit4::class)
class BackupPolicyDeviceTest {
    @Test
    fun cloudAndDeviceTransferExcludeAppData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        val excluded = mutableMapOf<String, MutableSet<String>>()
        context.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            var section = ""
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                when (xml.eventType) {
                    XmlPullParser.START_TAG -> when (xml.name) {
                        "cloud-backup", "device-transfer" -> section = xml.name
                        "exclude" -> if (xml.getAttributeValue(null, "path") == ".") {
                            excluded.getOrPut(section) { mutableSetOf() }
                                .add(xml.getAttributeValue(null, "domain"))
                        }
                    }
                    XmlPullParser.END_TAG -> if (xml.name == section) section = ""
                }
            }
        }
        val required = setOf("root", "database", "file", "sharedpref", "external")
        check(excluded["cloud-backup"]?.containsAll(required) == true)
        check(excluded["device-transfer"]?.containsAll(required) == true)
    }
}
