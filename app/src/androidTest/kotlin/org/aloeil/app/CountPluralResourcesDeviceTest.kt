package org.aloeil.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountPluralResourcesDeviceTest {
    private fun resources(language: String): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration).resources
    }

    private fun Resources.quantity(id: Int, count: Int): String =
        getQuantityString(id, count, count)

    private fun Resources.archiveContents(readings: Int, sittings: Int): String =
        getString(
            R.string.archive_contents,
            quantity(R.plurals.archive_reading_count, readings),
            quantity(R.plurals.archive_sitting_count, sittings),
        )

    @Test
    fun archiveContentsInflectsBothCountsIndependently() {
        val en = resources("en")
        check(en.archiveContents(1, 1) == "1 reading in 1 sitting")
        check(en.archiveContents(1, 2) == "1 reading in 2 sittings")
        check(en.archiveContents(2, 1) == "2 readings in 1 sitting")
        check(en.archiveContents(2, 2) == "2 readings in 2 sittings")

        val fr = resources("fr")
        check(fr.archiveContents(1, 1) == "1 mesure dans 1 séance")
        check(fr.archiveContents(1, 2) == "1 mesure dans 2 séances")
        check(fr.archiveContents(2, 1) == "2 mesures dans 1 séance")
        check(fr.archiveContents(2, 2) == "2 mesures dans 2 séances")

        val ko = resources("ko")
        check(ko.archiveContents(1, 1) == "측정값 1개, 기록 세션 1개")
        check(ko.archiveContents(1, 2) == "측정값 1개, 기록 세션 2개")
        check(ko.archiveContents(2, 1) == "측정값 2개, 기록 세션 1개")
        check(ko.archiveContents(2, 2) == "측정값 2개, 기록 세션 2개")
    }

    @Test
    fun everyRequestedCountResourceRendersOneAndManyInEachLocale() {
        val en = resources("en")
        check(en.quantity(R.plurals.archive_added, 1) ==
            "1 new reading added. Existing readings were kept.")
        check(en.quantity(R.plurals.archive_added, 2) ==
            "2 new readings added. Existing readings were kept.")
        check(en.quantity(R.plurals.archive_deleted_markers, 1).startsWith(
            "This backup also contains 1 deleted reading marker. It prevents an old file",
        ))
        check(en.quantity(R.plurals.archive_deleted_markers, 2).startsWith(
            "This backup also contains 2 deleted reading markers. They prevent old files",
        ))
        check(en.quantity(R.plurals.history_count, 1) == "1 reading")
        check(en.quantity(R.plurals.history_count, 2) == "2 readings")
        check(en.quantity(R.plurals.history_range_list_only, 1) ==
            "1 device range state appears in the list only.")
        check(en.quantity(R.plurals.history_range_list_only, 2) ==
            "2 device range states appear in the list only.")
        check(en.quantity(R.plurals.csv_count, 1) == "1 current reading will be exported.")
        check(en.quantity(R.plurals.csv_count, 2) == "2 current readings will be exported.")

        val fr = resources("fr")
        check(fr.quantity(R.plurals.archive_added, 1) ==
            "1 nouvelle mesure ajoutée. Les mesures existantes ont été conservées.")
        check(fr.quantity(R.plurals.archive_added, 2) ==
            "2 nouvelles mesures ajoutées. Les mesures existantes ont été conservées.")
        check(fr.quantity(R.plurals.archive_deleted_markers, 1).startsWith(
            "Cette sauvegarde contient aussi 1 repère de mesure supprimée. Il empêche un ancien fichier",
        ))
        check(fr.quantity(R.plurals.archive_deleted_markers, 2).startsWith(
            "Cette sauvegarde contient aussi 2 repères de mesures supprimées. Ils empêchent les anciens fichiers",
        ))
        check(fr.quantity(R.plurals.history_count, 1) == "1 mesure")
        check(fr.quantity(R.plurals.history_count, 2) == "2 mesures")
        check(fr.quantity(R.plurals.history_range_list_only, 1) ==
            "1 état hors plage de l’appareil figure uniquement dans la liste.")
        check(fr.quantity(R.plurals.history_range_list_only, 2) ==
            "2 états hors plage de l’appareil figurent uniquement dans la liste.")
        check(fr.quantity(R.plurals.csv_count, 1) == "1 mesure actuelle sera exportée.")
        check(fr.quantity(R.plurals.csv_count, 2) == "2 mesures actuelles seront exportées.")

        val ko = resources("ko")
        check(ko.quantity(R.plurals.archive_added, 1) ==
            "새 측정값 1개를 추가했습니다. 기존 측정값은 유지되었습니다.")
        check(ko.quantity(R.plurals.archive_added, 2) ==
            "새 측정값 2개를 추가했습니다. 기존 측정값은 유지되었습니다.")
        check(ko.quantity(R.plurals.archive_deleted_markers, 1).startsWith(
            "이 백업에는 삭제된 측정값 표시 1개도 포함됩니다.",
        ))
        check(ko.quantity(R.plurals.archive_deleted_markers, 2).startsWith(
            "이 백업에는 삭제된 측정값 표시 2개도 포함됩니다.",
        ))
        check(ko.quantity(R.plurals.history_count, 1) == "측정값 1개")
        check(ko.quantity(R.plurals.history_count, 2) == "측정값 2개")
        check(ko.quantity(R.plurals.history_range_list_only, 1) ==
            "기기 측정 범위 상태 1개는 목록에만 표시됩니다.")
        check(ko.quantity(R.plurals.history_range_list_only, 2) ==
            "기기 측정 범위 상태 2개는 목록에만 표시됩니다.")
        check(ko.quantity(R.plurals.csv_count, 1) == "현재 측정값 1개를 내보냅니다.")
        check(ko.quantity(R.plurals.csv_count, 2) == "현재 측정값 2개를 내보냅니다.")
    }
}
