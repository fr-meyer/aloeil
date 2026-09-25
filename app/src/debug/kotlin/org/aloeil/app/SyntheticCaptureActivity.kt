package org.aloeil.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import java.util.UUID
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository

/** Debug APK only: an isolated repository for Activity recreation tests. */
class SyntheticCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SyntheticCaptureStore(applicationContext) as T
        })[SyntheticCaptureStore::class.java]
        val capture = ViewModelProvider(this)[CaptureUiState::class.java]
        setContent { AloeilApp(store.repository, captureState = capture) }
    }
}

private class SyntheticCaptureStore(context: Context) : ViewModel() {
    private val database = Room.inMemoryDatabaseBuilder(
        context, ReadingDatabase::class.java,
    ).build()
    private val cipher = AndroidKeystoreReadingCipher(
        "aloeil-debug-capture-" + UUID.randomUUID(),
    )
    val repository = ReadingRepository(database.readings(), cipher)

    override fun onCleared() {
        database.close()
        cipher.deleteKeyForRecovery()
        super.onCleared()
    }
}
